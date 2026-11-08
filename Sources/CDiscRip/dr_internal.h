#ifndef DR_INTERNAL_H
#define DR_INTERNAL_H

#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>
#include <pthread.h>

#include <dvdread/dvd_reader.h>
#include <dvdread/ifo_read.h>
#include <dvdread/ifo_types.h>

#include "discrip.h"

#define DR_MAX_VTS 100

struct dr_disc {
    dvd_reader_t *dvd;
    char *path;
    ifo_handle_t *vmg;
    ifo_handle_t *vts[DR_MAX_VTS];
    int css_failed_vts[DR_MAX_VTS]; // 1 when "Error cracking CSS key" was logged for that VTS
    pthread_mutex_t lock;
    dr_log_cb log;
    void *log_ctx;
};

/// One playable cell of a title (after angle selection), in presentation order.
typedef struct {
    uint32_t first_sector;
    uint32_t last_sector;
    int64_t start90;     // start on the title timeline (90 kHz), from IFO playback times
    int64_t duration90;
    int chapter_start;   // 1 when a chapter (PTT) starts at this cell
} dr_cell;

typedef struct {
    int vts;
    int ttn;
    int angles;
    dr_cell *cells;
    int cell_count;
    int chapter_count;
    int64_t duration90;
    int64_t sectors;
    pgc_t *first_pgc;
} dr_title_layout;

ifo_handle_t *dr_vts_ifo(dr_disc *disc, int vts);
int dr_title_layout_build(dr_disc *disc, int title, int angle, dr_title_layout *out);
void dr_title_layout_free(dr_title_layout *layout);
int64_t dr_dvd_time_to_90k(const dvd_time_t *t);
int dr_frame_rate_code(const dvd_time_t *t); // 1 = 25 fps, 3 = 29.97 fps, 0 unknown

/// Physical subpicture stream number for logical stream i, taking the display aspect into account.
int dr_subp_physical(const pgc_t *pgc, int logical, int wide);
/// MPEG-PS stream id of an audio stream (format from the VTS attributes).
int dr_audio_stream_id(int audio_format, int physical);

/// Growable string buffer for JSON output.
typedef struct {
    char *data;
    size_t len;
    size_t cap;
} dr_buf;
void dr_buf_printf(dr_buf *b, const char *fmt, ...) __attribute__((format(printf, 2, 3)));
void dr_buf_json_string(dr_buf *b, const char *s);

void dr_logf(dr_disc *disc, int level, const char *fmt, ...) __attribute__((format(printf, 3, 4)));

#endif
