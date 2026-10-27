/*
 * Rips one title into Matroska: the VOBU reader feeds FFmpeg's MPEG-PS demuxer through a custom
 * AVIOContext, video, compressed audio and subpictures are copied, LPCM is re-encoded losslessly
 * (PCM, or FLAC when asked), timestamps are lined up across VOB boundaries, chapters come from
 * the cells where programs start, and the file is written through the caller's dr_file.
 */
#ifndef DR_RIP_H
#define DR_RIP_H

#include "dr_common.h"
#include "dr_file.h"

#include <dvdread/dvd_reader.h>

/* Track flags, written as the Matroska flags of the same names. */
enum {
    DR_TRACK_DEFAULT = 1,
    /* Shown even when subtitles are off. */
    DR_TRACK_FORCED = 2,
    DR_TRACK_COMMENTARY = 4,
    DR_TRACK_HEARING_IMPAIRED = 8,
    DR_TRACK_VISUAL_IMPAIRED = 16,
};

typedef struct dr_audio_choice {
    /* AVStream.id from FFmpeg's MPEG-PS demuxer: 0x80+n AC-3, 0x88+n DTS, 0xA0+n LPCM, 0x1C0+n MPEG. */
    int stream_id;
    /* ISO 639-2/B code for the track, or NULL. */
    const char *language;
    /* Track name, or NULL. */
    const char *name;
    /* DR_TRACK_ flags. */
    int flags;
} dr_audio_choice;

typedef struct dr_subtitle_choice {
    /* AVStream.id of the subpicture stream: 0x20 + its number for the title's display shape. */
    int stream_id;
    /* ISO 639-2/B code for the track, or NULL. */
    const char *language;
    /* Track name, or NULL. */
    const char *name;
    /* DR_TRACK_ flags. */
    int flags;
} dr_subtitle_choice;

typedef struct dr_rip_options {
    int title;
    int angle;
    const dr_audio_choice *audio;
    int audio_count;
    /* VobSub tracks, written as S_VOBSUB with the title's palette. */
    const dr_subtitle_choice *subtitles;
    int subtitle_count;
    int lpcm_to_flac;
    const char *movie_title;
    /* "YYYY" or "YYYY-MM-DD", or NULL. */
    const char *release_date;
    /* More global tags, such as IMDB ("tt0133093"), TMDB ("movie/603") and SUMMARY. */
    const char *const *tag_keys;
    const char *const *tag_values;
    int tag_count;
    /* Names for chapters 1..chapter_name_count; others get "Chapter NN". */
    const char *const *chapter_names;
    int chapter_name_count;
    /* Where the file goes, written from position 0; stays the caller's. */
    dr_file output;
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
 *    "audioTracks":…,"subtitleTracks":…,"warnings":[…]}
 *   {"ok":false,"error":"cancelled|no_title|unreadable|scrambled|no_video|no_space|write_failed|failed","message":"…"}
 */
char *dr_rip(dvd_reader_t *dvd, const dr_rip_options *options, const dr_rip_hooks *hooks, const dr_logger *logger);

#endif
