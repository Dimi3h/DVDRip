#include "dr_source.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/uio.h>

struct dr_dvd {
    dvd_reader_t *reader;
    dr_block_source source;
    uint64_t position; /* bytes */
    dr_logger logger;
    dvd_logger_cb log_cb;
    dvd_reader_stream_cb stream_cb;
};

void dr_css_configure(const char *cache_dir) {
    /* Apps cannot send SCSI commands through libdvdcss, so it never sees the drive: title keys
       are recovered from the scrambled sectors, as with an image. */
    setenv("DVDCSS_METHOD", "title", 1);
    if (cache_dir != NULL && cache_dir[0] != '\0') setenv("DVDCSS_CACHE", cache_dir, 1);
}

static void on_dvdread_log(void *priv, dvd_logger_level_t level, const char *format, va_list args) {
    dr_dvd *dvd = priv;
    int ours = DR_LOG_DEBUG;
    switch (level) {
        case DVD_LOGGER_LEVEL_ERROR: ours = DR_LOG_ERROR; break;
        case DVD_LOGGER_LEVEL_WARN: ours = DR_LOG_WARN; break;
        case DVD_LOGGER_LEVEL_INFO: ours = DR_LOG_INFO; break;
        default: ours = DR_LOG_DEBUG; break;
    }
    dr_vlogf(&dvd->logger, ours, format, args);
}

static int stream_seek(void *priv, uint64_t position) {
    dr_dvd *dvd = priv;
    if (position % DR_BLOCK_SIZE != 0) return -1;
    dvd->position = position;
    return 0;
}

static int stream_read(void *priv, void *buffer, int size) {
    dr_dvd *dvd = priv;
    if (size < 0 || size % DR_BLOCK_SIZE != 0) return -1;
    int blocks = dvd->source.read_blocks(dvd->source.opaque, dvd->position / DR_BLOCK_SIZE,
                                         size / DR_BLOCK_SIZE, buffer);
    if (blocks < 0) return -1;
    dvd->position += (uint64_t) blocks * DR_BLOCK_SIZE;
    return blocks * DR_BLOCK_SIZE;
}

static int stream_readv(void *priv, void *iovec, int blocks) {
    struct iovec *parts = iovec;
    int total = 0;
    int wanted = blocks * DR_BLOCK_SIZE;
    for (int i = 0; total < wanted; i++) {
        int size = (int) parts[i].iov_len;
        if (size > wanted - total) size = wanted - total;
        int read = stream_read(priv, parts[i].iov_base, size);
        if (read < 0) return total > 0 ? total : -1;
        total += read;
        if (read < size) break;
    }
    return total;
}

static dr_dvd *new_dvd(const dr_logger *logger) {
    dr_dvd *dvd = calloc(1, sizeof(dr_dvd));
    if (dvd == NULL) return NULL;
    if (logger != NULL) dvd->logger = *logger;
    dvd->log_cb.pf_log = on_dvdread_log;
    return dvd;
}

dr_dvd *dr_dvd_open_blocks(const dr_block_source *source, const dr_logger *logger, char *error, size_t error_size) {
    dr_dvd *dvd = new_dvd(logger);
    if (dvd == NULL) {
        dr_set_error(error, error_size, "out of memory");
        return NULL;
    }
    dvd->source = *source;
    dvd->stream_cb.pf_seek = stream_seek;
    dvd->stream_cb.pf_read = stream_read;
    dvd->stream_cb.pf_readv = stream_readv;
    dvd->reader = DVDOpenStream2(dvd, &dvd->log_cb, &dvd->stream_cb);
    if (dvd->reader == NULL) {
        dr_set_error(error, error_size, "libdvdread could not open the disc");
        free(dvd);
        return NULL;
    }
    return dvd;
}

dr_dvd *dr_dvd_open_files(dvd_reader_filesystem_h *fs, const dr_logger *logger, char *error, size_t error_size) {
    dr_dvd *dvd = new_dvd(logger);
    if (dvd == NULL) {
        dr_set_error(error, error_size, "out of memory");
        return NULL;
    }
    dvd->reader = DVDOpenFiles(dvd, &dvd->log_cb, "/", fs);
    if (dvd->reader == NULL) {
        dr_set_error(error, error_size, "libdvdread found no DVD-Video files in the folder");
        free(dvd);
        return NULL;
    }
    return dvd;
}

dvd_reader_t *dr_dvd_reader(const dr_dvd *dvd) {
    return dvd->reader;
}

void dr_dvd_close(dr_dvd *dvd) {
    if (dvd == NULL) return;
    if (dvd->reader != NULL) DVDClose(dvd->reader);
    free(dvd);
}
