/*
 * Command-line driver of the rip engine for the build machine, so ripping can be tested without
 * a phone. It reaches the disc the way the app does: an image through block-read callbacks, a
 * VIDEO_TS folder through filesystem callbacks.
 *
 *   discrip_host probe <file.mkv> [--callbacks]
 *   discrip_host titles <image.iso | --dir folder>
 *   discrip_host rip <image.iso | --dir folder> <title> <out.mkv> [--audio id[:lang[:name]],...]
 *                [--default-audio id] [--flac] [--movie-title text] [--date yyyy] [--cancel-after blocks]
 *                [--subs id[:lang[:name]],...] [--forced-sub id] [--tag KEY=value]... [--bad-blocks lba:count]
 *                [--callbacks] [--fail-write-at bytes[:nospace]]
 *
 * --callbacks writes and reads the MKV the way the app reaches the NAS: through callbacks, which
 * here return short reads; --fail-write-at makes them fail once the file would pass that size.
 *
 * Output is the engine's JSON on stdout; progress and log lines go to stderr.
 */
#include "dr_fd.h"
#include "dr_probe.h"
#include "dr_rip.h"
#include "dr_source.h"
#include "dr_plan.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

typedef struct image {
    dr_fd_image fd_image;
    uint64_t blocks_read;
    uint64_t cancel_after;
    /* Blocks that fail to read, for testing (--bad-blocks), and how often each was tried. */
    uint64_t bad_first;
    uint64_t bad_count;
    int bad_reads[64];
} image;

static void log_line(void *opaque, int level, const char *message) {
    (void) opaque;
    static const char *names[] = {"debug", "info", "warn", "error"};
    if (level == DR_LOG_DEBUG && getenv("DISCRIP_DEBUG") == NULL) return;
    size_t length = strlen(message);
    fprintf(stderr, "[%s] %s%s", names[level], message, length > 0 && message[length - 1] == '\n' ? "" : "\n");
}

static const dr_logger logger = {NULL, log_line};

static int read_blocks(void *opaque, uint64_t lba, int count, uint8_t *buffer) {
    image *img = opaque;
    if (img->bad_count > 0 && lba < img->bad_first + img->bad_count && lba + (uint64_t) count > img->bad_first) {
        for (uint64_t b = lba; b < lba + (uint64_t) count; b++) {
            if (b >= img->bad_first && b < img->bad_first + img->bad_count) img->bad_reads[b - img->bad_first]++;
        }
        return -1;
    }
    int blocks = dr_fd_image_read(&img->fd_image, lba, count, buffer);
    if (blocks > 0) img->blocks_read += (uint64_t) blocks;
    return blocks;
}

/* Filesystem callbacks over a real directory, standing in for the app's SAF tree. */
typedef struct tree {
    char root[4096];
} tree;

static void full_path(const dvd_reader_filesystem_h *fs, const char *path, char *out, size_t size) {
    const tree *t = fs->internal;
    snprintf(out, size, "%s%s%s", t->root, path[0] == '/' ? "" : "/", path);
}

static void fs_close(dvd_reader_filesystem_h *fs) {
    free(fs->internal);
    free(fs);
}

static int fs_stat(dvd_reader_filesystem_h *fs, const char *path, dvdstat_t *out) {
    char full[8192];
    full_path(fs, path, full, sizeof(full));
    struct stat info;
    if (stat(full, &info) != 0) return -1;
    out->size = (uint64_t) info.st_size;
    out->st_mode = S_ISDIR(info.st_mode) ? DVD_S_IFDIR : DVD_S_IFREG;
    return 0;
}

static void *fs_dir_open(dvd_reader_filesystem_h *fs, const char *path) {
    char full[8192];
    full_path(fs, path, full, sizeof(full));
    return opendir(full);
}

static int fs_dir_read(void *dir, dvd_dirent_t *entry) {
    struct dirent *found = readdir(dir);
    if (found == NULL) return 1;
    snprintf(entry->d_name, sizeof(entry->d_name), "%s", found->d_name);
    return 0;
}

static void fs_dir_close(void *dir) {
    closedir(dir);
}

static void *fs_file_open(dvd_reader_filesystem_h *fs, const char *path) {
    char full[8192];
    full_path(fs, path, full, sizeof(full));
    int fd = open(full, O_RDONLY);
    return fd >= 0 ? dr_fd_file_wrap(fd) : NULL;
}

static dvd_reader_filesystem_h *tree_filesystem(const char *root) {
    dvd_reader_filesystem_h *fs = calloc(1, sizeof(dvd_reader_filesystem_h));
    tree *t = calloc(1, sizeof(tree));
    if (fs == NULL || t == NULL) {
        free(fs);
        free(t);
        return NULL;
    }
    snprintf(t->root, sizeof(t->root), "%s", root);
    fs->internal = t;
    fs->close = fs_close;
    fs->stat = fs_stat;
    fs->dir_open = fs_dir_open;
    fs->dir_read = fs_dir_read;
    fs->dir_close = fs_dir_close;
    fs->file_open = fs_file_open;
    fs->file_read = dr_fd_file_read;
    fs->file_seek = dr_fd_file_seek;
    fs->file_close = dr_fd_file_close;
    return fs;
}

/* The MKV through callbacks instead of a descriptor, standing in for the app's NAS client. */
typedef struct callback_file {
    int fd;
    int64_t fail_at; /* -1: never */
    int fail_errno;
} callback_file;

/* Reads return at most this much, as SMB reads may. */
#define SHORT_READ 4093

static int callback_write(void *opaque, const uint8_t *data, int size, int64_t position) {
    callback_file *f = opaque;
    if (f->fail_at >= 0 && position + size > f->fail_at) return -f->fail_errno;
    dr_file file = dr_fd_as_file(f->fd);
    return file.write(file.opaque, data, size, position);
}

static int callback_read(void *opaque, uint8_t *buffer, int size, int64_t position) {
    callback_file *f = opaque;
    dr_file file = dr_fd_as_file(f->fd);
    return file.read(file.opaque, buffer, size < SHORT_READ ? size : SHORT_READ, position);
}

static int64_t callback_size(void *opaque) {
    callback_file *f = opaque;
    dr_file file = dr_fd_as_file(f->fd);
    return file.size(file.opaque);
}

static dr_file callback_output(callback_file *f) {
    return (dr_file) {f, callback_write, callback_read, callback_size};
}

static image source_image = {.fd_image = {-1, 0}};

static dr_dvd *open_source(int argc, char **argv, int *next) {
    char error[256] = "";
    dr_dvd *dvd;
    if (strcmp(argv[*next], "--dir") == 0 && *next + 1 < argc) {
        dvd_reader_filesystem_h *fs = tree_filesystem(argv[*next + 1]);
        dvd = fs != NULL ? dr_dvd_open_files(fs, &logger, error, sizeof(error)) : NULL;
        if (dvd == NULL && fs != NULL) fs->close(fs);
        *next += 2;
    } else {
        int fd = open(argv[*next], O_RDONLY);
        if (fd < 0 || dr_fd_image_init(&source_image.fd_image, fd) != 0) {
            fprintf(stderr, "Cannot open %s: %s\n", argv[*next], strerror(errno));
            if (fd >= 0) close(fd);
            return NULL;
        }
        dr_block_source source = {&source_image, read_blocks};
        dvd = dr_dvd_open_blocks(&source, &logger, error, sizeof(error));
        *next += 1;
    }
    if (dvd == NULL) fprintf(stderr, "Cannot open the DVD: %s\n", error);
    return dvd;
}

static void on_progress(void *opaque, const dr_progress *progress) {
    (void) opaque;
    fprintf(stderr, "progress %llu/%llu sectors, %lld bytes written, at %lld ms\n",
            (unsigned long long) progress->sectors_done, (unsigned long long) progress->sectors_total,
            (long long) progress->output_bytes, (long long) progress->position_ms);
}

static int cancelled(void *opaque) {
    const image *img = opaque;
    return img->cancel_after > 0 && img->blocks_read >= img->cancel_after;
}

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: discrip_host probe <file.mkv> | titles|rip <image | --dir folder> ...\n");
        return 2;
    }
    if (strcmp(argv[1], "probe") == 0) {
        int fd = open(argv[2], O_RDONLY);
        if (fd < 0) {
            fprintf(stderr, "Cannot open %s: %s\n", argv[2], strerror(errno));
            return 1;
        }
        callback_file callbacks = {fd, -1, 0};
        dr_file file = argc > 3 && strcmp(argv[3], "--callbacks") == 0 ? callback_output(&callbacks) : dr_fd_as_file(fd);
        char *json = dr_probe(&file);
        close(fd);
        printf("%s\n", json);
        int ok = strstr(json, "\"ok\":true") != NULL;
        free(json);
        return ok ? 0 : 1;
    }
    dr_css_configure(getenv("TMPDIR") != NULL ? getenv("TMPDIR") : "/tmp");
    int next = 2;
    dr_dvd *dvd = open_source(argc, argv, &next);
    if (dvd == NULL) return 1;

    int status = 0;
    if (strcmp(argv[1], "titles") == 0) {
        char error[256] = "";
        char *json = dr_titles_json(dr_dvd_reader(dvd), &logger, error, sizeof(error));
        if (json == NULL) {
            fprintf(stderr, "Listing titles failed: %s\n", error);
            status = 1;
        } else {
            printf("%s\n", json);
            free(json);
        }
    } else if (strcmp(argv[1], "rip") == 0 && next + 1 < argc) {
        dr_rip_options options = {0};
        options.title = atoi(argv[next]);
        options.angle = 1;
        const char *output = argv[next + 1];
        dr_audio_choice audio[8];
        dr_subtitle_choice subtitles[32];
        const char *tag_keys[8];
        const char *tag_values[8];
        int default_audio = -1;
        int forced_subtitle = -1;
        int use_callbacks = 0;
        callback_file callbacks = {-1, -1, 0};
        for (int i = next + 2; i < argc; i++) {
            if (strcmp(argv[i], "--flac") == 0) {
                options.lpcm_to_flac = 1;
            } else if (strcmp(argv[i], "--callbacks") == 0) {
                use_callbacks = 1;
            } else if (strcmp(argv[i], "--fail-write-at") == 0 && i + 1 < argc) {
                char *end = NULL;
                use_callbacks = 1;
                callbacks.fail_at = strtoll(argv[++i], &end, 0);
                callbacks.fail_errno = end != NULL && strcmp(end, ":nospace") == 0 ? ENOSPC : EIO;
            } else if (strcmp(argv[i], "--movie-title") == 0 && i + 1 < argc) {
                options.movie_title = argv[++i];
            } else if (strcmp(argv[i], "--date") == 0 && i + 1 < argc) {
                options.release_date = argv[++i];
            } else if (strcmp(argv[i], "--default-audio") == 0 && i + 1 < argc) {
                default_audio = (int) strtol(argv[++i], NULL, 0);
            } else if (strcmp(argv[i], "--cancel-after") == 0 && i + 1 < argc) {
                source_image.cancel_after = strtoull(argv[++i], NULL, 0);
            } else if (strcmp(argv[i], "--bad-blocks") == 0 && i + 1 < argc) {
                char *end = NULL;
                source_image.bad_first = strtoull(argv[++i], &end, 0);
                source_image.bad_count = end != NULL && *end == ':' ? strtoull(end + 1, NULL, 0) : 1;
                if (source_image.bad_count > 64) source_image.bad_count = 64;
            } else if (strcmp(argv[i], "--tag") == 0 && i + 1 < argc && options.tag_count < 8) {
                char *pair = argv[++i];
                char *value = strchr(pair, '=');
                if (value != NULL) {
                    *value++ = '\0';
                    tag_keys[options.tag_count] = pair;
                    tag_values[options.tag_count++] = value;
                }
            } else if (strcmp(argv[i], "--forced-sub") == 0 && i + 1 < argc) {
                forced_subtitle = (int) strtol(argv[++i], NULL, 0);
            } else if (strcmp(argv[i], "--subs") == 0 && i + 1 < argc) {
                char *list = argv[++i];
                for (char *item = strtok(list, ","); item != NULL && options.subtitle_count < 32; item = strtok(NULL, ",")) {
                    int n = options.subtitle_count++;
                    char *id = item;
                    char *language = strchr(id, ':');
                    if (language != NULL) *language++ = '\0';
                    char *name = language != NULL ? strchr(language, ':') : NULL;
                    if (name != NULL) *name++ = '\0';
                    subtitles[n] = (dr_subtitle_choice) {(int) strtol(id, NULL, 0), language, name, 0};
                }
            } else if (strcmp(argv[i], "--audio") == 0 && i + 1 < argc) {
                char *list = argv[++i];
                for (char *item = strtok(list, ","); item != NULL && options.audio_count < 8; item = strtok(NULL, ",")) {
                    int n = options.audio_count++;
                    char *id = item;
                    char *language = strchr(id, ':');
                    if (language != NULL) *language++ = '\0';
                    char *name = language != NULL ? strchr(language, ':') : NULL;
                    if (name != NULL) *name++ = '\0';
                    audio[n] = (dr_audio_choice) {(int) strtol(id, NULL, 0), language, name, 0};
                }
            }
        }
        for (int i = 0; i < options.audio_count; i++) {
            if (default_audio >= 0 ? audio[i].stream_id == default_audio : i == 0) audio[i].flags |= DR_TRACK_DEFAULT;
        }
        options.audio = audio;
        for (int i = 0; i < options.subtitle_count; i++) {
            if (subtitles[i].stream_id == forced_subtitle) subtitles[i].flags |= DR_TRACK_FORCED;
        }
        options.subtitles = subtitles;
        options.tag_keys = tag_keys;
        options.tag_values = tag_values;
        int output_fd = open(output, O_RDWR | O_CREAT | O_TRUNC, 0644);
        callbacks.fd = output_fd;
        options.output = use_callbacks ? callback_output(&callbacks) : dr_fd_as_file(output_fd);
        if (output_fd < 0) {
            fprintf(stderr, "Cannot create %s: %s\n", output, strerror(errno));
            status = 1;
        } else {
            dr_rip_hooks hooks = {&source_image, on_progress, cancelled};
            char *report = dr_rip(dr_dvd_reader(dvd), &options, &hooks, &logger);
            printf("%s\n", report);
            for (uint64_t b = 0; b < source_image.bad_count; b++) {
                fprintf(stderr, "bad block %llu read %d times\n", (unsigned long long) (source_image.bad_first + b),
                        source_image.bad_reads[b]);
            }
            status = strstr(report, "\"ok\":true") != NULL ? 0 : 1;
            free(report);
            close(output_fd);
        }
    } else {
        fprintf(stderr, "Unknown command\n");
        status = 2;
    }
    dr_dvd_close(dvd);
    if (source_image.fd_image.fd >= 0) close(source_image.fd_image.fd);
    return status;
}
