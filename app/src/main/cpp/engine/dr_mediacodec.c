#include "dr_mediacodec.h"

#include "dr_order.h"

#include <libavutil/error.h>
#include <libavutil/time.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <stdlib.h>
#include <string.h>

/* One wait on the codec; between waits cancelling is looked at. */
#define WAIT_US 10000

/* A codec that takes no frame and gives nothing back for this long is stuck. */
#define STALL_US (30 * 1000000LL)

/* The frames a second a codec plans its resources for, which is otherwise the film's frame rate:
   far beyond real time, as Android's own transcoder asks at 720p (DVD frames are smaller). */
#define OPERATING_RATE 480

/* MediaFormat values for DVD video: limited range, BT.601, standard dynamic range. */
#define COLOR_RANGE_LIMITED 2
#define COLOR_STANDARD_BT601_PAL 2
#define COLOR_STANDARD_BT601_NTSC 4
#define COLOR_TRANSFER_SDR_VIDEO 3

/* What to do with the buffers a codec gives back. */
typedef int (*output_handler)(void *opaque, const uint8_t *data, size_t size, int64_t pts_us, uint32_t flags);

/* A started codec and the layout its input buffers want. */
typedef struct instance {
    AMediaCodec *codec;
    int stride;
    int slice_height;
} instance;

struct dr_mediacodec {
    dr_logger logger;
    dr_mediacodec_config config;
    char *component;
    char *mime;
    instance encoder;
    /* What start asks of the codec; 0 once it would not start with it. */
    int operating_rate;
    int hevc;
    uint8_t *parameter_sets;
    size_t parameter_sets_size;
    /* The parameter sets the encoder gives on its own, put before its first frame. */
    uint8_t *pending;
    size_t pending_size;
    uint8_t *packet;
    size_t packet_capacity;
    /* The frames in the encoder, for their decoding times. */
    dr_order order;
    dr_mediacodec_emit emit;
    void *emit_opaque;
};

static int cancelled(const dr_mediacodec *codec) {
    return codec->config.cancelled != NULL && codec->config.cancelled(codec->config.cancel_opaque);
}

static int append(uint8_t **buffer, size_t *size, const uint8_t *data, size_t length) {
    uint8_t *grown = realloc(*buffer, *size + length);
    if (grown == NULL) return AVERROR(ENOMEM);
    memcpy(grown + *size, data, length);
    *buffer = grown;
    *size += length;
    return 0;
}

/* Whether a NAL unit header starts a parameter set: SPS and PPS, and for HEVC the VPS. */
static int is_parameter_set(int hevc, uint8_t header) {
    if (hevc) {
        int type = (header >> 1) & 0x3F;
        return type >= 32 && type <= 34;
    }
    int type = header & 0x1F;
    return type == 7 || type == 8;
}

/* Copies the parameter sets out of an Annex B frame, for codecs that send them in it. */
static int collect_parameter_sets(dr_mediacodec *codec, const uint8_t *data, size_t size) {
    static const uint8_t start_code[4] = {0, 0, 0, 1};
    size_t i = 0;
    while (i + 3 <= size) {
        if (!(data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1)) {
            i++;
            continue;
        }
        size_t begin = i + 3;
        size_t end = begin;
        while (end + 3 <= size && !(data[end] == 0 && data[end + 1] == 0 && (data[end + 2] == 1 ||
               (data[end + 2] == 0 && end + 3 < size && data[end + 3] == 1)))) {
            end++;
        }
        if (end + 3 > size) end = size;
        if (begin < end && is_parameter_set(codec->hevc, data[begin])) {
            int result = append(&codec->parameter_sets, &codec->parameter_sets_size, start_code, sizeof(start_code));
            if (result >= 0) result = append(&codec->parameter_sets, &codec->parameter_sets_size, data + begin, end - begin);
            if (result < 0) return result;
        }
        i = end;
    }
    return 0;
}

static AMediaFormat *new_format(const dr_mediacodec_config *config, int operating_rate) {
    AMediaFormat *format = AMediaFormat_new();
    if (format == NULL) return NULL;
    AMediaFormat_setString(format, "mime", config->mime);
    AMediaFormat_setInt32(format, "width", config->width);
    AMediaFormat_setInt32(format, "height", config->height);
    AMediaFormat_setInt32(format, "color-format", config->color_format);
    AMediaFormat_setInt32(format, "bitrate", (int32_t) config->bit_rate);
    if (config->bitrate_mode >= 0) AMediaFormat_setInt32(format, "bitrate-mode", config->bitrate_mode);
    AMediaFormat_setInt32(format, "frame-rate", config->frame_rate);
    AMediaFormat_setInt32(format, "i-frame-interval", config->key_frame_seconds);
    /* No B-frames, which not every encoder makes well nor every player handles; dr_order copes with
       an encoder that makes them anyway. */
    AMediaFormat_setInt32(format, "max-bframes", 0);
    /* Not a call but a transcode: best effort, as fast as the codec can. */
    AMediaFormat_setInt32(format, "priority", 1);
    if (operating_rate > 0) AMediaFormat_setInt32(format, "operating-rate", operating_rate);
    AMediaFormat_setInt32(format, "color-range", COLOR_RANGE_LIMITED);
    AMediaFormat_setInt32(format, "color-standard", config->pal ? COLOR_STANDARD_BT601_PAL : COLOR_STANDARD_BT601_NTSC);
    AMediaFormat_setInt32(format, "color-transfer", COLOR_TRANSFER_SDR_VIDEO);
    return format;
}

static void stop(instance *in) {
    if (in->codec == NULL) return;
    AMediaCodec_stop(in->codec);
    AMediaCodec_delete(in->codec);
    in->codec = NULL;
}

/*
 * Creates, configures and starts the component, and reads the stride and slice height it wants. A
 * component that will not start at the operating rate starts without, and *operating_rate becomes
 * 0 for the next instance.
 */
static int start(const dr_mediacodec_config *config, int *operating_rate, instance *in, const dr_logger *logger,
                 char *error, size_t error_size) {
    for (;;) {
        in->codec = AMediaCodec_createCodecByName(config->component);
        if (in->codec == NULL) {
            dr_set_error(error, error_size, "MediaCodec cannot create %s", config->component);
            return AVERROR_ENCODER_NOT_FOUND;
        }
        AMediaFormat *format = new_format(config, *operating_rate);
        if (format == NULL) {
            stop(in);
            return AVERROR(ENOMEM);
        }
        media_status_t status = AMediaCodec_configure(in->codec, format, NULL, NULL, AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
        AMediaFormat_delete(format);
        if (status == AMEDIA_OK) status = AMediaCodec_start(in->codec);
        if (status == AMEDIA_OK) break;
        AMediaCodec_delete(in->codec);
        in->codec = NULL;
        if (*operating_rate == 0) {
            dr_set_error(error, error_size, "%s will not start (MediaCodec error %d)", config->component, (int) status);
            return AVERROR_EXTERNAL;
        }
        dr_logf(logger, DR_LOG_WARN, "%s will not start at %d frames a second (MediaCodec error %d); trying without",
                config->component, *operating_rate, (int) status);
        *operating_rate = 0;
    }
    in->stride = config->width;
    in->slice_height = config->height;
    AMediaFormat *input = AMediaCodec_getInputFormat(in->codec);
    if (input != NULL) {
        int32_t value;
        if (AMediaFormat_getInt32(input, "stride", &value) && value >= config->width) in->stride = value;
        if (AMediaFormat_getInt32(input, "slice-height", &value) && value >= config->height) in->slice_height = value;
        AMediaFormat_delete(input);
    }
    return 0;
}

/*
 * Writes a frame into an input buffer as the codec lays it out: the luma plane at its stride, the
 * chroma below it at the slice height; NV12 interleaves the chroma, planar 4:2:0 puts U before V
 * at half the stride. Returns the bytes to queue.
 */
static int copy_frame(const dr_mediacodec_config *config, const instance *in, const AVFrame *frame, uint8_t *buffer, size_t capacity) {
    int width = config->width;
    int height = config->height;
    size_t stride = (size_t) in->stride;
    size_t chroma = stride * (size_t) in->slice_height;
    size_t needed;
    size_t whole;
    if (config->color_format == DR_COLOR_FORMAT_NV12) {
        needed = chroma + stride * (size_t) (height / 2 - 1) + (size_t) width;
        whole = chroma + stride * (size_t) (in->slice_height / 2);
    } else {
        size_t half = stride / 2;
        size_t v = chroma + half * (size_t) (in->slice_height / 2);
        needed = v + half * (size_t) (height / 2 - 1) + (size_t) (width / 2);
        whole = v + half * (size_t) (in->slice_height / 2);
    }
    if (frame->width != width || frame->height != height || capacity < needed) return AVERROR(EINVAL);
    for (int y = 0; y < height; y++) memcpy(buffer + stride * (size_t) y, frame->data[0] + frame->linesize[0] * y, (size_t) width);
    if (config->color_format == DR_COLOR_FORMAT_NV12) {
        for (int y = 0; y < height / 2; y++) {
            memcpy(buffer + chroma + stride * (size_t) y, frame->data[1] + frame->linesize[1] * y, (size_t) width);
        }
    } else {
        size_t half = stride / 2;
        size_t v = chroma + half * (size_t) (in->slice_height / 2);
        for (int y = 0; y < height / 2; y++) {
            memcpy(buffer + chroma + half * (size_t) y, frame->data[1] + frame->linesize[1] * y, (size_t) (width / 2));
            memcpy(buffer + v + half * (size_t) y, frame->data[2] + frame->linesize[2] * y, (size_t) (width / 2));
        }
    }
    return (int) (whole <= capacity ? whole : needed);
}

/* A MediaCodec call that failed, for the log; the rip reports it as the encoder failing. */
static int codec_error(const dr_mediacodec *codec, const char *call, long status) {
    dr_logf(&codec->logger, DR_LOG_ERROR, "%s: %s failed with MediaCodec status %ld", codec->component, call, status);
    return AVERROR_EXTERNAL;
}

/*
 * Hands the outputs the codec has ready to handle, waiting up to timeout_us for the first. Returns
 * how many there were, or an error; *ended is set once the end of the stream comes out.
 */
static int drain(const dr_mediacodec *codec, instance *in, int64_t timeout_us, int *ended, output_handler handle, void *opaque) {
    int taken = 0;
    for (;;) {
        AMediaCodecBufferInfo info;
        ssize_t index = AMediaCodec_dequeueOutputBuffer(in->codec, &info, taken == 0 ? timeout_us : 0);
        if (index == AMEDIACODEC_INFO_TRY_AGAIN_LATER) return taken;
        if (index == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED || index == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) continue;
        if (index < 0) return codec_error(codec, "dequeueOutputBuffer", (long) index);
        taken++;
        size_t ignored;
        uint8_t *buffer = AMediaCodec_getOutputBuffer(in->codec, (size_t) index, &ignored);
        int result = 0;
        if (info.size > 0) {
            result = buffer != NULL ? handle(opaque, buffer + info.offset, (size_t) info.size, info.presentationTimeUs, info.flags)
                                    : codec_error(codec, "getOutputBuffer", 0);
        }
        AMediaCodec_releaseOutputBuffer(in->codec, (size_t) index, false);
        if (result < 0) return result;
        if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) {
            *ended = 1;
            return taken;
        }
    }
}

/* Queues a frame, or the end of the stream for NULL, taking outputs while the codec has no room. */
static int queue(dr_mediacodec *codec, instance *in, const AVFrame *frame, int64_t pts_us, output_handler handle, void *opaque) {
    int64_t deadline = av_gettime_relative() + STALL_US;
    for (;;) {
        if (cancelled(codec)) return AVERROR_EXIT;
        int ended = 0;
        int taken = drain(codec, in, 0, &ended, handle, opaque);
        if (taken < 0) return taken;
        if (ended) return AVERROR_EOF;
        if (taken > 0) deadline = av_gettime_relative() + STALL_US;
        ssize_t index = AMediaCodec_dequeueInputBuffer(in->codec, WAIT_US);
        if (index >= 0) {
            size_t capacity = 0;
            uint8_t *buffer = AMediaCodec_getInputBuffer(in->codec, (size_t) index, &capacity);
            if (buffer == NULL) return codec_error(codec, "getInputBuffer", 0);
            int size = 0;
            if (frame != NULL) {
                size = copy_frame(&codec->config, in, frame, buffer, capacity);
                if (size < 0) {
                    dr_logf(&codec->logger, DR_LOG_ERROR, "A %dx%d frame does not fit an input buffer of %zu bytes at stride %d",
                            frame->width, frame->height, capacity, in->stride);
                    return size;
                }
            }
            uint32_t flags = frame != NULL ? 0 : AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM;
            media_status_t status = AMediaCodec_queueInputBuffer(in->codec, (size_t) index, 0, (size_t) size, (uint64_t) pts_us, flags);
            return status == AMEDIA_OK ? 0 : codec_error(codec, "queueInputBuffer", (long) status);
        }
        if (index != AMEDIACODEC_INFO_TRY_AGAIN_LATER) return codec_error(codec, "dequeueInputBuffer", (long) index);
        if (av_gettime_relative() > deadline) {
            dr_logf(&codec->logger, DR_LOG_ERROR, "%s took no frame for %d s", codec->component, (int) (STALL_US / 1000000));
            return AVERROR(ETIMEDOUT);
        }
    }
}

/* Takes outputs until the end of the stream comes out. */
static int drain_to_end(dr_mediacodec *codec, instance *in, output_handler handle, void *opaque) {
    int64_t deadline = av_gettime_relative() + STALL_US;
    for (;;) {
        if (cancelled(codec)) return AVERROR_EXIT;
        int ended = 0;
        int taken = drain(codec, in, WAIT_US, &ended, handle, opaque);
        if (taken < 0) return taken;
        if (ended) return 0;
        if (taken > 0) deadline = av_gettime_relative() + STALL_US;
        if (av_gettime_relative() > deadline) {
            dr_logf(&codec->logger, DR_LOG_ERROR, "%s gave nothing for %d s", codec->component, (int) (STALL_US / 1000000));
            return AVERROR(ETIMEDOUT);
        }
    }
}

/* The first instance's outputs: the parameter sets, from a codec config buffer or else from the frame. */
static int learn(void *opaque, const uint8_t *data, size_t size, int64_t pts_us, uint32_t flags) {
    (void) pts_us;
    dr_mediacodec *codec = opaque;
    /* Usually one buffer with all of them, but some codecs give a buffer for each. */
    if (flags & AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG) return append(&codec->parameter_sets, &codec->parameter_sets_size, data, size);
    if (codec->parameter_sets_size > 0) return 0;
    return collect_parameter_sets(codec, data, size);
}

/* One black frame through a codec of its own, ended and thrown away: the parameter sets. */
static int learn_parameter_sets(dr_mediacodec *codec, char *error, size_t error_size) {
    instance first = {0};
    int result = start(&codec->config, &codec->operating_rate, &first, &codec->logger, error, error_size);
    if (result < 0) return result;
    AVFrame *black = av_frame_alloc();
    result = black != NULL ? 0 : AVERROR(ENOMEM);
    if (result >= 0) {
        black->width = codec->config.width;
        black->height = codec->config.height;
        black->format = codec->config.color_format == DR_COLOR_FORMAT_NV12 ? AV_PIX_FMT_NV12 : AV_PIX_FMT_YUV420P;
        result = av_frame_get_buffer(black, 0);
    }
    if (result >= 0) {
        memset(black->data[0], 16, (size_t) black->linesize[0] * (size_t) black->height);
        for (int plane = 1; plane < 3 && black->data[plane] != NULL; plane++) {
            memset(black->data[plane], 128, (size_t) black->linesize[plane] * (size_t) (black->height / 2));
        }
        result = queue(codec, &first, black, 0, learn, codec);
    }
    if (result >= 0) result = queue(codec, &first, NULL, 0, learn, codec);
    if (result >= 0) result = drain_to_end(codec, &first, learn, codec);
    av_frame_free(&black);
    stop(&first);
    if (result < 0) {
        dr_set_error(error, error_size, "%s did not encode a first frame: %s", codec->component, av_err2str(result));
        return result;
    }
    if (codec->parameter_sets_size == 0) {
        dr_set_error(error, error_size, "%s gave no parameter sets", codec->component);
        return AVERROR_EXTERNAL;
    }
    return 0;
}

int dr_mediacodec_open(dr_mediacodec **out, const dr_mediacodec_config *config, const dr_logger *logger,
                       char *error, size_t error_size) {
    *out = NULL;
    if (config->component == NULL || !config->component[0] || config->width <= 0 || config->height <= 0 ||
        (config->color_format != DR_COLOR_FORMAT_NV12 && config->color_format != DR_COLOR_FORMAT_I420)) {
        dr_set_error(error, error_size, "MediaCodec needs a component and a colour format (19 or 21) to encode");
        return AVERROR(EINVAL);
    }
    dr_mediacodec *codec = calloc(1, sizeof(dr_mediacodec));
    if (codec == NULL) return AVERROR(ENOMEM);
    if (logger != NULL) codec->logger = *logger;
    codec->config = *config;
    codec->component = strdup(config->component);
    codec->mime = strdup(config->mime);
    if (codec->component == NULL || codec->mime == NULL) {
        dr_mediacodec_close(codec);
        return AVERROR(ENOMEM);
    }
    codec->config.component = codec->component;
    codec->config.mime = codec->mime;
    codec->hevc = strcmp(config->mime, "video/hevc") == 0;
    codec->operating_rate = OPERATING_RATE;
    int result = learn_parameter_sets(codec, error, error_size);
    if (result >= 0) result = start(&codec->config, &codec->operating_rate, &codec->encoder, &codec->logger, error, error_size);
    if (result < 0) {
        dr_mediacodec_close(codec);
        return result;
    }
    dr_logf(&codec->logger, DR_LOG_INFO, "%s takes %dx%d frames at stride %d and slice height %d; parameter sets of %zu bytes",
            codec->component, config->width, config->height, codec->encoder.stride, codec->encoder.slice_height,
            codec->parameter_sets_size);
    *out = codec;
    return 0;
}

const uint8_t *dr_mediacodec_parameter_sets(const dr_mediacodec *codec, size_t *size) {
    *size = codec->parameter_sets_size;
    return codec->parameter_sets;
}

/* The encoder's outputs: parameter sets kept for the next frame, frames passed on. */
static int pass_on(void *opaque, const uint8_t *data, size_t size, int64_t pts_us, uint32_t flags) {
    dr_mediacodec *codec = opaque;
    if (flags & AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG) return append(&codec->pending, &codec->pending_size, data, size);
    int64_t dts_us = dr_order_out(&codec->order, pts_us);
    /* The key frame flag is set on every API level, though the NDK only names it from API 34 on. */
    int key = (flags & AMEDIACODEC_BUFFER_FLAG_KEY_FRAME) != 0;
    if (codec->pending_size == 0) return codec->emit(codec->emit_opaque, data, size, pts_us, dts_us, key);
    size_t total = codec->pending_size + size;
    if (total > codec->packet_capacity) {
        uint8_t *grown = realloc(codec->packet, total);
        if (grown == NULL) return AVERROR(ENOMEM);
        codec->packet = grown;
        codec->packet_capacity = total;
    }
    memcpy(codec->packet, codec->pending, codec->pending_size);
    memcpy(codec->packet + codec->pending_size, data, size);
    codec->pending_size = 0;
    return codec->emit(codec->emit_opaque, codec->packet, total, pts_us, dts_us, key);
}

int dr_mediacodec_send(dr_mediacodec *codec, const AVFrame *frame, int64_t pts_us, dr_mediacodec_emit emit, void *opaque) {
    codec->emit = emit;
    codec->emit_opaque = opaque;
    int result = queue(codec, &codec->encoder, frame, pts_us, pass_on, codec);
    /* Only the end of the stream ends the stream. */
    if (result == AVERROR_EOF) return AVERROR_EXTERNAL;
    return result < 0 ? result : dr_order_in(&codec->order, pts_us);
}

int dr_mediacodec_finish(dr_mediacodec *codec, dr_mediacodec_emit emit, void *opaque) {
    codec->emit = emit;
    codec->emit_opaque = opaque;
    int result = queue(codec, &codec->encoder, NULL, 0, pass_on, codec);
    if (result == AVERROR_EOF) return 0;
    if (result >= 0) result = drain_to_end(codec, &codec->encoder, pass_on, codec);
    return result;
}

void dr_mediacodec_close(dr_mediacodec *codec) {
    if (codec == NULL) return;
    stop(&codec->encoder);
    free(codec->component);
    free(codec->mime);
    free(codec->parameter_sets);
    free(codec->pending);
    free(codec->packet);
    dr_order_free(&codec->order);
    free(codec);
}
