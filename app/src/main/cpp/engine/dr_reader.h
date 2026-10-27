/*
 * Reads a planned title as one MPEG program stream, as FFmpeg's demuxer wants it, and keeps the
 * timeline straight: each VOBU starts with a navigation pack whose PCI says when its video starts
 * and ends. Where the next VOBU does not start where the previous one ended (a new VOB with its
 * own clock, a seamless branch), the reader records an offset that lines the timestamps up again.
 * Where a bulk read fails, blocks are read one at a time, each up to DR_READ_RETRIES times;
 * blocks that stay unreadable are skipped and reported. CSS-scrambled blocks that could not be
 * decrypted stop the title.
 */
#ifndef DR_READER_H
#define DR_READER_H

#include "dr_plan.h"
#include "dr_source.h"

#define DR_READ_RETRIES 3

enum {
    DR_READ_END = 0,
    DR_READ_CANCELLED = -1,
    DR_READ_SCRAMBLED = -2,
    DR_READ_FAILED = -3,
};

typedef struct dr_bad_range {
    uint32_t first;
    uint32_t count;
} dr_bad_range;

typedef struct dr_reader dr_reader;

dr_reader *dr_reader_open(dr_dvd *dvd, const dr_plan *plan, const dr_logger *logger,
                          int (*cancelled)(void *opaque), void *opaque, char *error, size_t error_size);

/* Fills buffer with up to size bytes; returns the count, or one of the DR_READ_ codes. */
int dr_reader_read(dr_reader *reader, uint8_t *buffer, int size);

/* The offset, in 90 kHz ticks, to add to timestamps read at stream byte position. */
int64_t dr_reader_offset(dr_reader *reader, int64_t position);

/* Output time of a 1-based chapter's first VOBU in 90 kHz ticks, or -1 when not reached. */
int64_t dr_reader_chapter_start(const dr_reader *reader, int chapter);

/* Output time at which the last VOBU read so far ends, in 90 kHz ticks. */
int64_t dr_reader_end_time(const dr_reader *reader);

uint64_t dr_reader_sectors_done(const dr_reader *reader);

int dr_reader_bad_ranges(const dr_reader *reader, const dr_bad_range **ranges);

void dr_reader_close(dr_reader *reader);

#endif
