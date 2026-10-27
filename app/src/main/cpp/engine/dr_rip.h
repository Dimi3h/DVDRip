/*
 * Rips one title into Matroska: the VOBU reader feeds FFmpeg's MPEG-PS demuxer through a custom
 * AVIOContext, video and compressed audio are copied, LPCM is re-encoded losslessly (PCM, or
 * FLAC when asked), timestamps are lined up across VOB boundaries, chapters come from the cells
 * where programs start, and the file is written to a caller's file descriptor.
 */
#ifndef DR_RIP_H
#define DR_RIP_H

#include "dr_common.h"

#include <dvdread/dvd_reader.h>

typedef struct dr_audio_choice {
    /* AVStream.id from FFmpeg's MPEG-PS demuxer: 0x80+n AC-3, 0x88+n DTS, 0xA0+n LPCM, 0x1C0+n MPEG. */
    int stream_id;
    /* ISO 639-2/B code for the track, or NULL. */
    const char *language;
    /* Track name, or NULL. */
    const char *name;
    int is_default;
} dr_audio_choice;

typedef struct dr_rip_options {
    int title;
    int angle;
    const dr_audio_choice *audio;
    int audio_count;
    int lpcm_to_flac;
    const char *movie_title;
    /* "YYYY" or "YYYY-MM-DD", or NULL. */
    const char *release_date;
    /* Names for chapters 1..chapter_name_count; others get "Chapter NN". */
    const char *const *chapter_names;
    int chapter_name_count;
    /* Open for writing and seekable; stays owned by the caller. */
    int output_fd;
} dr_rip_options;

typedef struct dr_progress {
    uint64_t sectors_done;
    uint64_t sectors_total;
    int64_t output_bytes;
    int64_t position_ms;
} dr_progress;

typedef struct dr_rip_hooks {
    void *opaque;
    void (*progress)(void *opaque, const dr_progress *progress);
    int (*cancelled)(void *opaque);
} dr_rip_hooks;

/*
 * Rips and returns a JSON report (caller frees):
 *   {"ok":true,"outputBytes":…,"durationMs":…,"chapterStartsMs":[…],"badSectors":[[first,count],…],
 *    "audioTracks":…,"warnings":[…]}
 *   {"ok":false,"error":"cancelled|no_title|unreadable|scrambled|no_video|no_space|write_failed|failed","message":"…"}
 */
char *dr_rip(dvd_reader_t *dvd, const dr_rip_options *options, const dr_rip_hooks *hooks, const dr_logger *logger);

#endif
