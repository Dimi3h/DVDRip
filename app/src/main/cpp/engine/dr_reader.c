#include "dr_reader.h"

#include <dvdread/nav_read.h>
#include <dvdread/nav_types.h>
#include <stdlib.h>
#include <string.h>

#define CHUNK_BLOCKS 32
#define BUFFER_BLOCKS 256

typedef struct timeline_entry {
    int64_t position;
    int64_t offset;
} timeline_entry;

struct dr_reader {
    const dr_plan *plan;
    dr_vobs *vobs;
    dr_logger logger;
    int (*cancelled)(void *opaque);
    void *opaque;

    /* Where reading stands: cell index and next block; in interleaved cells also the VOBU being
       read and where the next one of this path starts. */
    int cell;
    uint32_t next_block;
    int cell_started;
    int in_vobu;
    uint32_t vobu_end;
    uint32_t after_vobu;

    /* Blocks read but not yet handed out. */
    uint8_t *buffer;
    int buffered;   /* bytes in buffer */
    int consumed;   /* bytes of buffer already handed out */
    int64_t produced; /* stream bytes handed out before buffer[0] */

    /* Timeline. */
    timeline_entry *timeline;
    int timeline_count;
    int timeline_capacity;
    int timeline_cursor;
    int have_vobu;
    int64_t offset;
    uint32_t last_end_ptm;
    int pending_chapter;
    int64_t *chapter_starts;

    uint64_t sectors_done;
    dr_bad_range *bad;
    int bad_count;
    int bad_capacity;
};

static uint32_t be32(const uint8_t *p) {
    return ((uint32_t) p[0] << 24) | ((uint32_t) p[1] << 16) | ((uint32_t) p[2] << 8) | p[3];
}

/* Offset of the first PES packet after the pack header, or -1 when this is no MPEG-2 pack. */
static int pes_start(const uint8_t *block) {
    if (block[0] != 0 || block[1] != 0 || block[2] != 1 || block[3] != 0xBA) return -1;
    if ((block[4] & 0xC0) != 0x40) return -1;
    return 14 + (block[13] & 0x07);
}

/* Navigation packs: pack header, system header, PCI (private stream 2, substream 0). */
static int is_nav_pack(const uint8_t *block) {
    int p = pes_start(block);
    if (p != 14) return 0;
    return block[14] == 0 && block[15] == 0 && block[16] == 1 && block[17] == 0xBB &&
           block[38] == 0 && block[39] == 0 && block[40] == 1 && block[41] == 0xBF && block[44] == 0;
}

/* A PES packet whose scrambling control is still set: it could not be decrypted. */
static int is_scrambled(const uint8_t *block) {
    int p = pes_start(block);
    if (p < 0 || p + 7 > DR_BLOCK_SIZE) return 0;
    if (block[p] != 0 || block[p + 1] != 0 || block[p + 2] != 1) return 0;
    uint8_t id = block[p + 3];
    int has_header = id == 0xBD || (id >= 0xC0 && id <= 0xEF);
    return has_header && (block[p + 6] & 0x30) != 0;
}

static int record_bad(dr_reader *reader, uint32_t block) {
    if (reader->bad_count > 0) {
        dr_bad_range *last = &reader->bad[reader->bad_count - 1];
        if (last->first + last->count == block) {
            last->count++;
            return 0;
        }
    }
    if (reader->bad_count == reader->bad_capacity) {
        int capacity = reader->bad_capacity ? reader->bad_capacity * 2 : 16;
        dr_bad_range *grown = realloc(reader->bad, sizeof(dr_bad_range) * (size_t) capacity);
        if (grown == NULL) return -1;
        reader->bad = grown;
        reader->bad_capacity = capacity;
    }
    reader->bad[reader->bad_count++] = (dr_bad_range) {block, 1};
    return 0;
}

static int add_timeline(dr_reader *reader, int64_t position, int64_t offset) {
    if (reader->timeline_count > 0 && reader->timeline[reader->timeline_count - 1].offset == offset) return 0;
    if (reader->timeline_count == reader->timeline_capacity) {
        int capacity = reader->timeline_capacity ? reader->timeline_capacity * 2 : 64;
        timeline_entry *grown = realloc(reader->timeline, sizeof(timeline_entry) * (size_t) capacity);
        if (grown == NULL) return -1;
        reader->timeline = grown;
        reader->timeline_capacity = capacity;
    }
    reader->timeline[reader->timeline_count++] = (timeline_entry) {position, offset};
    return 0;
}

/* Timing of a navigation pack at stream byte position. */
static int on_nav_pack(dr_reader *reader, const uint8_t *block, int64_t position) {
    uint32_t start = be32(block + 45 + 12); /* pci_gi.vobu_s_ptm */
    uint32_t end = be32(block + 45 + 16);   /* pci_gi.vobu_e_ptm */
    if (!reader->have_vobu) {
        reader->offset = -(int64_t) start;
        reader->have_vobu = 1;
    } else if (start != reader->last_end_ptm) {
        int64_t previous_end = (int64_t) reader->last_end_ptm + reader->offset;
        dr_logf(&reader->logger, DR_LOG_INFO, "Timestamp jump at byte %lld: %u follows %u",
                (long long) position, start, reader->last_end_ptm);
        reader->offset = previous_end - start;
    }
    reader->last_end_ptm = end;
    if (reader->pending_chapter > 0) {
        reader->chapter_starts[reader->pending_chapter - 1] = (int64_t) start + reader->offset;
        reader->pending_chapter = 0;
    }
    return add_timeline(reader, position, reader->offset);
}

/* Reads one block, trying DR_READ_RETRIES times; 1 when it was read, 0 when not, or a DR_READ_ code. */
static int read_single(dr_reader *reader, uint32_t block, uint8_t *target) {
    for (int attempt = 0; attempt < DR_READ_RETRIES; attempt++) {
        if (reader->cancelled != NULL && reader->cancelled(reader->opaque)) return DR_READ_CANCELLED;
        if (dr_vobs_read(reader->vobs, block, 1, target) == 1) return 1;
    }
    return 0;
}

/*
 * Reads count blocks at block into the buffer, block by block where a bulk read fails; blocks
 * that stay unreadable are left out and recorded. Returns blocks stored, or a DR_READ_ code.
 */
static int read_into_buffer(dr_reader *reader, uint32_t block, int count) {
    uint8_t *out = reader->buffer + reader->buffered;
    int stored = 0;
    int got = dr_vobs_read(reader->vobs, block, count, out);
    if (got == count) {
        stored = count;
    } else {
        for (int i = 0; i < count; i++) {
            int read = read_single(reader, block + (uint32_t) i, out + (size_t) stored * DR_BLOCK_SIZE);
            if (read < 0) return read;
            if (read == 1) {
                stored++;
            } else if (record_bad(reader, block + (uint32_t) i) < 0) {
                return DR_READ_FAILED;
            }
        }
        if (stored < count) {
            dr_logf(&reader->logger, DR_LOG_WARN, "Skipped %d unreadable blocks at %u", count - stored, block);
        }
    }
    reader->sectors_done += (uint64_t) count;
    for (int i = 0; i < stored; i++) {
        const uint8_t *data = out + (size_t) i * DR_BLOCK_SIZE;
        if (is_scrambled(data)) return DR_READ_SCRAMBLED;
        if (is_nav_pack(data)) {
            int64_t position = reader->produced + reader->buffered + (int64_t) i * DR_BLOCK_SIZE;
            if (on_nav_pack(reader, data, position) < 0) return DR_READ_FAILED;
        }
    }
    reader->buffered += stored * DR_BLOCK_SIZE;
    return stored;
}

/*
 * Next piece of an interleaved cell, following the DSI: at a VOBU start, its navigation pack (which
 * says where the VOBU ends and where the next one of this path starts); otherwise the next chunk
 * of the current VOBU. Returns blocks stored or a DR_READ_ code.
 */
static int read_interleaved(dr_reader *reader, const dr_plan_cell *cell) {
    if (reader->in_vobu) {
        if (reader->next_block > reader->vobu_end) {
            reader->in_vobu = 0;
            reader->next_block = reader->after_vobu;
            return 0;
        }
        int count = (int) (reader->vobu_end - reader->next_block + 1);
        if (count > CHUNK_BLOCKS) count = CHUNK_BLOCKS;
        int result = read_into_buffer(reader, reader->next_block, count);
        if (result >= 0) reader->next_block += (uint32_t) count;
        return result;
    }

    uint32_t nav_block = reader->next_block;
    int got = read_into_buffer(reader, nav_block, 1);
    if (got <= 0) {
        /* Without the navigation pack there is no way to the next VOBU of this path. */
        reader->next_block = cell->last_sector + 1;
        return got;
    }
    const uint8_t *nav = reader->buffer + reader->buffered - DR_BLOCK_SIZE;
    if (!is_nav_pack(nav)) {
        dr_logf(&reader->logger, DR_LOG_WARN, "No navigation pack at block %u; leaving the cell", nav_block);
        reader->next_block = cell->last_sector + 1;
        return got;
    }
    dsi_t dsi;
    navRead_DSI(&dsi, (unsigned char *) nav + DSI_START_BYTE);
    uint32_t vobu_end = nav_block + dsi.dsi_gi.vobu_ea;
    if (vobu_end > cell->last_sector) vobu_end = cell->last_sector;

    /* Inside an interleaved unit the VOBUs follow each other; after its last VOBU the next unit
       of this path starts at ilvu_sa, past the units of other angles or branches. */
    uint32_t after = vobu_end + 1;
    if (dsi.sml_pbi.category & 0x8000) {
        if (dsi.dsi_gi.vobu_ea >= dsi.sml_pbi.ilvu_ea) {
            int has_next = dsi.sml_pbi.ilvu_sa != 0 && dsi.sml_pbi.ilvu_sa != 0x7FFFFFFF;
            after = has_next ? nav_block + dsi.sml_pbi.ilvu_sa : cell->last_sector + 1;
        }
    } else if (dsi.vobu_sri.next_vobu == SRI_END_OF_CELL) {
        after = cell->last_sector + 1;
    }
    reader->in_vobu = 1;
    reader->vobu_end = vobu_end;
    reader->after_vobu = after;
    reader->next_block = nav_block + 1;
    return got;
}

/* Refills the empty buffer. Returns blocks stored (> 0), or a DR_READ_ code at the end or on failure. */
static int refill(dr_reader *reader) {
    reader->produced += reader->buffered;
    reader->buffered = 0;
    reader->consumed = 0;
    while (reader->cell < reader->plan->cell_count) {
        if (reader->cancelled != NULL && reader->cancelled(reader->opaque)) return DR_READ_CANCELLED;
        const dr_plan_cell *cell = &reader->plan->cells[reader->cell];
        if (!reader->cell_started) {
            reader->cell_started = 1;
            reader->in_vobu = 0;
            reader->next_block = cell->first_sector;
            if (cell->chapter > 0) reader->pending_chapter = cell->chapter;
        }
        if (reader->next_block > cell->last_sector && !reader->in_vobu) {
            reader->cell++;
            reader->cell_started = 0;
            continue;
        }
        int result;
        if (cell->interleaved) {
            result = read_interleaved(reader, cell);
        } else {
            int count = (int) (cell->last_sector - reader->next_block + 1);
            if (count > CHUNK_BLOCKS) count = CHUNK_BLOCKS;
            result = read_into_buffer(reader, reader->next_block, count);
            if (result >= 0) reader->next_block += (uint32_t) count;
        }
        if (result < 0) return result;
        if (result > 0) return result;
    }
    return DR_READ_END;
}

dr_reader *dr_reader_open(dr_dvd *dvd, const dr_plan *plan, const dr_logger *logger,
                          int (*cancelled)(void *opaque), void *opaque, char *error, size_t error_size) {
    dr_reader *reader = calloc(1, sizeof(dr_reader));
    if (reader == NULL) goto memory;
    reader->plan = plan;
    if (logger != NULL) reader->logger = *logger;
    reader->cancelled = cancelled;
    reader->opaque = opaque;
    reader->buffer = malloc((size_t) BUFFER_BLOCKS * DR_BLOCK_SIZE);
    reader->chapter_starts = malloc(sizeof(int64_t) * (size_t) (plan->chapter_count > 0 ? plan->chapter_count : 1));
    if (reader->buffer == NULL || reader->chapter_starts == NULL) goto memory;
    for (int i = 0; i < plan->chapter_count; i++) reader->chapter_starts[i] = -1;
    reader->vobs = dr_vobs_open(dvd, plan->title_set, cancelled, opaque);
    if (reader->vobs == NULL) {
        dr_set_error(error, error_size, "cannot open the VOB files of title set %d", plan->title_set);
        dr_reader_close(reader);
        return NULL;
    }
    return reader;

memory:
    dr_set_error(error, error_size, "out of memory");
    dr_reader_close(reader);
    return NULL;
}

int dr_reader_read(dr_reader *reader, uint8_t *buffer, int size) {
    if (reader->consumed >= reader->buffered) {
        int result = refill(reader);
        if (result <= 0) return result;
    }
    int available = reader->buffered - reader->consumed;
    int count = size < available ? size : available;
    memcpy(buffer, reader->buffer + reader->consumed, (size_t) count);
    reader->consumed += count;
    return count;
}

int64_t dr_reader_offset(dr_reader *reader, int64_t position) {
    if (reader->timeline_count == 0) return reader->offset;
    int i = reader->timeline_cursor;
    if (i >= reader->timeline_count) i = reader->timeline_count - 1;
    /* Positions mostly grow, so walk from the last answer. */
    while (i + 1 < reader->timeline_count && reader->timeline[i + 1].position <= position) i++;
    while (i > 0 && reader->timeline[i].position > position) i--;
    reader->timeline_cursor = i;
    return reader->timeline[i].offset;
}

int64_t dr_reader_chapter_start(const dr_reader *reader, int chapter) {
    if (chapter < 1 || chapter > reader->plan->chapter_count) return -1;
    return reader->chapter_starts[chapter - 1];
}

int64_t dr_reader_end_time(const dr_reader *reader) {
    return reader->have_vobu ? (int64_t) reader->last_end_ptm + reader->offset : 0;
}

uint64_t dr_reader_sectors_done(const dr_reader *reader) {
    return reader->sectors_done;
}

int dr_reader_bad_ranges(const dr_reader *reader, const dr_bad_range **ranges) {
    *ranges = reader->bad;
    return reader->bad_count;
}

void dr_reader_close(dr_reader *reader) {
    if (reader == NULL) return;
    dr_vobs_close(reader->vobs);
    free(reader->buffer);
    free(reader->timeline);
    free(reader->chapter_starts);
    free(reader->bad);
    free(reader);
}
