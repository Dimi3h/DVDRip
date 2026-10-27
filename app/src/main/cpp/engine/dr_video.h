/*
 * The video of the lossy presets: MPEG-2 decoded in software, deinterlaced with yadif only where
 * frames are flagged interlaced, given the sample aspect ratio that shows the picture at the
 * DVD's aspect, and encoded at the title's size and frame rate by the encoder the app chose
 * (hevc_mediacodec or h264_mediacodec; a software encoder in the host tests). The size is that of
 * the first frames, or the IFO's when that is clearly larger, so a title that opens with a part
 * at half resolution is not made smaller; frames of another size are scaled to it.
 */
#ifndef DR_VIDEO_H
#define DR_VIDEO_H

#include "dr_common.h"

#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>

typedef struct dr_video_config {
    /* FFmpeg encoder name, such as "hevc_mediacodec". */
    const char *encoder;
    /* For MediaCodec encoders: the component to use, such as "c2.qti.hevc.encoder"; NULL lets
       MediaCodec pick the first for the type. */
    const char *component;
    /* Target bit rate in bit/s. */
    int64_t bit_rate;
    /* For MediaCodec encoders: "vbr", "cbr" or "cq"; NULL leaves the component's default. */
    const char *bitrate_mode;
    /* Display aspect ratio from the IFO (4:3 or 16:9); 0/0 to keep the stream's own. */
    AVRational display_aspect;
    /* The picture size the IFO gives the title set; 0 when unknown. */
    int title_width;
    int title_height;
} dr_video_config;

typedef struct dr_video dr_video;

/* Hands an encoded packet, in the encoder's time base, to the muxer; negative on failure. */
typedef int (*dr_video_emit)(void *opaque, AVPacket *packet);

/*
 * Opens the decoder, the encoder and the filters between them for the input stream in. With
 * global_header the encoder puts its parameter sets in extradata, as Matroska wants. Returns 0,
 * or a negative error with a message in error.
 */
int dr_video_open(dr_video **out, const AVStream *in, const dr_video_config *config, int global_header,
                  const dr_logger *logger, char *error, size_t error_size);

/* What the output stream needs: codec parameters, time base and sample aspect ratio. */
int dr_video_describe(const dr_video *video, AVStream *out);

/*
 * Decodes a packet whose timestamps are on the output timeline and encodes what comes out.
 * Frames before 0 are dropped, packets the decoder rejects are skipped. Negative when the
 * filters or the encoder fail, or emit does.
 */
int dr_video_send(dr_video *video, AVPacket *packet, dr_video_emit emit, void *opaque);

/* Drains decoder, filters and encoder at the end of the title. */
int dr_video_flush(dr_video *video, dr_video_emit emit, void *opaque);

int64_t dr_video_frames_encoded(const dr_video *video);

/* Decoded frames that were flagged interlaced, which yadif deinterlaced. */
int64_t dr_video_interlaced_frames(const dr_video *video);

void dr_video_close(dr_video *video);

#endif
