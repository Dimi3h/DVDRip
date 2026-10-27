#include "dr_probe.h"

#include "dr_json.h"

#include <errno.h>
#include <libavformat/avformat.h>
#include <stdio.h>

#define BUFFER_SIZE (64 * 1024)

typedef struct input {
    const dr_file *file;
    int64_t position;
} input;

static int read_input(void *opaque, uint8_t *buffer, int size) {
    input *in = opaque;
    int got = in->file->read(in->file->opaque, buffer, size, in->position);
    if (got < 0) return got;
    if (got == 0) return AVERROR_EOF;
    in->position += got;
    return got;
}

static int64_t seek_input(void *opaque, int64_t offset, int whence) {
    input *in = opaque;
    if (whence == AVSEEK_SIZE) return in->file->size(in->file->opaque);
    int64_t base;
    switch (whence & ~AVSEEK_FORCE) {
        case SEEK_SET:
            base = 0;
            break;
        case SEEK_CUR:
            base = in->position;
            break;
        case SEEK_END:
            base = in->file->size(in->file->opaque);
            if (base < 0) return base;
            break;
        default:
            return AVERROR(EINVAL);
    }
    if (base + offset < 0) return AVERROR(EINVAL);
    in->position = base + offset;
    return in->position;
}

static char *failed(const char *message) {
    dr_json json;
    dr_json_init(&json);
    dr_json_begin_object(&json);
    dr_json_key(&json, "ok"); dr_json_bool(&json, 0);
    dr_json_key(&json, "message"); dr_json_string(&json, message);
    dr_json_end_object(&json);
    return dr_json_take(&json);
}

char *dr_probe(const dr_file *file) {
    input in = {file, 0};
    AVFormatContext *format = avformat_alloc_context();
    uint8_t *buffer = av_malloc(BUFFER_SIZE);
    AVIOContext *io = buffer != NULL ? avio_alloc_context(buffer, BUFFER_SIZE, 0, &in, read_input, NULL, seek_input) : NULL;
    if (format == NULL || io == NULL) {
        if (io == NULL) av_free(buffer);
        avio_context_free(&io);
        avformat_free_context(format);
        return failed("out of memory");
    }
    format->pb = io;
    format->flags |= AVFMT_FLAG_CUSTOM_IO;
    char *result;
    int status = avformat_open_input(&format, NULL, av_find_input_format("matroska"), NULL);
    if (status >= 0) status = avformat_find_stream_info(format, NULL);
    if (status < 0) {
        result = failed(av_err2str(status));
    } else {
        dr_json json;
        dr_json_init(&json);
        dr_json_begin_object(&json);
        dr_json_key(&json, "ok"); dr_json_bool(&json, 1);
        dr_json_key(&json, "durationMs");
        dr_json_int(&json, format->duration == AV_NOPTS_VALUE ? 0 : format->duration / 1000);
        dr_json_key(&json, "streams");
        dr_json_begin_array(&json);
        for (unsigned int i = 0; i < format->nb_streams; i++) {
            const AVStream *st = format->streams[i];
            const AVCodecParameters *par = st->codecpar;
            const AVDictionaryEntry *language = av_dict_get(st->metadata, "language", NULL, 0);
            dr_json_begin_object(&json);
            dr_json_key(&json, "type"); dr_json_string(&json, av_get_media_type_string(par->codec_type));
            dr_json_key(&json, "codec"); dr_json_string(&json, avcodec_get_name(par->codec_id));
            dr_json_key(&json, "language"); dr_json_string(&json, language != NULL ? language->value : NULL);
            if (par->codec_type == AVMEDIA_TYPE_VIDEO) {
                dr_json_key(&json, "width"); dr_json_int(&json, par->width);
                dr_json_key(&json, "height"); dr_json_int(&json, par->height);
                /* As players show it: the frame stretched by the pixel shape Matroska records. */
                AVRational sar = st->sample_aspect_ratio.num > 0 ? st->sample_aspect_ratio : par->sample_aspect_ratio;
                if (sar.num <= 0 || sar.den <= 0) sar = (AVRational) {1, 1};
                if (par->width > 0 && par->height > 0) {
                    AVRational dar;
                    av_reduce(&dar.num, &dar.den, (int64_t) par->width * sar.num, (int64_t) par->height * sar.den, 1 << 20);
                    char aspect[32];
                    snprintf(aspect, sizeof(aspect), "%d:%d", dar.num, dar.den);
                    dr_json_key(&json, "displayAspect"); dr_json_string(&json, aspect);
                }
            } else if (par->codec_type == AVMEDIA_TYPE_AUDIO) {
                dr_json_key(&json, "channels"); dr_json_int(&json, par->ch_layout.nb_channels);
                dr_json_key(&json, "sampleRate"); dr_json_int(&json, par->sample_rate);
            }
            dr_json_end_object(&json);
        }
        dr_json_end_array(&json);
        dr_json_key(&json, "chapterStartsMs");
        dr_json_begin_array(&json);
        for (unsigned int i = 0; i < format->nb_chapters; i++) {
            const AVChapter *chapter = format->chapters[i];
            dr_json_int(&json, av_rescale_q(chapter->start, chapter->time_base, (AVRational) {1, 1000}));
        }
        dr_json_end_array(&json);
        dr_json_key(&json, "tags");
        dr_json_begin_object(&json);
        const AVDictionaryEntry *tag = NULL;
        while ((tag = av_dict_iterate(format->metadata, tag)) != NULL) {
            dr_json_key(&json, tag->key);
            dr_json_string(&json, tag->value);
        }
        dr_json_end_object(&json);
        dr_json_end_object(&json);
        result = dr_json_take(&json);
    }
    avformat_close_input(&format);
    av_freep(&io->buffer);
    avio_context_free(&io);
    return result != NULL ? result : failed("out of memory");
}
