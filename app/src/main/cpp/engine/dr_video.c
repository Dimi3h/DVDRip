#include "dr_video.h"

#include <libavcodec/bsf.h>
#include <libavfilter/avfilter.h>
#include <libavfilter/buffersink.h>
#include <libavfilter/buffersrc.h>
#include <libavutil/opt.h>
#include <libavutil/pixdesc.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* Seconds between key frames: often enough to seek well, rare enough to cost little. */
#define KEY_FRAME_SECONDS 2

struct dr_video {
    dr_logger logger;
    AVCodecContext *decoder;
    AVCodecContext *encoder;
    /* Writes the aspect ratio into the parameter sets of H.264 and HEVC, which MediaCodec leaves
       out; NULL for encoders that write it themselves. */
    AVBSFContext *bitstream;
    /* Built for the first decoded frame and again when the frames change size or format. */
    AVFilterGraph *graph;
    AVFilterContext *source;
    AVFilterContext *sink;
    int graph_width;
    int graph_height;
    int graph_format;
    enum AVColorSpace graph_space;
    enum AVColorRange graph_range;
    AVRational input_time_base;
    AVFrame *decoded;
    AVFrame *filtered;
    AVPacket *encoded;
    AVPacket *rewritten;
    /* The output: the title's frame size, and the pixel shape that shows it at the disc's aspect. */
    int width;
    int height;
    AVRational sar;
    int64_t frames;
    int64_t interlaced;
    int64_t rescaled;
    int64_t last_pts;
};

/* Whether the encoder has an option of this name, as only MediaCodec's have codec_name. */
static int has_option(const AVCodec *codec, const char *name) {
    return codec->priv_class != NULL && av_opt_find((void *) &codec->priv_class, name, NULL, 0, AV_OPT_SEARCH_FAKE_OBJ) != NULL;
}

/* The pixel formats to try, best first: NV12 suits most hardware encoders, then planar 4:2:0. */
static int candidate_formats(const AVCodec *codec, enum AVPixelFormat *out, int capacity) {
    const enum AVPixelFormat *formats = NULL;
    int count = 0;
    if (avcodec_get_supported_config(NULL, codec, AV_CODEC_CONFIG_PIX_FORMAT, 0, (const void **) &formats, &count) < 0 || formats == NULL) {
        out[0] = AV_PIX_FMT_YUV420P;
        return 1;
    }
    int n = 0;
    static const enum AVPixelFormat preferred[] = {AV_PIX_FMT_NV12, AV_PIX_FMT_YUV420P};
    for (int p = 0; p < 2; p++) {
        for (int i = 0; i < count; i++) {
            if (formats[i] == preferred[p] && n < capacity) out[n++] = formats[i];
        }
    }
    for (int i = 0; i < count && n < capacity; i++) {
        const AVPixFmtDescriptor *descriptor = av_pix_fmt_desc_get(formats[i]);
        if (formats[i] == AV_PIX_FMT_NV12 || formats[i] == AV_PIX_FMT_YUV420P) continue;
        /* Hardware surfaces cannot take frames from a software decoder. */
        if (descriptor == NULL || (descriptor->flags & AV_PIX_FMT_FLAG_HWACCEL)) continue;
        out[n++] = formats[i];
    }
    return n;
}

/* The frame rate the encoder plans with: the measured average, which for soft-telecined film is
   the film's, else what the stream declares; 25 when neither makes sense. */
static AVRational frame_rate_of(const AVStream *in) {
    const AVRational candidates[] = {in->avg_frame_rate, in->r_frame_rate, in->codecpar->framerate};
    for (int i = 0; i < 3; i++) {
        AVRational rate = candidates[i];
        if (rate.num > 0 && rate.den > 0 && av_q2d(rate) >= 10 && av_q2d(rate) <= 120) return rate;
    }
    return (AVRational) {25, 1};
}

static int open_decoder(dr_video *video, const AVStream *in, char *error, size_t error_size) {
    const AVCodec *codec = avcodec_find_decoder(in->codecpar->codec_id);
    if (codec == NULL) {
        dr_set_error(error, error_size, "No decoder for the video");
        return AVERROR_DECODER_NOT_FOUND;
    }
    video->decoder = avcodec_alloc_context3(codec);
    if (video->decoder == NULL) return AVERROR(ENOMEM);
    int result = avcodec_parameters_to_context(video->decoder, in->codecpar);
    if (result >= 0) {
        video->decoder->pkt_timebase = in->time_base;
        /* MPEG-2 decodes slices in parallel: one thread per core. */
        video->decoder->thread_count = 0;
        result = avcodec_open2(video->decoder, codec, NULL);
    }
    if (result < 0) dr_set_error(error, error_size, "The video decoder could not start: %s", av_err2str(result));
    return result;
}

/* Opens the encoder with the first pixel format it accepts; MediaCodec says only by trying. */
static int open_encoder(dr_video *video, const AVStream *in, const dr_video_config *config, int global_header,
                        char *error, size_t error_size) {
    const AVCodec *codec = avcodec_find_encoder_by_name(config->encoder);
    if (codec == NULL) {
        dr_set_error(error, error_size, "This build has no encoder %s", config->encoder);
        return AVERROR_ENCODER_NOT_FOUND;
    }
    AVRational rate = frame_rate_of(in);
    enum AVPixelFormat formats[16];
    int format_count = candidate_formats(codec, formats, 16);
    int result = AVERROR_ENCODER_NOT_FOUND;
    for (int i = 0; i < format_count; i++) {
        AVCodecContext *encoder = avcodec_alloc_context3(codec);
        if (encoder == NULL) return AVERROR(ENOMEM);
        encoder->width = video->width;
        encoder->height = video->height;
        encoder->pix_fmt = formats[i];
        encoder->sample_aspect_ratio = video->sar;
        /* One tick per frame, as ffmpeg sets it: FFmpeg's own encoders, the host tests' MPEG-4
           among them, count frames in it to spread the bit rate. Timing that varies, such as
           soft-telecined film, is rounded to the nearest frame. */
        encoder->time_base = av_inv_q(rate);
        encoder->framerate = rate;
        encoder->bit_rate = config->bit_rate;
        encoder->gop_size = (int) (av_q2d(rate) * KEY_FRAME_SECONDS + 0.5);
        /* MediaCodec gives B-frames no DTS of their own; without them PTS serve as DTS. */
        encoder->max_b_frames = 0;
        encoder->color_range = video->decoder->color_range;
        encoder->color_primaries = video->decoder->color_primaries;
        encoder->color_trc = video->decoder->color_trc;
        encoder->colorspace = video->decoder->colorspace;
        if (global_header) encoder->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
        AVDictionary *options = NULL;
        if (config->bitrate_mode != NULL && config->bitrate_mode[0] && has_option(codec, "bitrate_mode")) {
            av_dict_set(&options, "bitrate_mode", config->bitrate_mode, 0);
        }
        if (config->component != NULL && config->component[0] && has_option(codec, "codec_name")) {
            av_dict_set(&options, "codec_name", config->component, 0);
        }
        result = avcodec_open2(encoder, codec, &options);
        av_dict_free(&options);
        if (result >= 0) {
            video->encoder = encoder;
            dr_logf(&video->logger, DR_LOG_INFO, "Encoding %dx%d %s at %d/%d fps with %s%s%s at %lld kbit/s", video->width,
                    video->height, av_get_pix_fmt_name(formats[i]), rate.num, rate.den, config->encoder,
                    config->component != NULL ? " " : "", config->component != NULL ? config->component : "",
                    (long long) (config->bit_rate / 1000));
            return 0;
        }
        dr_logf(&video->logger, DR_LOG_WARN, "%s does not start with %s: %s", config->encoder, av_get_pix_fmt_name(formats[i]),
                av_err2str(result));
        avcodec_free_context(&encoder);
    }
    dr_set_error(error, error_size, "The encoder %s could not start: %s", config->encoder, av_err2str(result));
    return result;
}

/* hevc_metadata or h264_metadata, set to write the sample aspect ratio into every SPS. */
static int open_bitstream_filter(dr_video *video, char *error, size_t error_size) {
    const char *name = video->encoder->codec_id == AV_CODEC_ID_HEVC ? "hevc_metadata"
                       : video->encoder->codec_id == AV_CODEC_ID_H264 ? "h264_metadata" : NULL;
    if (name == NULL) return 0;
    const AVBitStreamFilter *filter = av_bsf_get_by_name(name);
    if (filter == NULL) {
        dr_set_error(error, error_size, "This build has no %s filter", name);
        return AVERROR_BSF_NOT_FOUND;
    }
    int result = av_bsf_alloc(filter, &video->bitstream);
    if (result < 0) return result;
    result = avcodec_parameters_from_context(video->bitstream->par_in, video->encoder);
    if (result >= 0) {
        video->bitstream->time_base_in = video->encoder->time_base;
        result = av_opt_set_q(video->bitstream->priv_data, "sample_aspect_ratio", video->sar, 0);
    }
    if (result >= 0) result = av_bsf_init(video->bitstream);
    if (result < 0) dr_set_error(error, error_size, "The %s filter could not start: %s", name, av_err2str(result));
    return result;
}

int dr_video_open(dr_video **out, const AVStream *in, const dr_video_config *config, int global_header,
                  const dr_logger *logger, char *error, size_t error_size) {
    *out = NULL;
    dr_video *video = calloc(1, sizeof(dr_video));
    if (video == NULL) return AVERROR(ENOMEM);
    if (logger != NULL) video->logger = *logger;
    video->width = in->codecpar->width;
    video->height = in->codecpar->height;
    video->input_time_base = in->time_base;
    video->last_pts = AV_NOPTS_VALUE;
    if (video->width <= 0 || video->height <= 0) {
        dr_set_error(error, error_size, "The video's size is unknown");
        free(video);
        return AVERROR_INVALIDDATA;
    }
    /* The pixel shape that shows the full frame at the disc's aspect: 64:45 for 720x576 at 16:9. */
    if (config->display_aspect.num > 0 && config->display_aspect.den > 0) {
        av_reduce(&video->sar.num, &video->sar.den, (int64_t) config->display_aspect.num * video->height,
                  (int64_t) config->display_aspect.den * video->width, INT_MAX);
    } else {
        video->sar = in->codecpar->sample_aspect_ratio.num > 0 ? in->codecpar->sample_aspect_ratio : (AVRational) {1, 1};
    }
    video->decoded = av_frame_alloc();
    video->filtered = av_frame_alloc();
    video->encoded = av_packet_alloc();
    video->rewritten = av_packet_alloc();
    int result = video->decoded != NULL && video->filtered != NULL && video->encoded != NULL && video->rewritten != NULL
                 ? 0 : AVERROR(ENOMEM);
    if (result >= 0) result = open_decoder(video, in, error, error_size);
    if (result >= 0) result = open_encoder(video, in, config, global_header, error, error_size);
    if (result >= 0) result = open_bitstream_filter(video, error, error_size);
    if (result < 0) {
        dr_video_close(video);
        return result;
    }
    *out = video;
    return 0;
}

int dr_video_describe(const dr_video *video, AVStream *out) {
    int result = video->bitstream != NULL ? avcodec_parameters_copy(out->codecpar, video->bitstream->par_out)
                                          : avcodec_parameters_from_context(out->codecpar, video->encoder);
    if (result < 0) return result;
    out->codecpar->sample_aspect_ratio = video->sar;
    out->time_base = video->encoder->time_base;
    out->sample_aspect_ratio = video->sar;
    out->avg_frame_rate = video->encoder->framerate;
    return 0;
}

/* An encoded packet, through the bitstream filter if there is one, to emit; NULL ends the stream. */
static int deliver(dr_video *video, AVPacket *packet, dr_video_emit emit, void *opaque) {
    if (video->bitstream == NULL) {
        if (packet == NULL) return 0;
        int result = emit(opaque, packet);
        av_packet_unref(packet);
        return result;
    }
    int result = av_bsf_send_packet(video->bitstream, packet);
    if (result < 0) return result;
    for (;;) {
        result = av_bsf_receive_packet(video->bitstream, video->rewritten);
        if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) return 0;
        if (result < 0) return result;
        result = emit(opaque, video->rewritten);
        av_packet_unref(video->rewritten);
        if (result < 0) return result;
    }
}

static int drain_encoder(dr_video *video, dr_video_emit emit, void *opaque) {
    for (;;) {
        int result = avcodec_receive_packet(video->encoder, video->encoded);
        if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) return 0;
        if (result < 0) return result;
        result = deliver(video, video->encoded, emit, opaque);
        av_packet_unref(video->encoded);
        if (result < 0) return result;
    }
}

/* Sends a frame, or NULL at the end, and passes on what the encoder has ready. */
static int encode(dr_video *video, AVFrame *frame, dr_video_emit emit, void *opaque) {
    for (;;) {
        int result = avcodec_send_frame(video->encoder, frame);
        if (result == AVERROR(EAGAIN)) {
            /* The encoder wants its output taken first. */
            result = drain_encoder(video, emit, opaque);
            if (result < 0) return result;
            continue;
        }
        if (result < 0 && !(frame == NULL && result == AVERROR_EOF)) return result;
        if (frame != NULL) video->frames++;
        return drain_encoder(video, emit, opaque);
    }
}

static int drain_filters(dr_video *video, dr_video_emit emit, void *opaque) {
    for (;;) {
        int result = av_buffersink_get_frame(video->sink, video->filtered);
        if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) return 0;
        if (result < 0) return result;
        AVFrame *frame = video->filtered;
        frame->pts = av_rescale_q(frame->pts, av_buffersink_get_time_base(video->sink), video->encoder->time_base);
        /* Rounding to frame ticks must not put two frames at one time. */
        if (video->last_pts != AV_NOPTS_VALUE && frame->pts <= video->last_pts) frame->pts = video->last_pts + 1;
        video->last_pts = frame->pts;
        /* The source's I-frames are no reason for key frames here. */
        frame->pict_type = AV_PICTURE_TYPE_NONE;
        result = encode(video, frame, emit, opaque);
        av_frame_unref(frame);
        if (result < 0) return result;
    }
}

/* Ends the current filter graph, passing on the frame yadif still holds. */
static int close_graph(dr_video *video, dr_video_emit emit, void *opaque) {
    if (video->graph == NULL) return 0;
    int result = av_buffersrc_add_frame_flags(video->source, NULL, 0);
    if (result >= 0) result = drain_filters(video, emit, opaque);
    avfilter_graph_free(&video->graph);
    video->source = NULL;
    video->sink = NULL;
    return result;
}

/*
 * buffer -> yadif (frames flagged interlaced only) -> scale (to the output size, which leaves frames
 * of that size as they are) -> setsar -> format (the encoder's) -> buffersink, for frames like this.
 */
static int open_graph(dr_video *video, const AVFrame *frame) {
    video->graph = avfilter_graph_alloc();
    if (video->graph == NULL) return AVERROR(ENOMEM);
    video->source = avfilter_graph_alloc_filter(video->graph, avfilter_get_by_name("buffer"), "in");
    AVBufferSrcParameters *parameters = av_buffersrc_parameters_alloc();
    if (video->source == NULL || parameters == NULL) {
        av_free(parameters);
        return AVERROR(ENOMEM);
    }
    parameters->format = frame->format;
    parameters->time_base = video->input_time_base;
    parameters->width = frame->width;
    parameters->height = frame->height;
    parameters->sample_aspect_ratio = frame->sample_aspect_ratio;
    parameters->color_space = frame->colorspace;
    parameters->color_range = frame->color_range;
    int result = av_buffersrc_parameters_set(video->source, parameters);
    av_free(parameters);
    if (result >= 0) result = avfilter_init_str(video->source, NULL);
    if (result >= 0) {
        result = avfilter_graph_create_filter(&video->sink, avfilter_get_by_name("buffersink"), "out", NULL, NULL, video->graph);
    }
    if (result < 0) return result;
    char description[256];
    snprintf(description, sizeof(description),
             "yadif=mode=send_frame:parity=auto:deint=interlaced,scale=w=%d:h=%d,setsar=sar=%d/%d,format=pix_fmts=%s",
             video->width, video->height, video->sar.num, video->sar.den, av_get_pix_fmt_name(video->encoder->pix_fmt));
    AVFilterInOut *outputs = avfilter_inout_alloc();
    AVFilterInOut *inputs = avfilter_inout_alloc();
    if (outputs == NULL || inputs == NULL) {
        avfilter_inout_free(&outputs);
        avfilter_inout_free(&inputs);
        return AVERROR(ENOMEM);
    }
    outputs->name = av_strdup("in");
    outputs->filter_ctx = video->source;
    inputs->name = av_strdup("out");
    inputs->filter_ctx = video->sink;
    result = avfilter_graph_parse_ptr(video->graph, description, &inputs, &outputs, NULL);
    avfilter_inout_free(&outputs);
    avfilter_inout_free(&inputs);
    if (result >= 0) result = avfilter_graph_config(video->graph, NULL);
    if (result < 0) return result;
    video->graph_width = frame->width;
    video->graph_height = frame->height;
    video->graph_format = frame->format;
    video->graph_space = frame->colorspace;
    video->graph_range = frame->color_range;
    return 0;
}

static int filter(dr_video *video, AVFrame *frame, dr_video_emit emit, void *opaque) {
    int64_t pts = frame->best_effort_timestamp;
    /* Before the title's first VOBU, or B-frames of an open GOP: the copy drops these too. */
    if (pts == AV_NOPTS_VALUE || pts < 0) return 0;
    frame->pts = pts;
    int changed = video->graph == NULL || frame->width != video->graph_width || frame->height != video->graph_height ||
                  frame->format != video->graph_format || frame->colorspace != video->graph_space ||
                  frame->color_range != video->graph_range;
    if (changed) {
        if (video->graph != NULL) {
            dr_logf(&video->logger, DR_LOG_INFO, "The video changes from %dx%d %s %s %s to %dx%d %s %s %s", video->graph_width,
                    video->graph_height, av_get_pix_fmt_name(video->graph_format), av_color_space_name(video->graph_space),
                    av_color_range_name(video->graph_range), frame->width, frame->height, av_get_pix_fmt_name(frame->format),
                    av_color_space_name(frame->colorspace), av_color_range_name(frame->color_range));
        }
        int result = close_graph(video, emit, opaque);
        if (result >= 0) result = open_graph(video, frame);
        if (result < 0) {
            dr_logf(&video->logger, DR_LOG_ERROR, "The video filters could not be set up: %s", av_err2str(result));
            return result;
        }
    }
    if (frame->width != video->width || frame->height != video->height) video->rescaled++;
    if (frame->flags & AV_FRAME_FLAG_INTERLACED) video->interlaced++;
    int result = av_buffersrc_add_frame_flags(video->source, frame, AV_BUFFERSRC_FLAG_KEEP_REF);
    if (result < 0) return result;
    return drain_filters(video, emit, opaque);
}

static int receive_frames(dr_video *video, dr_video_emit emit, void *opaque) {
    for (;;) {
        int result = avcodec_receive_frame(video->decoder, video->decoded);
        if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) return 0;
        if (result < 0) {
            /* Damaged video: that frame is lost, the next may be fine. */
            dr_logf(&video->logger, DR_LOG_WARN, "Video frame not decoded: %s", av_err2str(result));
            continue;
        }
        result = filter(video, video->decoded, emit, opaque);
        av_frame_unref(video->decoded);
        if (result < 0) return result;
    }
}

int dr_video_send(dr_video *video, AVPacket *packet, dr_video_emit emit, void *opaque) {
    int result = avcodec_send_packet(video->decoder, packet);
    if (result < 0 && result != AVERROR_EOF) {
        dr_logf(&video->logger, DR_LOG_WARN, "Video packet rejected: %s", av_err2str(result));
    }
    return receive_frames(video, emit, opaque);
}

int dr_video_flush(dr_video *video, dr_video_emit emit, void *opaque) {
    int result = dr_video_send(video, NULL, emit, opaque);
    if (result >= 0) result = close_graph(video, emit, opaque);
    if (result >= 0) result = encode(video, NULL, emit, opaque);
    if (result >= 0) result = deliver(video, NULL, emit, opaque);
    if (video->rescaled > 0) {
        dr_logf(&video->logger, DR_LOG_INFO, "%lld frames of another size were scaled to %dx%d", (long long) video->rescaled,
                video->width, video->height);
    }
    return result;
}

int64_t dr_video_frames_encoded(const dr_video *video) {
    return video != NULL ? video->frames : 0;
}

int64_t dr_video_interlaced_frames(const dr_video *video) {
    return video != NULL ? video->interlaced : 0;
}

void dr_video_close(dr_video *video) {
    if (video == NULL) return;
    avfilter_graph_free(&video->graph);
    av_bsf_free(&video->bitstream);
    avcodec_free_context(&video->decoder);
    avcodec_free_context(&video->encoder);
    av_frame_free(&video->decoded);
    av_frame_free(&video->filtered);
    av_packet_free(&video->encoded);
    av_packet_free(&video->rewritten);
    free(video);
}
