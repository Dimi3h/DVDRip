#include "dr_probe.h"

#include "dr_json.h"

#include <errno.h>
#include <libavformat/avformat.h>
#include <unistd.h>

#define BUFFER_SIZE (64 * 1024)

static int read_fd(void *opaque, uint8_t *buffer, int size) {
    int fd = *(int *) opaque;
    ssize_t got;
    do {
        got = read(fd, buffer, (size_t) size);
    } while (got < 0 && errno == EINTR);
    if (got < 0) return AVERROR(errno);
    return got == 0 ? AVERROR_EOF : (int) got;
}

static int64_t seek_fd(void *opaque, int64_t offset, int whence) {
    int fd = *(int *) opaque;
    if (whence == AVSEEK_SIZE) {
        off_t current = lseek(fd, 0, SEEK_CUR);
        off_t end = lseek(fd, 0, SEEK_END);
        lseek(fd, current, SEEK_SET);
        return end;
    }
    off_t position = lseek(fd, (off_t) offset, whence & ~AVSEEK_FORCE);
    return position < 0 ? AVERROR(errno) : (int64_t) position;
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

char *dr_probe(int fd) {
    int local_fd = fd;
    if (lseek(local_fd, 0, SEEK_SET) < 0) return failed("The file cannot be read from its start");
    AVFormatContext *format = avformat_alloc_context();
    uint8_t *buffer = av_malloc(BUFFER_SIZE);
    AVIOContext *io = buffer != NULL ? avio_alloc_context(buffer, BUFFER_SIZE, 0, &local_fd, read_fd, NULL, seek_fd) : NULL;
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
