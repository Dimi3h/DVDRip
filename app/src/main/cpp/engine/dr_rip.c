#include "dr_rip.h"

#include "dr_json.h"
#include "dr_plan.h"
#include "dr_reader.h"

#include <errno.h>
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/audio_fifo.h>
#include <libavutil/channel_layout.h>
#include <libavutil/time.h>
#include <dvdread/ifo_read.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define MAX_TRACKS 8
#define MAX_WARNINGS 16
#define INPUT_BUFFER_SIZE (64 * 1024)
#define OUTPUT_BUFFER_SIZE (1024 * 1024)
#define PROGRESS_INTERVAL_US 500000

static const AVRational MPEG_TIME_BASE = {1, 90000};

typedef struct audio_track {
    const dr_audio_choice *choice;
    const audio_attr_t *attr;
    int in_index;
    int64_t last_offset;
    AVStream *out;
    int64_t last_dts;
    /* LPCM is decoded and encoded again; everything else is copied. */
    int transcode;
    AVCodecContext *decoder;
    AVCodecContext *encoder;
    AVAudioFifo *fifo;
    AVFrame *decoded;
    AVFrame *chunk;
    int64_t next_pts; /* encoder time base; AV_NOPTS_VALUE until the first frame */
} audio_track;

typedef struct rip {
    const dr_rip_options *options;
    const dr_rip_hooks *hooks;
    dr_logger logger;
    dr_plan plan;
    dr_reader *reader;
    int read_status;

    AVFormatContext *in;
    AVIOContext *in_io;
    AVFormatContext *out;
    AVPacket *packet;
    AVPacket *encoded;

    int video_in;
    int64_t video_last_offset;
    AVStream *video_out;
    int64_t video_last_dts;
    int64_t video_end;

    audio_track tracks[MAX_TRACKS];
    int track_count;

    int write_errno;
    int64_t last_progress;
    char warnings[MAX_WARNINGS][160];
    int warning_count;
    int clamped;
} rip;

static void warn(rip *r, const char *format, ...) __attribute__((format(printf, 2, 3)));

static void warn(rip *r, const char *format, ...) {
    char text[160];
    va_list args;
    va_start(args, format);
    vsnprintf(text, sizeof(text), format, args);
    va_end(args);
    dr_logf(&r->logger, DR_LOG_WARN, "%s", text);
    if (r->warning_count < MAX_WARNINGS) {
        memcpy(r->warnings[r->warning_count++], text, sizeof(text));
    }
}

static int is_cancelled(void *opaque) {
    rip *r = opaque;
    return r->hooks != NULL && r->hooks->cancelled != NULL && r->hooks->cancelled(r->hooks->opaque);
}

static int interrupt(void *opaque) {
    return is_cancelled(opaque);
}

static int read_input(void *opaque, uint8_t *buffer, int size) {
    rip *r = opaque;
    int result = dr_reader_read(r->reader, buffer, size);
    if (result > 0) return result;
    if (result == DR_READ_END) return AVERROR_EOF;
    r->read_status = result;
    return result == DR_READ_CANCELLED ? AVERROR_EXIT : AVERROR(EIO);
}

static int write_output(void *opaque, const uint8_t *buffer, int size) {
    rip *r = opaque;
    int done = 0;
    while (done < size) {
        ssize_t written = write(r->options->output_fd, buffer + done, (size_t) (size - done));
        if (written < 0) {
            if (errno == EINTR) continue;
            r->write_errno = errno;
            return AVERROR(errno);
        }
        done += (int) written;
    }
    return size;
}

static int64_t seek_output(void *opaque, int64_t offset, int whence) {
    rip *r = opaque;
    if (whence == AVSEEK_SIZE) {
        struct stat info;
        return fstat(r->options->output_fd, &info) == 0 ? (int64_t) info.st_size : AVERROR(errno);
    }
    off_t position = lseek(r->options->output_fd, (off_t) offset, whence & ~AVSEEK_FORCE);
    return position < 0 ? AVERROR(errno) : (int64_t) position;
}

static int64_t output_size(const rip *r) {
    struct stat info;
    return fstat(r->options->output_fd, &info) == 0 ? (int64_t) info.st_size : 0;
}

/* Offset to line up a packet read at position; packets without a position keep the last one. */
static int64_t offset_for(rip *r, int64_t position, int64_t *last) {
    if (position >= 0) *last = dr_reader_offset(r->reader, position);
    return *last;
}

static void report_progress(rip *r, int force) {
    if (r->hooks == NULL || r->hooks->progress == NULL) return;
    int64_t now = av_gettime_relative();
    if (!force && now - r->last_progress < PROGRESS_INTERVAL_US) return;
    r->last_progress = now;
    dr_progress progress = {
        .sectors_done = dr_reader_sectors_done(r->reader),
        .sectors_total = r->plan.sector_count,
        .output_bytes = r->out != NULL && r->out->pb != NULL ? avio_tell(r->out->pb) : 0,
        .position_ms = r->video_end > 0 ? r->video_end / 90 : 0,
    };
    r->hooks->progress(r->hooks->opaque, &progress);
}

/* Writes a packet whose timestamps are in the output stream's time base, keeping DTS monotonic. */
static int write_packet(rip *r, AVPacket *packet, int64_t *last_dts) {
    if (packet->dts != AV_NOPTS_VALUE && *last_dts != AV_NOPTS_VALUE && packet->dts < *last_dts) {
        packet->dts = *last_dts;
        if (packet->pts != AV_NOPTS_VALUE && packet->pts < packet->dts) packet->pts = packet->dts;
        r->clamped++;
    }
    if (packet->dts != AV_NOPTS_VALUE) *last_dts = packet->dts;
    int result = av_interleaved_write_frame(r->out, packet);
    if (result < 0) dr_logf(&r->logger, DR_LOG_ERROR, "Writing a packet failed: %s", av_err2str(result));
    return result;
}

static audio_track *track_for_stream(rip *r, int index) {
    for (int i = 0; i < r->track_count; i++) {
        if (r->tracks[i].in_index == index) return &r->tracks[i];
    }
    return NULL;
}

static enum AVCodecID copy_codec(const audio_attr_t *attr) {
    switch (attr->audio_format) {
        case 0: return AV_CODEC_ID_AC3;
        case 2:
        case 3: return AV_CODEC_ID_MP2;
        case 6: return AV_CODEC_ID_DTS;
        default: return AV_CODEC_ID_NONE;
    }
}

static const audio_attr_t *audio_attr_for(const rip *r, int stream_id) {
    const vtsi_mat_t *mat = r->plan.vts->vtsi_mat;
    for (int i = 0; i < mat->nr_of_vts_audio_streams && i < 8; i++) {
        if (dr_audio_stream_id(&mat->vts_audio_attr[i], r->plan.pgc->audio_control[i]) == stream_id) {
            return &mat->vts_audio_attr[i];
        }
    }
    return NULL;
}

/* Encoder for an LPCM track: PCM of the same depth, or FLAC. */
static int open_lpcm_encoder(rip *r, audio_track *track, const AVStream *in) {
    int sample_rate = track->attr->sample_frequency == 1 ? 96000 : 48000;
    int channels = track->attr->channels + 1;
    int bits = track->attr->quantization == 0 ? 16 : 24;
    if (in != NULL && in->codecpar->sample_rate > 0) sample_rate = in->codecpar->sample_rate;
    if (in != NULL && in->codecpar->ch_layout.nb_channels > 0) channels = in->codecpar->ch_layout.nb_channels;
    if (in != NULL && in->codecpar->bits_per_coded_sample > 0) bits = in->codecpar->bits_per_coded_sample > 16 ? 24 : 16;

    enum AVCodecID id = r->options->lpcm_to_flac ? AV_CODEC_ID_FLAC : (bits > 16 ? AV_CODEC_ID_PCM_S24LE : AV_CODEC_ID_PCM_S16LE);
    const AVCodec *codec = avcodec_find_encoder(id);
    if (codec == NULL) return AVERROR_ENCODER_NOT_FOUND;
    AVCodecContext *encoder = avcodec_alloc_context3(codec);
    if (encoder == NULL) return AVERROR(ENOMEM);
    encoder->sample_rate = sample_rate;
    encoder->sample_fmt = bits > 16 ? AV_SAMPLE_FMT_S32 : AV_SAMPLE_FMT_S16;
    encoder->bits_per_raw_sample = bits;
    av_channel_layout_default(&encoder->ch_layout, channels);
    encoder->time_base = (AVRational) {1, sample_rate};
    if (r->out->oformat->flags & AVFMT_GLOBALHEADER) encoder->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
    int result = avcodec_open2(encoder, codec, NULL);
    if (result < 0) {
        avcodec_free_context(&encoder);
        return result;
    }
    track->encoder = encoder;
    track->out->time_base = encoder->time_base;
    result = avcodec_parameters_from_context(track->out->codecpar, encoder);
    if (result < 0) return result;
    if (encoder->frame_size > 0 && !(codec->capabilities & AV_CODEC_CAP_VARIABLE_FRAME_SIZE)) {
        track->fifo = av_audio_fifo_alloc(encoder->sample_fmt, channels, encoder->frame_size * 4);
        if (track->fifo == NULL) return AVERROR(ENOMEM);
    }
    track->decoded = av_frame_alloc();
    track->chunk = av_frame_alloc();
    if (track->decoded == NULL || track->chunk == NULL) return AVERROR(ENOMEM);
    track->next_pts = AV_NOPTS_VALUE;
    return 0;
}

static int open_lpcm_decoder(rip *r, audio_track *track, const AVStream *in) {
    const AVCodec *codec = avcodec_find_decoder(AV_CODEC_ID_PCM_DVD);
    if (codec == NULL) return AVERROR_DECODER_NOT_FOUND;
    AVCodecContext *decoder = avcodec_alloc_context3(codec);
    if (decoder == NULL) return AVERROR(ENOMEM);
    int result = avcodec_parameters_to_context(decoder, in->codecpar);
    if (result >= 0) {
        decoder->pkt_timebase = in->time_base;
        result = avcodec_open2(decoder, codec, NULL);
    }
    if (result < 0) {
        avcodec_free_context(&decoder);
        dr_logf(&r->logger, DR_LOG_ERROR, "LPCM decoder: %s", av_err2str(result));
        return result;
    }
    track->decoder = decoder;
    return 0;
}

static int drain_encoder(rip *r, audio_track *track) {
    for (;;) {
        int result = avcodec_receive_packet(track->encoder, r->encoded);
        if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) return 0;
        if (result < 0) return result;
        av_packet_rescale_ts(r->encoded, track->encoder->time_base, track->out->time_base);
        r->encoded->stream_index = track->out->index;
        result = write_packet(r, r->encoded, &track->last_dts);
        if (result < 0) return result;
    }
}

static int encode_samples(rip *r, audio_track *track, AVFrame *frame) {
    if (frame != NULL) {
        frame->pts = track->next_pts;
        track->next_pts += frame->nb_samples;
    }
    int result = avcodec_send_frame(track->encoder, frame);
    if (result < 0 && result != AVERROR_EOF) return result;
    return drain_encoder(r, track);
}

/* Feeds the FIFO to a fixed-frame-size encoder; with flush, also the short remainder. */
static int encode_from_fifo(rip *r, audio_track *track, int flush) {
    int frame_size = track->encoder->frame_size;
    while (av_audio_fifo_size(track->fifo) >= frame_size || (flush && av_audio_fifo_size(track->fifo) > 0)) {
        int samples = FFMIN(frame_size, av_audio_fifo_size(track->fifo));
        AVFrame *chunk = track->chunk;
        av_frame_unref(chunk);
        chunk->nb_samples = samples;
        chunk->format = track->encoder->sample_fmt;
        chunk->sample_rate = track->encoder->sample_rate;
        int result = av_channel_layout_copy(&chunk->ch_layout, &track->encoder->ch_layout);
        if (result >= 0) result = av_frame_get_buffer(chunk, 0);
        if (result < 0) return result;
        if (av_audio_fifo_read(track->fifo, (void **) chunk->data, samples) < samples) return AVERROR(EIO);
        result = encode_samples(r, track, chunk);
        if (result < 0) return result;
    }
    return 0;
}

static int handle_decoded(rip *r, audio_track *track, AVFrame *frame) {
    AVCodecContext *encoder = track->encoder;
    if (frame->format != encoder->sample_fmt || frame->sample_rate != encoder->sample_rate ||
        frame->ch_layout.nb_channels != encoder->ch_layout.nb_channels) {
        warn(r, "LPCM track 0x%X changed format mid-stream; samples dropped", track->choice->stream_id);
        return 0;
    }
    int64_t expected = frame->pts == AV_NOPTS_VALUE ? AV_NOPTS_VALUE
                       : av_rescale_q(frame->pts, track->decoder->pkt_timebase, encoder->time_base);
    if (track->next_pts == AV_NOPTS_VALUE) {
        if (expected == AV_NOPTS_VALUE || expected < 0) return 0; /* before the video starts */
        track->next_pts = expected;
    } else if (expected != AV_NOPTS_VALUE) {
        int64_t queued = track->fifo != NULL ? av_audio_fifo_size(track->fifo) : 0;
        int64_t drift = expected - (track->next_pts + queued);
        if (llabs(drift) > encoder->sample_rate / 10) {
            /* A gap or overlap of more than 100 ms: follow the source clock again. */
            if (track->fifo != NULL) {
                int result = encode_from_fifo(r, track, 1);
                if (result < 0) return result;
            }
            track->next_pts = expected;
        }
    }
    if (track->fifo != NULL) {
        if (av_audio_fifo_write(track->fifo, (void **) frame->data, frame->nb_samples) < frame->nb_samples) {
            return AVERROR(ENOMEM);
        }
        return encode_from_fifo(r, track, 0);
    }
    return encode_samples(r, track, frame);
}

static int transcode_packet(rip *r, audio_track *track, AVPacket *packet) {
    int result = avcodec_send_packet(track->decoder, packet);
    if (result < 0) {
        /* A damaged LPCM packet: skip it rather than the rip. */
        dr_logf(&r->logger, DR_LOG_WARN, "LPCM packet rejected: %s", av_err2str(result));
        return 0;
    }
    for (;;) {
        result = avcodec_receive_frame(track->decoder, track->decoded);
        if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) return 0;
        if (result < 0) return result;
        result = handle_decoded(r, track, track->decoded);
        av_frame_unref(track->decoded);
        if (result < 0) return result;
    }
}

static int flush_track(rip *r, audio_track *track) {
    if (!track->transcode || track->encoder == NULL) return 0;
    if (track->decoder != NULL) {
        int result = transcode_packet(r, track, NULL);
        if (result < 0) return result;
    }
    if (track->fifo != NULL) {
        int result = encode_from_fifo(r, track, 1);
        if (result < 0) return result;
    }
    return encode_samples(r, track, NULL);
}

static int add_audio_output(rip *r, audio_track *track) {
    track->out = avformat_new_stream(r->out, NULL);
    if (track->out == NULL) return AVERROR(ENOMEM);
    track->last_dts = AV_NOPTS_VALUE;
    const AVStream *in = track->in_index >= 0 ? r->in->streams[track->in_index] : NULL;
    if (track->attr->audio_format == 4) {
        track->transcode = 1;
        int result = open_lpcm_encoder(r, track, in);
        if (result < 0) return result;
        if (in != NULL) {
            result = open_lpcm_decoder(r, track, in);
            if (result < 0) return result;
        }
    } else if (in != NULL) {
        int result = avcodec_parameters_copy(track->out->codecpar, in->codecpar);
        if (result < 0) return result;
        track->out->codecpar->codec_tag = 0;
        track->out->time_base = in->time_base;
    } else {
        /* Not seen while probing: describe it from the IFO so packets arriving later still fit. */
        AVCodecParameters *par = track->out->codecpar;
        par->codec_type = AVMEDIA_TYPE_AUDIO;
        par->codec_id = copy_codec(track->attr);
        par->sample_rate = track->attr->sample_frequency == 1 ? 96000 : 48000;
        av_channel_layout_default(&par->ch_layout, track->attr->channels + 1);
        track->out->time_base = MPEG_TIME_BASE;
    }
    const dr_audio_choice *choice = track->choice;
    if (choice->language != NULL && choice->language[0]) av_dict_set(&track->out->metadata, "language", choice->language, 0);
    if (choice->name != NULL && choice->name[0]) av_dict_set(&track->out->metadata, "title", choice->name, 0);
    if (choice->is_default) track->out->disposition |= AV_DISPOSITION_DEFAULT;
    return 0;
}

/* Late streams: a chosen audio stream may first appear after probing. */
static void adopt_new_stream(rip *r, int index) {
    AVStream *st = r->in->streams[index];
    for (int i = 0; i < r->track_count; i++) {
        audio_track *track = &r->tracks[i];
        if (track->in_index < 0 && track->choice->stream_id == st->id) {
            track->in_index = index;
            if (track->transcode && track->decoder == NULL) {
                if (open_lpcm_decoder(r, track, st) < 0) track->in_index = -2;
            }
            return;
        }
    }
    st->discard = AVDISCARD_ALL;
}

static int copy_packet(rip *r, AVPacket *packet, const AVStream *in, AVStream *out, int64_t *last_offset, int64_t *last_dts, int is_video) {
    int64_t offset = av_rescale_q(offset_for(r, packet->pos, last_offset), MPEG_TIME_BASE, in->time_base);
    if (packet->pts != AV_NOPTS_VALUE) packet->pts += offset;
    if (packet->dts != AV_NOPTS_VALUE) packet->dts += offset;
    int64_t presentation = packet->pts != AV_NOPTS_VALUE ? packet->pts : packet->dts;
    /* Leading frames from before the title's first VOBU, or B-frames of an open GOP. */
    if (presentation == AV_NOPTS_VALUE || presentation < 0) {
        av_packet_unref(packet);
        return 0;
    }
    if (is_video) {
        int64_t end = av_rescale_q(presentation + packet->duration, in->time_base, MPEG_TIME_BASE);
        if (end > r->video_end) r->video_end = end;
    }
    av_packet_rescale_ts(packet, in->time_base, out->time_base);
    packet->stream_index = out->index;
    packet->pos = -1;
    return write_packet(r, packet, last_dts);
}

static int add_chapters(rip *r) {
    int count = r->plan.chapter_count;
    int64_t end_time = dr_reader_end_time(r->reader);
    AVChapter **chapters = av_calloc((size_t) count, sizeof(AVChapter *));
    if (chapters == NULL) return AVERROR(ENOMEM);
    int written = 0;
    for (int c = 1; c <= count; c++) {
        int64_t start = dr_reader_chapter_start(r->reader, c);
        if (start < 0) continue;
        int64_t end = end_time;
        for (int next = c + 1; next <= count; next++) {
            int64_t next_start = dr_reader_chapter_start(r->reader, next);
            if (next_start >= 0) {
                end = next_start;
                break;
            }
        }
        if (end <= start) continue;
        AVChapter *chapter = av_mallocz(sizeof(AVChapter));
        if (chapter == NULL) break;
        chapter->id = written + 1;
        chapter->time_base = MPEG_TIME_BASE;
        chapter->start = start;
        chapter->end = end;
        char fallback[32];
        const char *name = NULL;
        if (r->options->chapter_names != NULL && c <= r->options->chapter_name_count) name = r->options->chapter_names[c - 1];
        if (name == NULL || !name[0]) {
            snprintf(fallback, sizeof(fallback), "Chapter %02d", written + 1);
            name = fallback;
        }
        av_dict_set(&chapter->metadata, "title", name, 0);
        chapters[written++] = chapter;
    }
    r->out->chapters = chapters;
    r->out->nb_chapters = (unsigned int) written;
    return 0;
}

static char *failure(const char *code, const char *message) {
    dr_json json;
    dr_json_init(&json);
    dr_json_begin_object(&json);
    dr_json_key(&json, "ok"); dr_json_bool(&json, 0);
    dr_json_key(&json, "error"); dr_json_string(&json, code);
    dr_json_key(&json, "message"); dr_json_string(&json, message);
    dr_json_end_object(&json);
    return dr_json_take(&json);
}

static char *success(rip *r) {
    dr_json json;
    dr_json_init(&json);
    dr_json_begin_object(&json);
    dr_json_key(&json, "ok"); dr_json_bool(&json, 1);
    dr_json_key(&json, "outputBytes"); dr_json_int(&json, output_size(r));
    dr_json_key(&json, "durationMs"); dr_json_int(&json, dr_reader_end_time(r->reader) / 90);
    dr_json_key(&json, "chapterStartsMs");
    dr_json_begin_array(&json);
    for (unsigned int i = 0; i < r->out->nb_chapters; i++) {
        dr_json_int(&json, av_rescale_q(r->out->chapters[i]->start, r->out->chapters[i]->time_base, (AVRational) {1, 1000}));
    }
    dr_json_end_array(&json);
    dr_json_key(&json, "audioTracks"); dr_json_int(&json, r->track_count);
    dr_json_key(&json, "badSectors");
    dr_json_begin_array(&json);
    const dr_bad_range *ranges = NULL;
    int range_count = dr_reader_bad_ranges(r->reader, &ranges);
    for (int i = 0; i < range_count; i++) {
        dr_json_begin_array(&json);
        dr_json_int(&json, ranges[i].first);
        dr_json_int(&json, ranges[i].count);
        dr_json_end_array(&json);
    }
    dr_json_end_array(&json);
    dr_json_key(&json, "warnings");
    dr_json_begin_array(&json);
    for (int i = 0; i < r->warning_count; i++) dr_json_string(&json, r->warnings[i]);
    dr_json_end_array(&json);
    dr_json_end_object(&json);
    return dr_json_take(&json);
}

static int open_input(rip *r) {
    r->in = avformat_alloc_context();
    uint8_t *buffer = av_malloc(INPUT_BUFFER_SIZE);
    if (r->in == NULL || buffer == NULL) {
        av_free(buffer);
        return AVERROR(ENOMEM);
    }
    AVIOContext *io = avio_alloc_context(buffer, INPUT_BUFFER_SIZE, 0, r, read_input, NULL, NULL);
    if (io == NULL) {
        av_free(buffer);
        return AVERROR(ENOMEM);
    }
    io->seekable = 0;
    r->in_io = io;
    r->in->pb = io;
    r->in->flags |= AVFMT_FLAG_CUSTOM_IO | AVFMT_FLAG_GENPTS;
    r->in->interrupt_callback = (AVIOInterruptCB) {interrupt, r};
    AVDictionary *options = NULL;
    av_dict_set(&options, "probesize", "33554432", 0);
    av_dict_set(&options, "analyzeduration", "30000000", 0);
    int result = avformat_open_input(&r->in, NULL, av_find_input_format("mpeg"), &options);
    av_dict_free(&options);
    if (result < 0) return result;
    return avformat_find_stream_info(r->in, NULL);
}

static int open_output(rip *r) {
    int result = avformat_alloc_output_context2(&r->out, NULL, "matroska", NULL);
    if (result < 0) return result;
    uint8_t *buffer = av_malloc(OUTPUT_BUFFER_SIZE);
    if (buffer == NULL) return AVERROR(ENOMEM);
    r->out->pb = avio_alloc_context(buffer, OUTPUT_BUFFER_SIZE, 1, r, NULL, write_output, seek_output);
    if (r->out->pb == NULL) {
        av_free(buffer);
        return AVERROR(ENOMEM);
    }
    r->out->flags |= AVFMT_FLAG_CUSTOM_IO;
    if (r->options->movie_title != NULL && r->options->movie_title[0]) {
        av_dict_set(&r->out->metadata, "title", r->options->movie_title, 0);
    }
    if (r->options->release_date != NULL && r->options->release_date[0]) {
        av_dict_set(&r->out->metadata, "DATE_RELEASED", r->options->release_date, 0);
    }

    const AVStream *video = r->in->streams[r->video_in];
    r->video_out = avformat_new_stream(r->out, NULL);
    if (r->video_out == NULL) return AVERROR(ENOMEM);
    result = avcodec_parameters_copy(r->video_out->codecpar, video->codecpar);
    if (result < 0) return result;
    r->video_out->codecpar->codec_tag = 0;
    r->video_out->time_base = video->time_base;
    r->video_out->sample_aspect_ratio = video->codecpar->sample_aspect_ratio;
    r->video_out->avg_frame_rate = video->avg_frame_rate;
    r->video_out->disposition = AV_DISPOSITION_DEFAULT;
    r->video_last_dts = AV_NOPTS_VALUE;

    for (int i = 0; i < r->track_count; i++) {
        result = add_audio_output(r, &r->tracks[i]);
        if (result < 0) return result;
    }
    return 0;
}

static void close_rip(rip *r) {
    for (int i = 0; i < r->track_count; i++) {
        audio_track *track = &r->tracks[i];
        avcodec_free_context(&track->decoder);
        avcodec_free_context(&track->encoder);
        if (track->fifo != NULL) av_audio_fifo_free(track->fifo);
        av_frame_free(&track->decoded);
        av_frame_free(&track->chunk);
    }
    /* Custom I/O stays ours: close_input leaves it, and a failed open_input already freed the context. */
    avformat_close_input(&r->in);
    if (r->in_io != NULL) {
        av_freep(&r->in_io->buffer);
        avio_context_free(&r->in_io);
    }
    if (r->out != NULL) {
        if (r->out->pb != NULL) {
            av_freep(&r->out->pb->buffer);
            avio_context_free(&r->out->pb);
        }
        avformat_free_context(r->out);
        r->out = NULL;
    }
    av_packet_free(&r->packet);
    av_packet_free(&r->encoded);
    dr_reader_close(r->reader);
    dr_plan_free(&r->plan);
}

/* A full disk gets its own code, so the app can say so instead of showing strerror(). */
static char *write_failure(const rip *r) {
    int code = r->write_errno;
    return failure(code == ENOSPC || code == EDQUOT ? "no_space" : "write_failed", strerror(code));
}

static char *read_failure(rip *r, int result) {
    if (r->read_status == DR_READ_CANCELLED || is_cancelled(r)) return failure("cancelled", "The rip was cancelled");
    if (r->read_status == DR_READ_SCRAMBLED) {
        return failure("scrambled", "The title is CSS protected and its key could not be recovered");
    }
    if (r->write_errno != 0) return write_failure(r);
    char message[160];
    snprintf(message, sizeof(message), "%s", av_err2str(result));
    return failure(r->read_status != 0 ? "unreadable" : "failed", message);
}

char *dr_rip(dvd_reader_t *dvd, const dr_rip_options *options, const dr_rip_hooks *hooks, const dr_logger *logger) {
    rip *r = calloc(1, sizeof(rip));
    if (r == NULL) return failure("failed", "out of memory");
    r->options = options;
    r->hooks = hooks;
    if (logger != NULL) r->logger = *logger;
    r->video_in = -1;
    char error[256] = "";
    char *report = NULL;
    int result;

    ifo_handle_t *vmg = ifoOpen(dvd, 0);
    if (vmg == NULL) {
        report = failure("unreadable", "Cannot read VIDEO_TS.IFO");
        goto done;
    }
    result = dr_plan_build(dvd, vmg, options->title, options->angle, &r->plan, error, sizeof(error));
    ifoClose(vmg);
    if (result < 0) {
        report = failure("no_title", error);
        goto done;
    }
    r->reader = dr_reader_open(dvd, &r->plan, &r->logger, is_cancelled, r, error, sizeof(error));
    if (r->reader == NULL) {
        report = failure("unreadable", error);
        goto done;
    }
    r->packet = av_packet_alloc();
    r->encoded = av_packet_alloc();
    if (r->packet == NULL || r->encoded == NULL) {
        report = failure("failed", "out of memory");
        goto done;
    }

    result = open_input(r);
    if (result < 0) {
        report = read_failure(r, result);
        goto done;
    }
    for (unsigned int i = 0; i < r->in->nb_streams; i++) {
        const AVStream *st = r->in->streams[i];
        enum AVCodecID id = st->codecpar->codec_id;
        if (r->video_in < 0 && st->codecpar->codec_type == AVMEDIA_TYPE_VIDEO &&
            (id == AV_CODEC_ID_MPEG2VIDEO || id == AV_CODEC_ID_MPEG1VIDEO)) {
            r->video_in = (int) i;
        }
    }
    if (r->video_in < 0) {
        report = failure("no_video", "No MPEG video found in the title");
        goto done;
    }

    for (int i = 0; i < options->audio_count && r->track_count < MAX_TRACKS; i++) {
        const dr_audio_choice *choice = &options->audio[i];
        const audio_attr_t *attr = audio_attr_for(r, choice->stream_id);
        if (attr == NULL || (attr->audio_format != 4 && copy_codec(attr) == AV_CODEC_ID_NONE)) {
            warn(r, "Audio stream 0x%X is not in this title; skipped", choice->stream_id);
            continue;
        }
        audio_track *track = &r->tracks[r->track_count++];
        track->choice = choice;
        track->attr = attr;
        track->in_index = -1;
        for (unsigned int s = 0; s < r->in->nb_streams; s++) {
            if (r->in->streams[s]->id == choice->stream_id) track->in_index = (int) s;
        }
    }
    for (unsigned int i = 0; i < r->in->nb_streams; i++) {
        if ((int) i != r->video_in && track_for_stream(r, (int) i) == NULL) r->in->streams[i]->discard = AVDISCARD_ALL;
    }

    result = open_output(r);
    if (result < 0) {
        snprintf(error, sizeof(error), "Preparing the Matroska file failed: %s", av_err2str(result));
        report = failure("failed", error);
        goto done;
    }
    result = avformat_write_header(r->out, NULL);
    if (result < 0) {
        report = r->write_errno != 0 ? write_failure(r) : failure("failed", av_err2str(result));
        goto done;
    }

    unsigned int known_streams = r->in->nb_streams;
    for (;;) {
        if (is_cancelled(r)) {
            report = failure("cancelled", "The rip was cancelled");
            goto done;
        }
        result = av_read_frame(r->in, r->packet);
        if (result == AVERROR_EOF) break;
        if (result < 0) {
            report = read_failure(r, result);
            goto done;
        }
        while (known_streams < r->in->nb_streams) adopt_new_stream(r, (int) known_streams++);

        int index = r->packet->stream_index;
        const AVStream *in = r->in->streams[index];
        if (index == r->video_in) {
            result = copy_packet(r, r->packet, in, r->video_out, &r->video_last_offset, &r->video_last_dts, 1);
        } else {
            audio_track *track = track_for_stream(r, index);
            if (track == NULL) {
                result = 0;
            } else if (track->transcode) {
                int64_t offset = offset_for(r, r->packet->pos, &track->last_offset);
                if (r->packet->pts != AV_NOPTS_VALUE) r->packet->pts += offset;
                if (r->packet->dts != AV_NOPTS_VALUE) r->packet->dts += offset;
                result = track->decoder != NULL ? transcode_packet(r, track, r->packet) : 0;
            } else {
                result = copy_packet(r, r->packet, in, track->out, &track->last_offset, &track->last_dts, 0);
            }
        }
        av_packet_unref(r->packet);
        if (result < 0) {
            report = r->write_errno != 0 ? write_failure(r) : failure("failed", av_err2str(result));
            goto done;
        }
        report_progress(r, 0);
    }

    for (int i = 0; i < r->track_count; i++) {
        audio_track *track = &r->tracks[i];
        if (track->in_index < 0) warn(r, "Audio stream 0x%X never appeared in the title", track->choice->stream_id);
        result = flush_track(r, track);
        if (result < 0) {
            report = failure("failed", av_err2str(result));
            goto done;
        }
    }
    if (r->clamped > 0) warn(r, "%d packets had timestamps that went back; they were held level", r->clamped);
    result = add_chapters(r);
    if (result >= 0) result = av_write_trailer(r->out);
    if (result < 0) {
        report = r->write_errno != 0 ? write_failure(r) : failure("failed", av_err2str(result));
        goto done;
    }
    avio_flush(r->out->pb);
    report_progress(r, 1);
    report = success(r);

done:
    close_rip(r);
    free(r);
    return report != NULL ? report : failure("failed", "out of memory");
}
