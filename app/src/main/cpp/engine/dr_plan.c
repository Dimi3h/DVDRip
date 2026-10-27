#include "dr_plan.h"

#include "dr_json.h"

#include <dvdread/ifo_read.h>
#include <stdlib.h>
#include <string.h>

static int bcd(uint8_t value) {
    return ((value >> 4) & 0x0F) * 10 + (value & 0x0F);
}

int64_t dr_dvd_time_ticks(const dvd_time_t *time) {
    int64_t seconds = (int64_t) bcd(time->hour) * 3600 + bcd(time->minute) * 60 + bcd(time->second);
    int frames = bcd(time->frame_u & 0x3F);
    /* 01b: 25 frames per second; 11b: 30 (29.97) frames per second. */
    int64_t frame_ticks = ((time->frame_u >> 6) == 1) ? 3600 : 3003;
    return seconds * DR_TICKS_PER_SECOND + frames * frame_ticks;
}

int dr_audio_stream_id(const audio_attr_t *attr, uint16_t control) {
    if (!(control & 0x8000)) return -1;
    int number = (control >> 8) & 0x07;
    switch (attr->audio_format) {
        case 0: return 0x80 + number;  /* AC-3 */
        case 2:
        case 3: return 0x1C0 + number; /* MPEG-1 / MPEG-2 audio */
        case 4: return 0xA0 + number;  /* LPCM */
        case 6: return 0x88 + number;  /* DTS */
        default: return -1;
    }
}

int dr_subtitle_stream_id(const video_attr_t *video, uint32_t control) {
    if (!(control & 0x80000000u)) return -1;
    /* 16:9 titles use the wide stream; 4:3 titles the 4:3 one. */
    int number = video->display_aspect_ratio == 3 ? (control >> 16) & 0x1F : (control >> 24) & 0x1F;
    return 0x20 + number;
}

/* Index of the PTT (0-based) that starts at program pgn of program chain pgcn, or -1. */
static int chapter_at(const ttu_t *ttu, int pgcn, int pgn) {
    for (int i = 0; i < ttu->nr_of_ptts; i++) {
        if (ttu->ptt[i].pgcn == pgcn && ttu->ptt[i].pgn == pgn) return i;
    }
    return -1;
}

static int title_uses_pgc(const ttu_t *ttu, int pgcn) {
    for (int i = 0; i < ttu->nr_of_ptts; i++) {
        if (ttu->ptt[i].pgcn == pgcn) return 1;
    }
    return 0;
}

/* Chapter starting at cell (1-based) of pgc, if any cell in [first, last] starts a program. */
static int chapter_in_cells(const ttu_t *ttu, const pgc_t *pgc, int pgcn, int first, int last) {
    for (int p = 1; p <= pgc->nr_of_programs; p++) {
        int cell = pgc->program_map[p - 1];
        if (cell >= first && cell <= last) {
            int ptt = chapter_at(ttu, pgcn, p);
            if (ptt >= 0) return ptt + 1;
        }
    }
    return 0;
}

static int append_cell(dr_plan *plan, int *capacity, const cell_playback_t *cell, int chapter) {
    if (plan->cell_count == *capacity) {
        int grown_capacity = *capacity ? *capacity * 2 : 32;
        dr_plan_cell *grown = realloc(plan->cells, sizeof(dr_plan_cell) * (size_t) grown_capacity);
        if (grown == NULL) return -1;
        plan->cells = grown;
        *capacity = grown_capacity;
    }
    dr_plan_cell *out = &plan->cells[plan->cell_count++];
    out->first_sector = cell->first_sector;
    out->last_sector = cell->last_sector;
    out->interleaved = cell->interleaved;
    out->chapter = chapter;
    out->duration = dr_dvd_time_ticks(&cell->playback_time);
    if (cell->last_sector >= cell->first_sector) {
        plan->sector_count += (uint64_t) cell->last_sector - cell->first_sector + 1;
    }
    plan->duration += out->duration;
    return 0;
}

int dr_plan_build(dvd_reader_t *dvd, ifo_handle_t *vmg, int title, int angle, dr_plan *plan,
                  char *error, size_t error_size) {
    memset(plan, 0, sizeof(*plan));
    if (vmg->tt_srpt == NULL || title < 1 || title > vmg->tt_srpt->nr_of_srpts) {
        dr_set_error(error, error_size, "no title %d on this disc", title);
        return -1;
    }
    const title_info_t *info = &vmg->tt_srpt->title[title - 1];
    plan->title = title;
    plan->title_set = info->title_set_nr;
    plan->angle = (angle >= 1 && angle <= info->nr_of_angles) ? angle : 1;

    plan->vts = ifoOpen(dvd, info->title_set_nr);
    if (plan->vts == NULL || plan->vts->vts_ptt_srpt == NULL || plan->vts->vts_pgcit == NULL) {
        dr_set_error(error, error_size, "cannot read the IFO of title set %d", info->title_set_nr);
        dr_plan_free(plan);
        return -1;
    }
    if (info->vts_ttn < 1 || info->vts_ttn > plan->vts->vts_ptt_srpt->nr_of_srpts) {
        dr_set_error(error, error_size, "title %d points outside its title set", title);
        dr_plan_free(plan);
        return -1;
    }
    const ttu_t *ttu = &plan->vts->vts_ptt_srpt->title[info->vts_ttn - 1];
    if (ttu->nr_of_ptts < 1) {
        dr_set_error(error, error_size, "title %d has no chapters", title);
        dr_plan_free(plan);
        return -1;
    }
    plan->chapter_count = ttu->nr_of_ptts;

    const pgcit_t *pgcit = plan->vts->vts_pgcit;
    int pgcn = ttu->ptt[0].pgcn;
    int start_cell = 0;
    int capacity = 0;
    /* A title is one program chain, or several linked by next_pgc_nr; follow the links but never
       around in a circle. */
    unsigned char visited[1024] = {0};
    while (pgcn >= 1 && pgcn <= pgcit->nr_of_pgci_srp && pgcn < 1024 && !visited[pgcn]) {
        visited[pgcn] = 1;
        pgc_t *pgc = pgcit->pgci_srp[pgcn - 1].pgc;
        if (pgc == NULL || pgc->cell_playback == NULL || pgc->program_map == NULL) break;
        if (plan->pgc == NULL) plan->pgc = pgc;
        if (start_cell == 0) {
            int first_program = ttu->ptt[0].pgn;
            start_cell = (first_program >= 1 && first_program <= pgc->nr_of_programs) ? pgc->program_map[first_program - 1] : 1;
        }
        for (int c = start_cell; c <= pgc->nr_of_cells; c++) {
            const cell_playback_t *cell = &pgc->cell_playback[c - 1];
            if (cell->block_type == BLOCK_TYPE_ANGLE_BLOCK && cell->block_mode == BLOCK_MODE_FIRST_CELL) {
                int last = c;
                while (last < pgc->nr_of_cells && pgc->cell_playback[last].block_mode != BLOCK_MODE_LAST_CELL) last++;
                if (last < pgc->nr_of_cells) last++; /* include the last cell of the block */
                int chosen = c + plan->angle - 1;
                if (chosen > last) chosen = c;
                int chapter = chapter_in_cells(ttu, pgc, pgcn, c, last);
                if (append_cell(plan, &capacity, &pgc->cell_playback[chosen - 1], chapter) < 0) goto memory;
                c = last;
                continue;
            }
            if (cell->block_type == BLOCK_TYPE_ANGLE_BLOCK) continue; /* stray angle cell */
            if (append_cell(plan, &capacity, cell, chapter_in_cells(ttu, pgc, pgcn, c, c)) < 0) goto memory;
        }
        int next = pgc->next_pgc_nr;
        start_cell = 1;
        pgcn = (next != 0 && title_uses_pgc(ttu, next)) ? next : 0;
    }
    if (plan->cell_count == 0) {
        dr_set_error(error, error_size, "title %d has no playable cells", title);
        dr_plan_free(plan);
        return -1;
    }
    /* Playback starts at the first chapter even when the IFO does not mark its cell. */
    if (plan->cells[0].chapter == 0) plan->cells[0].chapter = 1;
    return 0;

memory:
    dr_set_error(error, error_size, "out of memory");
    dr_plan_free(plan);
    return -1;
}

void dr_plan_free(dr_plan *plan) {
    free(plan->cells);
    if (plan->vts != NULL) ifoClose(plan->vts);
    memset(plan, 0, sizeof(*plan));
}

/* Lower case, as ISO 639-1 is written; some discs store the code in upper case. */
static char lower(int c) {
    return (char) (c >= 'A' && c <= 'Z' ? c - 'A' + 'a' : c);
}

static void language(dr_json *json, uint16_t code, int valid) {
    char text[3] = {lower(code >> 8), lower(code & 0xFF), 0};
    if (valid && text[0] >= 'a' && text[0] <= 'z' && text[1] >= 'a' && text[1] <= 'z') {
        dr_json_string(json, text);
    } else {
        dr_json_null(json);
    }
}

static const char *audio_codec(int format) {
    switch (format) {
        case 0: return "ac3";
        case 2: return "mp2";
        case 3: return "mp2ext";
        case 4: return "lpcm";
        case 6: return "dts";
        default: return "unknown";
    }
}

static const char *audio_extension(int code) {
    switch (code) {
        case 1: return "normal";
        case 2: return "visually_impaired";
        case 3: return "commentary";
        case 4: return "alternate_commentary";
        default: return "unspecified";
    }
}

static const char *subtitle_extension(int code) {
    switch (code) {
        case 1: return "normal";
        case 2: return "large";
        case 3: return "children";
        case 5: return "captions";
        case 6: return "large_captions";
        case 7: return "children_captions";
        case 9: return "forced";
        case 13: return "commentary";
        case 14: return "large_commentary";
        case 15: return "children_commentary";
        default: return "unspecified";
    }
}

static void title_json(dr_json *json, const dr_plan *plan, const title_info_t *info) {
    const vtsi_mat_t *mat = plan->vts->vtsi_mat;
    const video_attr_t *video = &mat->vts_video_attr;
    const pgc_t *pgc = plan->pgc;
    int pal = video->video_format == 1;
    int heights[4] = {pal ? 576 : 480, pal ? 576 : 480, pal ? 576 : 480, pal ? 288 : 240};
    int widths[4] = {720, 704, 352, 352};

    dr_json_begin_object(json);
    dr_json_key(json, "number"); dr_json_int(json, plan->title);
    dr_json_key(json, "titleSet"); dr_json_int(json, plan->title_set);
    dr_json_key(json, "angles"); dr_json_int(json, info->nr_of_angles);
    dr_json_key(json, "chapters"); dr_json_int(json, plan->chapter_count);
    dr_json_key(json, "cells"); dr_json_int(json, plan->cell_count);
    dr_json_key(json, "sectors"); dr_json_int(json, (long long) plan->sector_count);
    dr_json_key(json, "durationMs"); dr_json_int(json, plan->duration / 90);

    dr_json_key(json, "video");
    dr_json_begin_object(json);
    dr_json_key(json, "mpegVersion"); dr_json_int(json, video->mpeg_version == 0 ? 1 : 2);
    dr_json_key(json, "standard"); dr_json_string(json, pal ? "PAL" : "NTSC");
    dr_json_key(json, "aspect"); dr_json_string(json, video->display_aspect_ratio == 3 ? "16:9" : "4:3");
    dr_json_key(json, "width"); dr_json_int(json, widths[video->picture_size & 3]);
    dr_json_key(json, "height"); dr_json_int(json, heights[video->picture_size & 3]);
    dr_json_key(json, "letterboxed"); dr_json_bool(json, video->letterboxed);
    dr_json_key(json, "film"); dr_json_bool(json, video->film_mode);
    dr_json_end_object(json);

    dr_json_key(json, "audio");
    dr_json_begin_array(json);
    for (int i = 0; i < mat->nr_of_vts_audio_streams && i < 8; i++) {
        const audio_attr_t *attr = &mat->vts_audio_attr[i];
        int id = dr_audio_stream_id(attr, pgc->audio_control[i]);
        if (id < 0) continue;
        int bits[4] = {16, 20, 24, 0};
        dr_json_begin_object(json);
        dr_json_key(json, "index"); dr_json_int(json, i);
        dr_json_key(json, "streamId"); dr_json_int(json, id);
        dr_json_key(json, "codec"); dr_json_string(json, audio_codec(attr->audio_format));
        dr_json_key(json, "language"); language(json, attr->lang_code, attr->lang_type == 1);
        dr_json_key(json, "channels"); dr_json_int(json, attr->channels + 1);
        dr_json_key(json, "sampleRate"); dr_json_int(json, attr->sample_frequency == 1 ? 96000 : 48000);
        dr_json_key(json, "bitsPerSample"); dr_json_int(json, attr->audio_format == 4 ? bits[attr->quantization & 3] : 0);
        dr_json_key(json, "extension"); dr_json_string(json, audio_extension(attr->code_extension));
        dr_json_end_object(json);
    }
    dr_json_end_array(json);

    dr_json_key(json, "subtitles");
    dr_json_begin_array(json);
    for (int i = 0; i < mat->nr_of_vts_subp_streams && i < 32; i++) {
        const subp_attr_t *attr = &mat->vts_subp_attr[i];
        int id = dr_subtitle_stream_id(video, pgc->subp_control[i]);
        if (id < 0) continue;
        dr_json_begin_object(json);
        dr_json_key(json, "index"); dr_json_int(json, i);
        dr_json_key(json, "streamId"); dr_json_int(json, id);
        dr_json_key(json, "language"); language(json, attr->lang_code, attr->type == 1);
        dr_json_key(json, "extension"); dr_json_string(json, subtitle_extension(attr->code_extension));
        dr_json_end_object(json);
    }
    dr_json_end_array(json);

    dr_json_key(json, "chapterStartsMs");
    dr_json_begin_array(json);
    int64_t time = 0;
    for (int i = 0; i < plan->cell_count; i++) {
        if (plan->cells[i].chapter > 0) dr_json_int(json, time / 90);
        time += plan->cells[i].duration;
    }
    dr_json_end_array(json);
    dr_json_end_object(json);
}

char *dr_titles_json(dvd_reader_t *dvd, const dr_logger *logger, char *error, size_t error_size) {
    ifo_handle_t *vmg = ifoOpen(dvd, 0);
    if (vmg == NULL || vmg->tt_srpt == NULL) {
        dr_set_error(error, error_size, "cannot read VIDEO_TS.IFO");
        if (vmg != NULL) ifoClose(vmg);
        return NULL;
    }
    dr_json json;
    dr_json_init(&json);
    dr_json_begin_object(&json);
    dr_json_key(&json, "titles");
    dr_json_begin_array(&json);
    for (int t = 1; t <= vmg->tt_srpt->nr_of_srpts; t++) {
        dr_plan plan;
        char reason[256];
        if (dr_plan_build(dvd, vmg, t, 1, &plan, reason, sizeof(reason)) < 0) {
            /* Protection schemes add broken titles; list the rest. */
            dr_logf(logger, DR_LOG_WARN, "Skipping title %d: %s", t, reason);
            continue;
        }
        title_json(&json, &plan, &vmg->tt_srpt->title[t - 1]);
        dr_plan_free(&plan);
    }
    dr_json_end_array(&json);
    dr_json_end_object(&json);
    ifoClose(vmg);
    char *text = dr_json_take(&json);
    if (text == NULL) dr_set_error(error, error_size, "out of memory");
    return text;
}
