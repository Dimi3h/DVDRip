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

/* Sets up libdvdcss for stream use: title keys are cracked from the scrambled sectors
   (DVDCSS_METHOD=title) and not cached on disk. Call once before opening discs. */
void dr_css_configure(void);

/* Opens a disc or image through block reads. CSS-scrambled title VOBs are decrypted by the
   engine's own libdvdcss instance (see dr_vobs_read). */
dr_dvd *dr_dvd_open_blocks(const dr_block_source *source, const dr_logger *logger, char *error, size_t error_size);

/* Opens unencrypted VIDEO_TS files through fs, rooted at "/". Takes ownership of fs on success. */
dr_dvd *dr_dvd_open_files(dvd_reader_filesystem_h *fs, const dr_logger *logger, char *error, size_t error_size);

dvd_reader_t *dr_dvd_reader(const dr_dvd *dvd);

void dr_dvd_close(dr_dvd *dvd);

/* The title VOBs of a title set (VTS_nn_1.VOB and on), read as one run of blocks. */
typedef struct dr_vobs dr_vobs;

/* Opens title_set's title VOBs; NULL when they are missing. cancelled is polled while a title
   key is being recovered, which can take long when there is none to find. */
dr_vobs *dr_vobs_open(dr_dvd *dvd, int title_set, int (*cancelled)(void *opaque), void *opaque);

/*
 * Reads count blocks from block on, counted from the start of the title VOBs. Returns the number
 * read (fewer only at the end) or -1. On a disc or image, the first CSS-scrambled block read makes
 * libdvdcss recover the title key (DVDCSS_METHOD=title) and blocks come back decrypted from then
 * on; when no key can be recovered they come back still scrambled, for the caller to stop at.
 */
int dr_vobs_read(dr_vobs *vobs, uint32_t block, int count, uint8_t *out);

void dr_vobs_close(dr_vobs *vobs);

#endif
