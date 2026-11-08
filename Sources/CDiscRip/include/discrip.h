// DiscRip native layer: DVD-Video parsing (libdvdread + libdvdcss) and the rip pipeline
// (MPEG-PS demux, optional VideoToolbox encode, Matroska mux) on top of FFmpeg.
#ifndef DISCRIP_H
#define DISCRIP_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct dr_disc dr_disc;

/// Opens a DVD-Video source: a raw device (/dev/rdiskN), a mount point, an ISO image,
/// or a folder that contains VIDEO_TS (or the VIDEO_TS folder itself).
/// `css_cache_dir` may be NULL. On failure returns NULL and sets *error_out (free with dr_free).
dr_disc *dr_disc_open(const char *path, const char *css_cache_dir, char **error_out);
void dr_disc_close(dr_disc *disc);

/// JSON description of the disc: volume label, region mask and every title with its
/// cells, chapters, video attributes, audio and subpicture streams. Free with dr_free.
char *dr_disc_info_json(dr_disc *disc);

void dr_free(void *ptr);

/// Reads the RPC region state of an optical drive (raw BSD device path, e.g. /dev/rdisk4).
/// region_mask uses the DVD convention (bit n set = region n+1 NOT allowed).
/// Returns 0 on success, -1 when the drive does not report it (RPC-1 or no disc).
int dr_drive_region_state(const char *raw_device_path, int *region_mask, int *changes_left,
                          int *rpc_scheme, int *type_code);

/// Lists the hardware video encoders FFmpeg can open on this Mac: bit 0 = HEVC, bit 1 = H.264.
int dr_hw_encoders_available(void);

enum {
    DR_VIDEO_COPY = 0,
    DR_VIDEO_HEVC = 1,
    DR_VIDEO_H264 = 2,
};

enum {
    DR_PHASE_READING = 0,   // reading + muxing (copy)
    DR_PHASE_ENCODING = 1,  // reading + encoding (lossy)
    DR_PHASE_FINISHING = 2, // writing index / trailer
};

typedef struct {
    int phase;
    int64_t bytes_done;
    int64_t bytes_total;
    double elapsed_seconds;
    double media_seconds_done;
    double media_seconds_total;
    double encoder_fps;        // 0 for copy
    int64_t output_bytes;
    int bad_sectors;
} dr_progress;

typedef void (*dr_progress_cb)(void *ctx, const dr_progress *progress);
typedef void (*dr_log_cb)(void *ctx, int level, const char *message);

typedef struct {
    int title;                         // 1-based title number (VMG TT_SRPT)
    int angle;                         // 1-based angle
    const int *audio_stream_ids;       // MPEG-PS stream ids, output order
    const char *const *audio_languages;// ISO 639-2/B per audio track ("und" if unknown)
    const char *const *audio_names;    // track names (may be NULL)
    int audio_count;
    const int *subtitle_stream_ids;    // 0x20..0x3f
    const char *const *subtitle_languages;
    const char *const *subtitle_names;
    int subtitle_count;
    int default_audio_index;           // index into audio list, -1 for none
    int video_mode;                    // DR_VIDEO_*
    int64_t video_bitrate;             // bits/s for lossy modes
    int allow_h264_fallback;           // HEVC unavailable -> H.264
    int lpcm_to_flac;                  // LPCM -> FLAC (otherwise bit-exact PCM)
    const char *output_path;
    const char *const *global_tags;    // key, value, key, value, ..., NULL
    const char *chapter_name_format;   // printf format with one %d, e.g. "Hoofdstuk %d"
    volatile int *cancel;              // set to non-zero to cancel
    dr_progress_cb progress;
    dr_log_cb log;
    void *ctx;
} dr_rip_options;

enum {
    DR_OK = 0,
    DR_CANCELLED = 1,
    DR_ERR_OPEN_TITLE = 2,     // title / VOB not found
    DR_ERR_CSS_KEY = 3,        // CSS title key could not be derived
    DR_ERR_READ_REFUSED = 4,   // first reads refused (typical for RPC-2 region mismatch)
    DR_ERR_READ = 5,           // too many consecutive unreadable sectors
    DR_ERR_DEVICE_GONE = 6,    // drive disconnected / disc ejected
    DR_ERR_DEMUX = 7,
    DR_ERR_ENCODER = 8,        // hardware encoder unavailable or failed mid-rip
    DR_ERR_OUTPUT = 9,         // cannot write output
    DR_ERR_NO_SPACE = 10,      // ENOSPC
    DR_ERR_INTERNAL = 11,
};

#define DR_MAX_BAD_RANGES 64

typedef struct {
    int code;
    char message[512];
    int video_mode_used;       // DR_VIDEO_* actually used
    int fell_back_to_h264;
    int bad_range_count;
    uint32_t bad_range_start[DR_MAX_BAD_RANGES];
    uint32_t bad_range_length[DR_MAX_BAD_RANGES];
    int bad_sectors;
    int64_t output_bytes;
    double media_seconds;
    int partial_file_removed;
} dr_rip_result;

/// Rips one title to a Matroska file. Blocking; call from a background thread.
int dr_rip(dr_disc *disc, const dr_rip_options *options, dr_rip_result *result);

#ifdef __cplusplus
}
#endif
#endif
