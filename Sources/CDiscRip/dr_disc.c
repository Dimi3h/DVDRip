#include "dr_internal.h"

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <IOKit/storage/IODVDMediaBSDClient.h>
#include <libavcodec/avcodec.h>

#include <dvdread/nav_read.h>

void dr_free(void *ptr) { free(ptr); }

void dr_buf_printf(dr_buf *b, const char *fmt, ...) {
    va_list ap;
    for (;;) {
        size_t room = b->cap - b->len;
        va_start(ap, fmt);
        int n = vsnprintf(b->data ? b->data + b->len : NULL, b->data ? room : 0, fmt, ap);
        va_end(ap);
        if (n < 0) return;
        if (b->data && (size_t)n < room) {
            b->len += (size_t)n;
            return;
        }
        size_t need = b->len + (size_t)n + 1;
        size_t cap = b->cap ? b->cap : 4096;
        while (cap < need) cap *= 2;
        char *p = realloc(b->data, cap);
        if (!p) return;
        b->data = p;
        b->cap = cap;
    }
}

void dr_buf_json_string(dr_buf *b, const char *s) {
    dr_buf_printf(b, "\"");
    for (const unsigned char *p = (const unsigned char *)(s ? s : ""); *p; p++) {
        switch (*p) {
        case '"': dr_buf_printf(b, "\\\""); break;
        case '\\': dr_buf_printf(b, "\\\\"); break;
        case '\n': dr_buf_printf(b, "\\n"); break;
        case '\r': dr_buf_printf(b, "\\r"); break;
        case '\t': dr_buf_printf(b, "\\t"); break;
        default:
            if (*p < 0x20) dr_buf_printf(b, "\\u%04x", *p);
            else dr_buf_printf(b, "%c", *p);
        }
    }
    dr_buf_printf(b, "\"");
}

void dr_logf(dr_disc *disc, int level, const char *fmt, ...) {
    if (!disc || !disc->log) return;
    char msg[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(msg, sizeof msg, fmt, ap);
    va_end(ap);
    disc->log(disc->log_ctx, level, msg);
}

static void dvdread_log(void *priv, dvd_logger_level_t level, const char *fmt, va_list ap) {
    dr_disc *disc = priv;
    char msg[1024];
    vsnprintf(msg, sizeof msg, fmt, ap);
    // "Error cracking CSS key for /VIDEO_TS/VTS_02_1.VOB (0x...)"
    const char *p = strstr(msg, "Error cracking CSS key");
    if (p) {
        const char *v = strstr(p, "VTS_");
        if (v) {
            int vts = atoi(v + 4);
            if (vts > 0 && vts < DR_MAX_VTS) disc->css_failed_vts[vts] = 1;
        }
    }
    if (disc->log) disc->log(disc->log_ctx, level == DVD_LOGGER_LEVEL_ERROR ? 0 : 1, msg);
    else if (level == DVD_LOGGER_LEVEL_ERROR) fprintf(stderr, "libdvdread: %s\n", msg);
}

dr_disc *dr_disc_open(const char *path, const char *css_cache_dir, char **error_out) {
    if (error_out) *error_out = NULL;
    if (css_cache_dir && *css_cache_dir) setenv("DVDCSS_CACHE", css_cache_dir, 1);
    // A real drive on macOS lets libdvdcss authenticate (method "key"); libdvdcss falls back to
    // disc and title key cracking by itself when authentication is not possible.
    if (!getenv("DVDCSS_METHOD")) setenv("DVDCSS_METHOD", "key", 1);

    dr_disc *disc = calloc(1, sizeof *disc);
    if (!disc) return NULL;
    pthread_mutex_init(&disc->lock, NULL);
    disc->path = strdup(path);

    // A folder may be the VIDEO_TS folder itself or its parent; libdvdread handles both.
    static const dvd_logger_cb logger = { dvdread_log };
    disc->dvd = DVDOpen2(disc, &logger, path);
    if (!disc->dvd) {
        if (error_out) {
            char buf[600];
            snprintf(buf, sizeof buf, "Cannot open DVD-Video at %s", path);
            *error_out = strdup(buf);
        }
        dr_disc_close(disc);
        return NULL;
    }
    disc->vmg = ifoOpen(disc->dvd, 0);
    if (!disc->vmg || !disc->vmg->tt_srpt) {
        if (error_out) *error_out = strdup("No valid VIDEO_TS.IFO: not a DVD-Video disc");
        dr_disc_close(disc);
        return NULL;
    }
    return disc;
}

void dr_disc_close(dr_disc *disc) {
    if (!disc) return;
    for (int i = 0; i < DR_MAX_VTS; i++)
        if (disc->vts[i]) ifoClose(disc->vts[i]);
    if (disc->vmg) ifoClose(disc->vmg);
    if (disc->dvd) DVDClose(disc->dvd);
    pthread_mutex_destroy(&disc->lock);
    free(disc->path);
    free(disc);
}

ifo_handle_t *dr_vts_ifo(dr_disc *disc, int vts) {
    if (vts <= 0 || vts >= DR_MAX_VTS) return NULL;
    pthread_mutex_lock(&disc->lock);
    if (!disc->vts[vts]) disc->vts[vts] = ifoOpen(disc->dvd, vts);
    ifo_handle_t *ifo = disc->vts[vts];
    pthread_mutex_unlock(&disc->lock);
    return ifo;
}

static int bcd(uint8_t v) { return ((v >> 4) & 0x0f) * 10 + (v & 0x0f); }

int dr_frame_rate_code(const dvd_time_t *t) { return (t->frame_u >> 6) & 0x03; }

int64_t dr_dvd_time_to_90k(const dvd_time_t *t) {
    int64_t secs = (int64_t)bcd(t->hour) * 3600 + bcd(t->minute) * 60 + bcd(t->second);
    int frames = bcd(t->frame_u & 0x3f);
    int64_t per_frame = dr_frame_rate_code(t) == 3 ? 3003 : 3600; // 29.97 or 25 fps
    return secs * 90000 + frames * per_frame;
}

int dr_subp_physical(const pgc_t *pgc, int logical, int wide) {
    uint32_t c = pgc->subp_control[logical];
    if (!(c & 0x80000000u)) return -1;
    return wide ? (int)((c >> 16) & 0x1f) : (int)((c >> 24) & 0x1f);
}

int dr_audio_stream_id(int audio_format, int physical) {
    switch (audio_format) {
    case 0: return 0x80 + physical;  // AC-3
    case 2:
    case 3: return 0x1c0 + physical; // MPEG-1/2 audio
    case 4: return 0xa0 + physical;  // LPCM
    case 6: return 0x88 + physical;  // DTS
    default: return -1;
    }
}

void dr_title_layout_free(dr_title_layout *layout) {
    free(layout->cells);
    memset(layout, 0, sizeof *layout);
}

int dr_title_layout_build(dr_disc *disc, int title, int angle, dr_title_layout *out) {
    memset(out, 0, sizeof *out);
    tt_srpt_t *tt = disc->vmg->tt_srpt;
    if (title < 1 || title > tt->nr_of_srpts) return -1;
    title_info_t *ti = &tt->title[title - 1];
    ifo_handle_t *ifo = dr_vts_ifo(disc, ti->title_set_nr);
    if (!ifo || !ifo->vts_ptt_srpt || !ifo->vts_pgcit) return -1;
    if (ti->vts_ttn < 1 || ti->vts_ttn > ifo->vts_ptt_srpt->nr_of_srpts) return -1;
    ttu_t *ttu = &ifo->vts_ptt_srpt->title[ti->vts_ttn - 1];
    if (ttu->nr_of_ptts < 1) return -1;

    out->vts = ti->title_set_nr;
    out->ttn = ti->vts_ttn;
    out->angles = ti->nr_of_angles ? ti->nr_of_angles : 1;
    if (angle < 1 || angle > out->angles) angle = 1;

    int cap = 64;
    out->cells = calloc((size_t)cap, sizeof(dr_cell));
    if (!out->cells) return -1;

    // Walk the PTTs; each PTT names (pgcn, pgn). Consecutive PTTs in the same PGC are chapters of
    // that PGC; a new pgcn appends that PGC's cells.
    int64_t t90 = 0;
    int prev_pgcn = -1;
    for (int p = 0; p < ttu->nr_of_ptts; p++) {
        int pgcn = ttu->ptt[p].pgcn;
        int pgn = ttu->ptt[p].pgn;
        if (pgcn < 1 || pgcn > ifo->vts_pgcit->nr_of_pgci_srp) continue;
        pgc_t *pgc = ifo->vts_pgcit->pgci_srp[pgcn - 1].pgc;
        if (!pgc || !pgc->cell_playback || !pgc->program_map) continue;
        if (!out->first_pgc) out->first_pgc = pgc;
        if (pgcn == prev_pgcn) continue; // cells already appended; chapters are marked below
        prev_pgcn = pgcn;

        // Chapter entry cells of this PGC (from all PTTs that point at it).
        int entry[256] = {0};
        for (int q = p; q < ttu->nr_of_ptts && ttu->ptt[q].pgcn == pgcn; q++) {
            int g = ttu->ptt[q].pgn;
            if (g >= 1 && g <= pgc->nr_of_programs) {
                int cell = pgc->program_map[g - 1];
                if (cell >= 1 && cell <= 255) entry[cell] = 1;
            }
        }
        (void)pgn;

        int angle_pos = 0;
        for (int c = 0; c < pgc->nr_of_cells; c++) {
            cell_playback_t *cp = &pgc->cell_playback[c];
            if (cp->block_type == 1) { // angle block: block_mode 1 = first, 3 = last
                if (cp->block_mode == 1) angle_pos = 0;
                angle_pos++;
                if (angle_pos != angle) {
                    // A chapter that starts on a skipped angle cell starts on the chosen one.
                    if (entry[c + 1] && c + 1 < 255) entry[c + 2] = 1;
                    continue;
                }
            }
            if (cp->last_sector < cp->first_sector) continue;
            if (out->cell_count == cap) {
                cap *= 2;
                dr_cell *n = realloc(out->cells, (size_t)cap * sizeof(dr_cell));
                if (!n) return -1;
                out->cells = n;
            }
            dr_cell *dc = &out->cells[out->cell_count++];
            dc->first_sector = cp->first_sector;
            dc->last_sector = cp->last_sector;
            dc->start90 = t90;
            dc->duration90 = dr_dvd_time_to_90k(&cp->playback_time);
            dc->chapter_start = (c + 1 <= 255) ? entry[c + 1] : 0;
            t90 += dc->duration90;
            out->sectors += (int64_t)cp->last_sector - cp->first_sector + 1;
        }
    }
    if (out->cell_count == 0) {
        dr_title_layout_free(out);
        return -1;
    }
    out->cells[0].chapter_start = 1;
    for (int i = 0; i < out->cell_count; i++)
        if (out->cells[i].chapter_start) out->chapter_count++;
    out->duration90 = t90;
    return 0;
}

static void lang_string(uint16_t code, char out[3]) {
    char a = (char)(code >> 8), b = (char)(code & 0xff);
    if (a >= 'a' && a <= 'z' && b >= 'a' && b <= 'z') {
        out[0] = a; out[1] = b; out[2] = 0;
    } else if (a >= 'A' && a <= 'Z' && b >= 'A' && b <= 'Z') {
        out[0] = (char)(a + 32); out[1] = (char)(b + 32); out[2] = 0;
    } else {
        out[0] = 0;
    }
}

static const char *audio_format_name(int f) {
    switch (f) {
    case 0: return "ac3";
    case 2: return "mpeg1";
    case 3: return "mpeg2";
    case 4: return "lpcm";
    case 6: return "dts";
    default: return "unknown";
    }
}

static void ycrcb_to_rgb(uint32_t v, int *r, int *g, int *b) {
    int y = (v >> 16) & 0xff, cr = (v >> 8) & 0xff, cb = v & 0xff;
    double R = 1.164 * (y - 16) + 1.596 * (cr - 128);
    double G = 1.164 * (y - 16) - 0.813 * (cr - 128) - 0.391 * (cb - 128);
    double B = 1.164 * (y - 16) + 2.018 * (cb - 128);
    *r = R < 0 ? 0 : R > 255 ? 255 : (int)(R + 0.5);
    *g = G < 0 ? 0 : G > 255 ? 255 : (int)(G + 0.5);
    *b = B < 0 ? 0 : B > 255 ? 255 : (int)(B + 0.5);
}

char *dr_disc_info_json(dr_disc *disc) {
    dr_buf b = {0};
    char volid[33] = {0};
    unsigned char setid[128] = {0};
    if (DVDUDFVolumeInfo(disc->dvd, volid, sizeof volid, setid, sizeof setid) != 0) {
        memset(volid, 0, sizeof volid);
        DVDISOVolumeInfo(disc->dvd, volid, sizeof volid, setid, sizeof setid);
    }
    int region_mask = (int)((disc->vmg->vmgi_mat->vmg_category >> 16) & 0xff);
    dr_buf_printf(&b, "{\"volumeLabel\":");
    dr_buf_json_string(&b, volid);
    dr_buf_printf(&b, ",\"discRegionMask\":%d,\"titles\":[", region_mask);

    tt_srpt_t *tt = disc->vmg->tt_srpt;
    int first = 1;
    for (int t = 1; t <= tt->nr_of_srpts; t++) {
        dr_title_layout L;
        if (dr_title_layout_build(disc, t, 1, &L) != 0) continue;
        ifo_handle_t *ifo = dr_vts_ifo(disc, L.vts);
        vtsi_mat_t *m = ifo->vtsi_mat;
        pgc_t *pgc = L.first_pgc;
        video_attr_t *va = &m->vts_video_attr;
        int pal = va->video_format == 1;
        int height = pal ? 576 : 480;
        int width;
        switch (va->picture_size) {
        case 0: width = 720; break;
        case 1: width = 704; break;
        case 2: width = 352; break;
        default: width = 352; height /= 2; break;
        }
        int wide = va->display_aspect_ratio == 3;
        int fps_code = dr_frame_rate_code(&pgc->playback_time);

        if (!first) dr_buf_printf(&b, ",");
        first = 0;
        dr_buf_printf(&b,
            "{\"index\":%d,\"vts\":%d,\"ttn\":%d,\"angles\":%d,\"chapters\":%d,\"cells\":%d,"
            "\"durationMs\":%lld,\"sectors\":%lld,\"cssKeyMissing\":%s,"
            "\"video\":{\"width\":%d,\"height\":%d,\"standard\":\"%s\",\"aspect\":\"%s\","
            "\"letterboxed\":%s,\"filmMode\":%s,\"fps\":%s},",
            t, L.vts, L.ttn, L.angles, L.chapter_count, L.cell_count,
            (long long)(L.duration90 / 90), (long long)L.sectors,
            disc->css_failed_vts[L.vts] ? "true" : "false",
            width, height, pal ? "PAL" : "NTSC", wide ? "16:9" : "4:3",
            va->letterboxed ? "true" : "false", va->film_mode ? "true" : "false",
            fps_code == 3 ? "29.97" : "25");

        dr_buf_printf(&b, "\"chapterStartsMs\":[");
        int cf = 1;
        for (int i = 0; i < L.cell_count; i++) {
            if (!L.cells[i].chapter_start) continue;
            dr_buf_printf(&b, "%s%lld", cf ? "" : ",", (long long)(L.cells[i].start90 / 90));
            cf = 0;
        }
        dr_buf_printf(&b, "],\"audio\":[");
        int af = 1;
        for (int i = 0; i < m->nr_of_vts_audio_streams && i < 8; i++) {
            uint16_t ctl = pgc->audio_control[i];
            if (!(ctl & 0x8000)) continue;
            int phys = (ctl >> 8) & 0x07;
            audio_attr_t *aa = &m->vts_audio_attr[i];
            int sid = dr_audio_stream_id(aa->audio_format, phys);
            if (sid < 0) continue;
            char lang[3];
            lang_string(aa->lang_type == 1 ? aa->lang_code : 0, lang);
            int bits = aa->audio_format == 4 ? (aa->quantization == 0 ? 16 : aa->quantization == 1 ? 20 : 24) : 0;
            dr_buf_printf(&b, "%s{\"streamId\":%d,\"logical\":%d,\"format\":\"%s\",\"channels\":%d,"
                              "\"sampleRate\":%d,\"bits\":%d,\"lang\":\"%s\",\"codeExtension\":%d}",
                          af ? "" : ",", sid, i, audio_format_name(aa->audio_format), aa->channels + 1,
                          aa->sample_frequency == 1 ? 96000 : 48000, bits, lang, aa->code_extension);
            af = 0;
        }
        dr_buf_printf(&b, "],\"subtitles\":[");
        int sf = 1;
        int seen[32] = {0};
        for (int i = 0; i < m->nr_of_vts_subp_streams && i < 32; i++) {
            int phys = dr_subp_physical(pgc, i, wide);
            if (phys < 0 || phys > 31 || seen[phys]) continue;
            seen[phys] = 1;
            subp_attr_t *sa = &m->vts_subp_attr[i];
            char lang[3];
            lang_string(sa->type == 1 ? sa->lang_code : 0, lang);
            dr_buf_printf(&b, "%s{\"streamId\":%d,\"logical\":%d,\"lang\":\"%s\",\"codeExtension\":%d}",
                          sf ? "" : ",", 0x20 + phys, i, lang, sa->code_extension);
            sf = 0;
        }
        dr_buf_printf(&b, "],\"palette\":[");
        for (int i = 0; i < 16; i++) {
            int r, g, bl;
            ycrcb_to_rgb(pgc->palette[i], &r, &g, &bl);
            dr_buf_printf(&b, "%s\"%02x%02x%02x\"", i ? "," : "", r, g, bl);
        }
        dr_buf_printf(&b, "]}");
        dr_title_layout_free(&L);
    }
    dr_buf_printf(&b, "]}");
    return b.data;
}

int dr_drive_region_state(const char *raw_device_path, int *region_mask, int *changes_left,
                          int *rpc_scheme, int *type_code) {
    int fd = open(raw_device_path, O_RDONLY | O_NONBLOCK);
    if (fd < 0) return -1;
    DVDRegionPlaybackControlInfo info;
    memset(&info, 0, sizeof info);
    dk_dvd_report_key_t key;
    memset(&key, 0, sizeof key);
    key.format = kDVDKeyFormatRegionState;
    key.keyClass = kDVDKeyClassCSS_CPPM_CPRM;
    key.bufferLength = sizeof info;
    key.buffer = &info;
    int rc = ioctl(fd, DKIOCDVDREPORTKEY, &key);
    close(fd);
    if (rc < 0) return -1;
    if (region_mask) *region_mask = info.driveRegion;
    if (changes_left) *changes_left = info.numberUserResets;
    if (rpc_scheme) *rpc_scheme = info.rpcScheme;
    if (type_code) *type_code = info.typeCode;
    return 0;
}

int dr_hw_encoders_available(void) {
    int mask = 0;
    const char *names[2] = { "hevc_videotoolbox", "h264_videotoolbox" };
    for (int i = 0; i < 2; i++) {
        const AVCodec *codec = avcodec_find_encoder_by_name(names[i]);
        if (!codec) continue;
        AVCodecContext *c = avcodec_alloc_context3(codec);
        if (!c) continue;
        c->width = 720;
        c->height = 576;
        c->pix_fmt = AV_PIX_FMT_NV12;
        c->time_base = (AVRational){1, 25};
        c->framerate = (AVRational){25, 1};
        c->bit_rate = 2000000;
        if (avcodec_open2(c, codec, NULL) == 0) mask |= 1 << i;
        avcodec_free_context(&c);
    }
    return mask;
}
