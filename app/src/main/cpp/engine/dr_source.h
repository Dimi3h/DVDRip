/*
 * Opening a DVD for libdvdread: either as a block device (a USB drive or an ISO image, read
 * through callbacks so the data can come from Kotlin) or as a VIDEO_TS file tree reached through
 * filesystem callbacks (a folder picked with the Storage Access Framework).
 */
#ifndef DR_SOURCE_H
#define DR_SOURCE_H

#include "dr_common.h"

#include <dvdread/dvd_filesystem.h>
#include <dvdread/dvd_reader.h>

typedef struct dr_block_source {
    void *opaque;
    /* Reads count 2048-byte blocks starting at lba. Returns the number of blocks read, fewer
       only at the end of the medium, or -1 when they cannot be read. */
    int (*read_blocks)(void *opaque, uint64_t lba, int count, uint8_t *buffer);
} dr_block_source;

typedef struct dr_dvd dr_dvd;

/* Sets up libdvdcss for stream use: title keys are recovered from the disc (DVDCSS_METHOD=title)
   and cached in cache_dir. Call once before opening discs. */
void dr_css_configure(const char *cache_dir);

/* Opens a disc or image through block reads; CSS is handled by libdvdcss. */
dr_dvd *dr_dvd_open_blocks(const dr_block_source *source, const dr_logger *logger, char *error, size_t error_size);

/* Opens unencrypted VIDEO_TS files through fs, rooted at "/". Takes ownership of fs on success. */
dr_dvd *dr_dvd_open_files(dvd_reader_filesystem_h *fs, const dr_logger *logger, char *error, size_t error_size);

dvd_reader_t *dr_dvd_reader(const dr_dvd *dvd);

void dr_dvd_close(dr_dvd *dvd);

#endif
