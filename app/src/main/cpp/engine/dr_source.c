#include "dr_source.h"

#include <dvdcss/dvdcss.h>
#include <dvdread/dvd_udf.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/uio.h>

/* Attempts at recovering a title key, each starting past the block that stopped the one before. */
#define KEY_ATTEMPTS 8

/* A read position on the block source: libdvdread and the engine's libdvdcss each keep their own. */
typedef struct block_stream {
    struct dr_dvd *dvd;
    uint64_t position; /* bytes */
} block_stream;

struct dr_dvd {
    dvd_reader_t *reader;
    dr_block_source source;
    dr_logger logger;
    dvd_logger_cb log_cb;
    block_stream dvdread_stream;
    dvd_reader_stream_cb dvdread_cb;

    /* Discs and images: the libdvdcss instance that decrypts title VOBs (see dr_vobs_read). */
    dvdcss_t css;
    block_stream css_stream;
    dvdcss_stream_cb css_cb;

    /* While a title key is being recovered: the rip's cancel check, and the last block that
       stayed unreadable. */
    int recovering;
    int (*cancelled)(void *opaque);
    void *cancel_opaque;
    int64_t unreadable;
};

struct dr_vobs {
    dr_dvd *dvd;
    int (*cancelled)(void *opaque);
    void *opaque;
    /* VIDEO_TS folders: libdvdread reads the files. */
    dvd_file_t *file;
    /* Discs and images: the first block of VTS_nn_1.VOB, and how far CSS has come. */
    uint32_t start;
    int keyed;     /* a title key was recovered; reads decrypt */
    int recovered; /* recovery was tried; it is not tried twice */
};

void dr_css_configure(void) {
    /* Apps cannot send SCSI commands through libdvdcss, so it never sees the drive: title keys
       are cracked from the scrambled sectors, as with an image. Its disk cache stays off: without
       the disc key, libdvdcss names a disc's cache after its volume label and date only, which
       discs of a box set can share, and a decrypted copy shares with its original. A key from
       the wrong disc decrypts to garbage that nothing notices. */
    setenv("DVDCSS_METHOD", "title", 1);
    setenv("DVDCSS_CACHE", "off", 1);
    /* libdvdread would crack every title set's key when the first VOB is opened; the engine
       decrypts title VOBs itself, one title set at a time. */
    setenv("DVDREAD_NOKEYS", "1", 1);
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

static int stream_seek(block_stream *stream, uint64_t position) {
    if (position % DR_BLOCK_SIZE != 0) return -1;
    stream->position = position;
    return 0;
}

static int stream_read(block_stream *stream, void *buffer, int size) {
    if (size < 0 || size % DR_BLOCK_SIZE != 0) return -1;
    dr_dvd *dvd = stream->dvd;
    int blocks = dvd->source.read_blocks(dvd->source.opaque, stream->position / DR_BLOCK_SIZE,
                                         size / DR_BLOCK_SIZE, buffer);
    if (blocks < 0) return -1;
    stream->position += (uint64_t) blocks * DR_BLOCK_SIZE;
    return blocks * DR_BLOCK_SIZE;
}

static int stream_readv(block_stream *stream, int (*read)(void *, void *, int), void *priv,
                        const struct iovec *parts, int blocks) {
    (void) stream;
    int total = 0;
    int wanted = blocks * DR_BLOCK_SIZE;
    for (int i = 0; total < wanted; i++) {
        int size = (int) parts[i].iov_len;
        if (size > wanted - total) size = wanted - total;
        int got = read(priv, parts[i].iov_base, size);
        if (got < 0) return total > 0 ? total : -1;
        total += got;
        if (got < size) break;
    }
    return total;
}

/* libdvdread's stream: its handle is the dr_dvd, which its log callback needs too. */
static int dvdread_seek(void *priv, uint64_t position) {
    return stream_seek(&((dr_dvd *) priv)->dvdread_stream, position);
}

static int dvdread_read(void *priv, void *buffer, int size) {
    return stream_read(&((dr_dvd *) priv)->dvdread_stream, buffer, size);
}

static int dvdread_readv(void *priv, void *iovec, int blocks) {
    return stream_readv(&((dr_dvd *) priv)->dvdread_stream, dvdread_read, priv, iovec, blocks);
}

/* libdvdcss's stream. While it recovers a key it reads block by block, maybe through the whole
   title, so reads then poll the rip's cancel check. A block it cannot read ends its search (after
   one more try of its own); recover_key starts again past it rather than wear a scratched disc
   down: any other scrambled block may give the key away as well. */
static int css_seek(void *priv, uint64_t position) {
    return stream_seek(priv, position);
}

static int css_read(void *priv, void *buffer, int size) {
    block_stream *stream = priv;
    dr_dvd *dvd = stream->dvd;
    if (!dvd->recovering) return stream_read(stream, buffer, size);
    if (dvd->cancelled != NULL && dvd->cancelled(dvd->cancel_opaque)) return -1;
    int got = stream_read(stream, buffer, size);
    if (got < 0) dvd->unreadable = (int64_t) (stream->position / DR_BLOCK_SIZE);
    return got;
}

static int css_readv(void *priv, const void *iovec, int blocks) {
    return stream_readv(priv, css_read, priv, iovec, blocks);
}

static dr_dvd *new_dvd(const dr_logger *logger) {
    dr_dvd *dvd = calloc(1, sizeof(dr_dvd));
    if (dvd == NULL) return NULL;
    if (logger != NULL) dvd->logger = *logger;
    dvd->log_cb.pf_log = on_dvdread_log;
    dvd->dvdread_stream.dvd = dvd;
    dvd->css_stream.dvd = dvd;
    return dvd;
}

dr_dvd *dr_dvd_open_blocks(const dr_block_source *source, const dr_logger *logger, char *error, size_t error_size) {
    dr_dvd *dvd = new_dvd(logger);
    if (dvd == NULL) {
        dr_set_error(error, error_size, "out of memory");
        return NULL;
    }
    dvd->source = *source;
    dvd->css_cb.pf_seek = css_seek;
    dvd->css_cb.pf_read = css_read;
    dvd->css_cb.pf_readv = css_readv;
    dvd->css = dvdcss_open_stream(&dvd->css_stream, &dvd->css_cb);
    if (dvd->css == NULL) {
        dr_set_error(error, error_size, "libdvdcss could not open the disc");
        free(dvd);
        return NULL;
    }
    dvd->dvdread_cb.pf_seek = dvdread_seek;
    dvd->dvdread_cb.pf_read = dvdread_read;
    dvd->dvdread_cb.pf_readv = dvdread_readv;
    dvd->reader = DVDOpenStream2(dvd, &dvd->log_cb, &dvd->dvdread_cb);
    if (dvd->reader == NULL) {
        dr_set_error(error, error_size, "libdvdread could not open the disc");
        dvdcss_close(dvd->css);
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
    if (dvd->css != NULL) dvdcss_close(dvd->css);
    free(dvd);
}

dr_vobs *dr_vobs_open(dr_dvd *dvd, int title_set, int (*cancelled)(void *opaque), void *opaque) {
    dr_vobs *vobs = calloc(1, sizeof(dr_vobs));
    if (vobs == NULL) return NULL;
    vobs->dvd = dvd;
    vobs->cancelled = cancelled;
    vobs->opaque = opaque;
    if (dvd->css == NULL) {
        vobs->file = DVDOpenFile(dvd->reader, title_set, DVD_READ_TITLE_VOBS);
        if (vobs->file == NULL) {
            free(vobs);
            return NULL;
        }
        return vobs;
    }
    char name[32];
    uint32_t size = 0;
    snprintf(name, sizeof(name), "/VIDEO_TS/VTS_%02d_1.VOB", title_set);
    vobs->start = UDFFindFile(dvd->reader, name, &size);
    if (vobs->start == 0 || size == 0) {
        free(vobs);
        return NULL;
    }
    return vobs;
}

/* A block libdvdcss decrypts: a pack whose first packet has its scrambling control set (as CSS
   marks them; the system header, padding and private stream 2 have no such field). */
static int is_scrambled(const uint8_t *block) {
    if (block[0] != 0 || block[1] != 0 || block[2] != 1 || block[3] != 0xBA) return 0;
    if (block[0x11] == 0xBB || block[0x11] == 0xBE || block[0x11] == 0xBF) return 0;
    return (block[0x14] & 0x30) != 0;
}

static int first_scrambled(const uint8_t *blocks, int count) {
    for (int i = 0; i < count; i++) {
        if (is_scrambled(blocks + (size_t) i * DR_BLOCK_SIZE)) return i;
    }
    return -1;
}

/*
 * Has libdvdcss recover the title key from the scrambled blocks at lba and on, which it keeps for
 * the rest of the session. Returns 1 when a key was found.
 */
static int recover_key(dr_vobs *vobs, int lba) {
    dr_dvd *dvd = vobs->dvd;
    dvd->recovering = 1;
    dvd->cancelled = vobs->cancelled;
    dvd->cancel_opaque = vobs->opaque;
    int found = 0;
    for (int attempt = 0; attempt < KEY_ATTEMPTS && !found; attempt++) {
        dr_logf(&dvd->logger, DR_LOG_INFO, "Recovering the CSS title key from block %d on", lba);
        dvd->unreadable = -1;
        if (dvdcss_seek(dvd->css, lba, DVDCSS_SEEK_KEY) == lba) {
            found = 1;
        } else if (dvd->unreadable >= lba && (vobs->cancelled == NULL || !vobs->cancelled(vobs->opaque))) {
            lba = (int) dvd->unreadable + 1;
        } else {
            break;
        }
    }
    dvd->recovering = 0;
    dvd->cancelled = NULL;
    dvd->cancel_opaque = NULL;
    dr_logf(&dvd->logger, found ? DR_LOG_INFO : DR_LOG_WARN, found ? "CSS title key found" : "No CSS title key found");
    return found;
}

static int read_css(dr_vobs *vobs, int lba, int count, uint8_t *out) {
    dr_dvd *dvd = vobs->dvd;
    if (dvdcss_seek(dvd->css, lba, DVDCSS_NOFLAGS) != lba) return -1;
    /* Without a key, libdvdcss's title key is unset (not zero), so nothing may be decrypted. */
    return dvdcss_read(dvd->css, out, count, vobs->keyed ? DVDCSS_READ_DECRYPT : DVDCSS_NOFLAGS);
}

int dr_vobs_read(dr_vobs *vobs, uint32_t block, int count, uint8_t *out) {
    if (vobs->file != NULL) return (int) DVDReadBlocks(vobs->file, (int) block, (size_t) count, out);
    int lba = (int) (vobs->start + block);
    int got = read_css(vobs, lba, count, out);
    if (got <= 0 || vobs->recovered) return got;
    int scrambled = first_scrambled(out, got);
    if (scrambled < 0) return got;
    vobs->recovered = 1;
    if (!recover_key(vobs, lba + scrambled)) return got;
    vobs->keyed = 1;
    return read_css(vobs, lba, count, out);
}

void dr_vobs_close(dr_vobs *vobs) {
    if (vobs == NULL) return;
    if (vobs->file != NULL) DVDCloseFile(vobs->file);
    free(vobs);
}
