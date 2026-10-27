/*
 * The lossy presets' encoder on Android: MediaCodec, driven through the NDK by the engine itself
 * rather than by FFmpeg's hevc_mediacodec and h264_mediacodec, which
 *   - learn the parameter sets by encoding a dummy frame, ending the stream and flushing to go on,
 *     which Android leaves undefined for encoders (the emulator's encoder stops there),
 *   - write frames into the input buffers at their own stride, not at the one the codec gives,
 *   - and wait for a stuck codec without end, beyond the reach of cancelling.
 * Here a first, short-lived instance encodes one black frame to learn the parameter sets, which
 * Matroska wants before the first packet; the real instance then starts afresh. Frames go in at
 * the codec's stride and slice height, and every wait has a deadline and can be cancelled.
 * MediaCodec gives no decoding times; they are derived here (dr_order.h), so that they also hold
 * for an encoder that makes B-frames although it is asked for none.
 */
#ifndef DR_MEDIACODEC_H
#define DR_MEDIACODEC_H

#include "dr_common.h"

#include <libavutil/frame.h>
#include <stddef.h>
#include <stdint.h>

/* MediaCodec's colour formats for frames in buffers. */
#define DR_COLOR_FORMAT_I420 19 /* COLOR_FormatYUV420Planar */
#define DR_COLOR_FORMAT_NV12 21 /* COLOR_FormatYUV420SemiPlanar */

typedef struct dr_mediacodec_config {
    /* The component, such as "c2.qti.hevc.encoder". */
    const char *component;
    /* "video/hevc" or "video/avc". */
    const char *mime;
    int width;
    int height;
    /* DR_COLOR_FORMAT_NV12 or DR_COLOR_FORMAT_I420: the frames handed in are NV12 or planar 4:2:0. */
    int color_format;
    int64_t bit_rate;
    /* MediaCodec's bitrate-mode: 1 variable, 2 constant; -1 leaves the component's own. */
    int bitrate_mode;
    int frame_rate;
    int key_frame_seconds;
    /* For the colour description: 576 lines are PAL's BT.601, 480 NTSC's. */
    int pal;
    /* Polled while waiting on the codec; non-zero stops it with AVERROR_EXIT. */
    int (*cancelled)(void *opaque);
    void *cancel_opaque;
} dr_mediacodec_config;

typedef struct dr_mediacodec dr_mediacodec;

/* An encoded frame: Annex B, times in microseconds. Negative stops the encoding with that error. */
typedef int (*dr_mediacodec_emit)(void *opaque, const uint8_t *data, size_t size, int64_t pts_us, int64_t dts_us, int key);

/* Learns the parameter sets, then starts the encoder. 0, or a negative error with a message. */
int dr_mediacodec_open(dr_mediacodec **out, const dr_mediacodec_config *config, const dr_logger *logger,
                       char *error, size_t error_size);

/* The parameter sets (VPS for HEVC, SPS, PPS) in Annex B, as Matroska's CodecPrivate needs them. */
const uint8_t *dr_mediacodec_parameter_sets(const dr_mediacodec *codec, size_t *size);

/* Encodes a frame of the configured size and format, passing on what comes out. Each frame must
   come later than the one before. */
int dr_mediacodec_send(dr_mediacodec *codec, const AVFrame *frame, int64_t pts_us, dr_mediacodec_emit emit, void *opaque);

/* Ends the stream and passes on the rest. */
int dr_mediacodec_finish(dr_mediacodec *codec, dr_mediacodec_emit emit, void *opaque);

void dr_mediacodec_close(dr_mediacodec *codec);

#endif
