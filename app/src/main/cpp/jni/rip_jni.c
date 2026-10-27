/*
 * JNI bridge between be.discrip.rip.NativeRip and the rip engine.
 *
 * A session is one open DVD: an image read with pread() on a descriptor Kotlin hands over, the disc
 * in a USB drive read through a Kotlin NativeBlocks, or a VIDEO_TS folder reached through a Kotlin
 * NativeFolder (stat, list, open) whose files are then read here, on the descriptors it opens. The MKV goes to a descriptor, or through a Kotlin
 * NativeFile (the NAS). Text crosses the boundary as UTF-8 byte arrays because JNI's own string
 * functions use modified UTF-8.
 *
 * libdvdread picks its input functions through process-wide pointers, so one session at a time.
 */
#include <android/log.h>
#include <errno.h>
#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "dr_fd.h"
#include "dr_json.h"
#include "dr_plan.h"
#include "dr_probe.h"
#include "dr_rip.h"
#include "dr_source.h"

/* NativeFolder.stat() results besides a file size. */
#define STAT_MISSING (-1)
#define STAT_DIRECTORY (-2)

typedef struct java_blocks java_blocks;

typedef struct session {
    dr_dvd *dvd;
    dr_fd_image image; /* fd -1 for a folder or a drive */
    java_blocks *blocks; /* a drive */
    atomic_int cancel;
} session;

static JavaVM *java_vm;
static pthread_mutex_t guard = PTHREAD_MUTEX_INITIALIZER;
static int session_open;

static session *session_of(jlong handle) {
    return (session *) (intptr_t) handle;
}

static void log_line(void *opaque, int level, const char *message) {
    (void) opaque;
    static const int priorities[] = {ANDROID_LOG_DEBUG, ANDROID_LOG_INFO, ANDROID_LOG_WARN, ANDROID_LOG_ERROR};
    __android_log_write(priorities[level], "DiscRip", message);
}

static const dr_logger logger = {NULL, log_line};

/* The calling thread's environment; filesystem callbacks run inside a NativeRip call. */
static JNIEnv *current_env(void) {
    JNIEnv *env = NULL;
    if (java_vm == NULL || (*java_vm)->GetEnv(java_vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) return NULL;
    return env;
}

/* Clears a pending Java exception; true when there was one. */
static int failed_call(JNIEnv *env) {
    if (!(*env)->ExceptionCheck(env)) return 0;
    (*env)->ExceptionClear(env);
    return 1;
}

/* A NUL-terminated copy of a UTF-8 byte array (caller frees), or NULL for null or no memory. */
static char *text_of(JNIEnv *env, jbyteArray bytes) {
    if (bytes == NULL) return NULL;
    jsize length = (*env)->GetArrayLength(env, bytes);
    char *text = malloc((size_t) length + 1);
    if (text == NULL) return NULL;
    (*env)->GetByteArrayRegion(env, bytes, 0, length, (jbyte *) text);
    text[length] = '\0';
    return text;
}

static jbyteArray bytes_of(JNIEnv *env, const char *text) {
    jsize length = (jsize) strlen(text);
    jbyteArray bytes = (*env)->NewByteArray(env, length);
    if (bytes != NULL) (*env)->SetByteArrayRegion(env, bytes, 0, length, (const jbyte *) text);
    return bytes;
}

/* Returns an engine JSON result as bytes and frees it; NULL (an OutOfMemoryError pending) if out of memory. */
static jbyteArray take_json(JNIEnv *env, char *json) {
    if (json == NULL) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/OutOfMemoryError"), "rip engine");
        return NULL;
    }
    jbyteArray bytes = bytes_of(env, json);
    free(json);
    return bytes;
}

static char *error_json(const char *message) {
    dr_json json;
    dr_json_init(&json);
    dr_json_begin_object(&json);
    dr_json_key(&json, "error"); dr_json_string(&json, message);
    dr_json_end_object(&json);
    return dr_json_take(&json);
}

static void set_error(JNIEnv *env, jobjectArray error, const char *message) {
    jbyteArray bytes = bytes_of(env, message);
    if (bytes == NULL) return;
    (*env)->SetObjectArrayElement(env, error, 0, bytes);
    (*env)->DeleteLocalRef(env, bytes);
}

/* Claims the one session slot; false (error set) when a DVD is already open. */
static int claim_slot(JNIEnv *env, jobjectArray error) {
    pthread_mutex_lock(&guard);
    int free_slot = !session_open;
    if (free_slot) session_open = 1;
    pthread_mutex_unlock(&guard);
    if (!free_slot) set_error(env, error, "Another DVD is still open");
    return free_slot;
}

static void release_slot(void) {
    pthread_mutex_lock(&guard);
    session_open = 0;
    pthread_mutex_unlock(&guard);
}

static session *new_session(void) {
    session *s = calloc(1, sizeof(session));
    if (s == NULL) return NULL;
    s->image.fd = -1;
    atomic_init(&s->cancel, 0);
    return s;
}

/* --- VIDEO_TS folder through Kotlin --------------------------------------------------------- */

typedef struct folder {
    jobject target; /* global reference to the NativeFolder */
    jmethodID stat;
    jmethodID list;
    jmethodID open;
} folder;

typedef struct listing {
    int count;
    int next;
    char **names;
} listing;

static void folder_close(dvd_reader_filesystem_h *fs) {
    folder *f = fs->internal;
    JNIEnv *env = current_env();
    if (env != NULL) (*env)->DeleteGlobalRef(env, f->target);
    free(f);
    free(fs);
}

static int folder_stat(dvd_reader_filesystem_h *fs, const char *path, dvdstat_t *out) {
    folder *f = fs->internal;
    JNIEnv *env = current_env();
    if (env == NULL) return -1;
    jbyteArray name = bytes_of(env, path);
    if (name == NULL) {
        failed_call(env);
        return -1;
    }
    jlong result = (*env)->CallLongMethod(env, f->target, f->stat, name);
    (*env)->DeleteLocalRef(env, name);
    if (failed_call(env) || result == STAT_MISSING || result < STAT_DIRECTORY) return -1;
    out->size = result == STAT_DIRECTORY ? 0 : (uint64_t) result;
    out->st_mode = result == STAT_DIRECTORY ? DVD_S_IFDIR : DVD_S_IFREG;
    return 0;
}

static void free_listing(listing *l) {
    for (int i = 0; i < l->count; i++) free(l->names[i]);
    free(l->names);
    free(l);
}

static void *folder_dir_open(dvd_reader_filesystem_h *fs, const char *path) {
    folder *f = fs->internal;
    JNIEnv *env = current_env();
    if (env == NULL) return NULL;
    jbyteArray name = bytes_of(env, path);
    if (name == NULL) {
        failed_call(env);
        return NULL;
    }
    jobjectArray names = (*env)->CallObjectMethod(env, f->target, f->list, name);
    (*env)->DeleteLocalRef(env, name);
    if (failed_call(env) || names == NULL) return NULL;

    jsize count = (*env)->GetArrayLength(env, names);
    listing *l = calloc(1, sizeof(listing));
    if (l != NULL) l->names = calloc(count > 0 ? (size_t) count : 1, sizeof(char *));
    if (l == NULL || l->names == NULL) {
        free(l);
        (*env)->DeleteLocalRef(env, names);
        return NULL;
    }
    for (jsize i = 0; i < count; i++) {
        jbyteArray entry = (*env)->GetObjectArrayElement(env, names, i);
        char *text = text_of(env, entry);
        (*env)->DeleteLocalRef(env, entry);
        if (text == NULL) {
            free_listing(l);
            (*env)->DeleteLocalRef(env, names);
            return NULL;
        }
        l->names[l->count++] = text;
    }
    (*env)->DeleteLocalRef(env, names);
    return l;
}

static int folder_dir_read(void *dir, dvd_dirent_t *entry) {
    listing *l = dir;
    if (l->next >= l->count) return 1;
    const char *name = l->names[l->next++];
    size_t length = strlen(name);
    if (length > DVDREAD_NAME_MAX) length = DVDREAD_NAME_MAX;
    memcpy(entry->d_name, name, length);
    entry->d_name[length] = '\0';
    return 0;
}

static void folder_dir_close(void *dir) {
    free_listing(dir);
}

static void *folder_file_open(dvd_reader_filesystem_h *fs, const char *path) {
    folder *f = fs->internal;
    JNIEnv *env = current_env();
    if (env == NULL) return NULL;
    jbyteArray name = bytes_of(env, path);
    if (name == NULL) {
        failed_call(env);
        return NULL;
    }
    jint fd = (*env)->CallIntMethod(env, f->target, f->open, name);
    (*env)->DeleteLocalRef(env, name);
    if (failed_call(env) || fd < 0) return NULL;
    return dr_fd_file_wrap(fd);
}

static dvd_reader_filesystem_h *folder_filesystem(JNIEnv *env, jobject target) {
    jclass type = (*env)->GetObjectClass(env, target);
    jmethodID stat = (*env)->GetMethodID(env, type, "stat", "([B)J");
    jmethodID list = stat != NULL ? (*env)->GetMethodID(env, type, "list", "([B)[[B") : NULL;
    jmethodID open = list != NULL ? (*env)->GetMethodID(env, type, "open", "([B)I") : NULL;
    (*env)->DeleteLocalRef(env, type);
    if (open == NULL) {
        failed_call(env);
        return NULL;
    }
    dvd_reader_filesystem_h *fs = calloc(1, sizeof(dvd_reader_filesystem_h));
    folder *f = calloc(1, sizeof(folder));
    jobject global = (*env)->NewGlobalRef(env, target);
    if (fs == NULL || f == NULL || global == NULL) {
        if (global != NULL) (*env)->DeleteGlobalRef(env, global);
        free(fs);
        free(f);
        return NULL;
    }
    *f = (folder) {global, stat, list, open};
    fs->internal = f;
    fs->close = folder_close;
    fs->stat = folder_stat;
    fs->dir_open = folder_dir_open;
    fs->dir_read = folder_dir_read;
    fs->dir_close = folder_dir_close;
    fs->file_open = folder_file_open;
    fs->file_read = dr_fd_file_read;
    fs->file_seek = dr_fd_file_seek;
    fs->file_close = dr_fd_file_close;
    return fs;
}

/* --- A disc through Kotlin (NativeBlocks): the disc in a USB drive ----------------------------- */

struct java_blocks {
    jobject target; /* global reference to the NativeBlocks */
    jmethodID read;
    /* Carries the blocks; a global reference, kept between calls and grown as needed. */
    jbyteArray buffer;
    jsize capacity;
};

static int java_blocks_read(void *opaque, uint64_t lba, int count, uint8_t *out) {
    java_blocks *b = opaque;
    JNIEnv *env = current_env();
    if (env == NULL || count < 0 || count > INT32_MAX / DR_BLOCK_SIZE) return -1;
    jsize size = count * DR_BLOCK_SIZE;
    if (b->capacity < size) {
        jbyteArray local = (*env)->NewByteArray(env, size);
        jbyteArray grown = local != NULL ? (*env)->NewGlobalRef(env, local) : NULL;
        if (local != NULL) (*env)->DeleteLocalRef(env, local);
        if (grown == NULL) {
            failed_call(env);
            return -1;
        }
        if (b->buffer != NULL) (*env)->DeleteGlobalRef(env, b->buffer);
        b->buffer = grown;
        b->capacity = size;
    }
    jint got = (*env)->CallIntMethod(env, b->target, b->read, (jlong) lba, (jint) count, b->buffer);
    if (failed_call(env) || got < 0 || got > count) return -1;
    if (got > 0) (*env)->GetByteArrayRegion(env, b->buffer, 0, got * DR_BLOCK_SIZE, (jbyte *) out);
    return got;
}

static void java_blocks_free(JNIEnv *env, java_blocks *b) {
    if (b == NULL) return;
    if (b->buffer != NULL) (*env)->DeleteGlobalRef(env, b->buffer);
    if (b->target != NULL) (*env)->DeleteGlobalRef(env, b->target);
    free(b);
}

/* --- Files through Kotlin (NativeFile), such as the MKV on the NAS -------------------------------- */

typedef struct java_file {
    JNIEnv *env;
    jobject target;
    jmethodID write;
    jmethodID read;
    jmethodID size;
    /* Carries the bytes both ways; kept between calls and grown as needed. */
    jbyteArray buffer;
    jsize capacity;
} java_file;

/* Finds the NativeFile methods of target; false (exception pending) when one is missing. */
static int java_file_init(JNIEnv *env, jobject target, java_file *out) {
    *out = (java_file) {.env = env, .target = target};
    jclass type = (*env)->GetObjectClass(env, target);
    out->write = (*env)->GetMethodID(env, type, "write", "([BIJ)I");
    out->read = out->write != NULL ? (*env)->GetMethodID(env, type, "read", "([BIJ)I") : NULL;
    out->size = out->read != NULL ? (*env)->GetMethodID(env, type, "size", "()J") : NULL;
    (*env)->DeleteLocalRef(env, type);
    return out->size != NULL;
}

static void java_file_release(java_file *f) {
    if (f->buffer != NULL) (*f->env)->DeleteLocalRef(f->env, f->buffer);
    f->buffer = NULL;
    f->capacity = 0;
}

/* Makes the buffer hold at least size bytes; false when memory runs out. */
static int java_file_reserve(java_file *f, int size) {
    if (f->buffer != NULL && f->capacity >= size) return 1;
    java_file_release(f);
    f->buffer = (*f->env)->NewByteArray(f->env, size);
    if (f->buffer == NULL) {
        failed_call(f->env);
        return 0;
    }
    f->capacity = size;
    return 1;
}

/* NativeFile reports failures as negative errno values; an exception that escapes it is an EIO. */
static int java_file_write(void *opaque, const uint8_t *data, int size, int64_t position) {
    java_file *f = opaque;
    if (!java_file_reserve(f, size)) return -ENOMEM;
    (*f->env)->SetByteArrayRegion(f->env, f->buffer, 0, size, (const jbyte *) data);
    jint result = (*f->env)->CallIntMethod(f->env, f->target, f->write, f->buffer, size, (jlong) position);
    if (failed_call(f->env)) return -EIO;
    return result < 0 ? result : 0;
}

static int java_file_read(void *opaque, uint8_t *buffer, int size, int64_t position) {
    java_file *f = opaque;
    if (!java_file_reserve(f, size)) return -ENOMEM;
    jint got = (*f->env)->CallIntMethod(f->env, f->target, f->read, f->buffer, size, (jlong) position);
    if (failed_call(f->env) || got > size) return -EIO;
    if (got > 0) (*f->env)->GetByteArrayRegion(f->env, f->buffer, 0, got, (jbyte *) buffer);
    return got;
}

static int64_t java_file_size(void *opaque) {
    java_file *f = opaque;
    jlong size = (*f->env)->CallLongMethod(f->env, f->target, f->size);
    return failed_call(f->env) ? -EIO : (int64_t) size;
}

static dr_file java_file_as_file(java_file *f) {
    return (dr_file) {f, java_file_write, java_file_read, java_file_size};
}

/* --- Entry points ----------------------------------------------------------------------------- */

JNIEXPORT void JNICALL
Java_be_discrip_rip_NativeRip_configure(JNIEnv *env, jclass clazz) {
    (void) clazz;
    (*env)->GetJavaVM(env, &java_vm);
    dr_css_configure();
}

/* Opens an image on fd, which the session then owns (closed also on failure). */
JNIEXPORT jlong JNICALL
Java_be_discrip_rip_NativeRip_openImage(JNIEnv *env, jclass clazz, jint fd, jobjectArray error) {
    (void) clazz;
    if (!claim_slot(env, error)) {
        close(fd);
        return 0;
    }
    char message[256] = "out of memory";
    session *s = new_session();
    if (s != NULL && dr_fd_image_init(&s->image, fd) != 0) {
        snprintf(message, sizeof(message), "The image cannot be read");
    } else if (s != NULL) {
        dr_block_source source = {&s->image, dr_fd_image_read};
        s->dvd = dr_dvd_open_blocks(&source, &logger, message, sizeof(message));
        if (s->dvd != NULL) return (jlong) (intptr_t) s;
    }
    free(s);
    close(fd);
    release_slot();
    set_error(env, error, message);
    return 0;
}

/* Opens the disc that target (a NativeBlocks) reads. */
JNIEXPORT jlong JNICALL
Java_be_discrip_rip_NativeRip_openBlocks(JNIEnv *env, jclass clazz, jobject target, jobjectArray error) {
    (void) clazz;
    if (!claim_slot(env, error)) return 0;
    (*env)->GetJavaVM(env, &java_vm);
    char message[256] = "out of memory";
    session *s = new_session();
    java_blocks *b = s != NULL ? calloc(1, sizeof(java_blocks)) : NULL;
    if (b != NULL) {
        jclass type = (*env)->GetObjectClass(env, target);
        b->read = (*env)->GetMethodID(env, type, "read", "(JI[B)I");
        (*env)->DeleteLocalRef(env, type);
        b->target = b->read != NULL ? (*env)->NewGlobalRef(env, target) : NULL;
        if (b->target == NULL) failed_call(env);
    }
    if (b != NULL && b->target != NULL) {
        s->blocks = b;
        dr_block_source source = {b, java_blocks_read};
        s->dvd = dr_dvd_open_blocks(&source, &logger, message, sizeof(message));
        if (s->dvd != NULL) return (jlong) (intptr_t) s;
    }
    java_blocks_free(env, b);
    free(s);
    release_slot();
    set_error(env, error, message);
    return 0;
}

JNIEXPORT jlong JNICALL
Java_be_discrip_rip_NativeRip_openFolder(JNIEnv *env, jclass clazz, jobject target, jobjectArray error) {
    (void) clazz;
    if (!claim_slot(env, error)) return 0;
    (*env)->GetJavaVM(env, &java_vm);
    char message[256] = "out of memory";
    session *s = new_session();
    dvd_reader_filesystem_h *fs = s != NULL ? folder_filesystem(env, target) : NULL;
    if (fs != NULL) {
        s->dvd = dr_dvd_open_files(fs, &logger, message, sizeof(message));
        if (s->dvd != NULL) return (jlong) (intptr_t) s;
        fs->close(fs);
    }
    free(s);
    release_slot();
    set_error(env, error, message);
    return 0;
}

/* {"titles":[…]} or {"error":"…"}. */
JNIEXPORT jbyteArray JNICALL
Java_be_discrip_rip_NativeRip_titles(JNIEnv *env, jclass clazz, jlong handle) {
    (void) clazz;
    char error[256] = "";
    char *json = dr_titles_json(dr_dvd_reader(session_of(handle)->dvd), &logger, error, sizeof(error));
    return take_json(env, json != NULL ? json : error_json(error));
}

typedef struct rip_call {
    JNIEnv *env;
    jobject listener;
    jmethodID on_progress;
    session *s;
} rip_call;

static void on_progress(void *opaque, const dr_progress *progress) {
    rip_call *call = opaque;
    (*call->env)->CallVoidMethod(call->env, call->listener, call->on_progress, (jlong) progress->sectors_done,
                                 (jlong) progress->sectors_total, (jlong) progress->output_bytes,
                                 (jlong) progress->position_ms);
    failed_call(call->env);
}

static int is_cancelled(void *opaque) {
    const rip_call *call = opaque;
    return atomic_load(&call->s->cancel);
}

/* Copies a byte[][] (entries may be null) into C strings; count of entries, or -1 out of memory. */
static int texts_of(JNIEnv *env, jobjectArray array, char ***out) {
    jsize count = array != NULL ? (*env)->GetArrayLength(env, array) : 0;
    char **texts = calloc(count > 0 ? (size_t) count : 1, sizeof(char *));
    if (texts == NULL) return -1;
    for (jsize i = 0; i < count; i++) {
        jbyteArray entry = (*env)->GetObjectArrayElement(env, array, i);
        if (entry == NULL) continue;
        texts[i] = text_of(env, entry);
        (*env)->DeleteLocalRef(env, entry);
        if (texts[i] == NULL) {
            for (jsize j = 0; j < i; j++) free(texts[j]);
            free(texts);
            return -1;
        }
    }
    *out = texts;
    return (int) count;
}

static void free_texts(char **texts, int count) {
    if (texts == NULL) return;
    for (int i = 0; i < count; i++) free(texts[i]);
    free(texts);
}

/* Tracks of one kind as Kotlin passes them: parallel arrays of stream ids, flags, languages, names. */
typedef struct track_arrays {
    int count;
    jint *ids;
    jint *flags;
    char **languages;
    int language_count;
    char **names;
    int name_count;
} track_arrays;

/* False (exception pending) when the arrays differ in length or memory runs out. */
static int read_tracks(JNIEnv *env, jintArray ids, jintArray flags, jobjectArray languages, jobjectArray names,
                       track_arrays *out) {
    *out = (track_arrays) {0};
    out->count = (*env)->GetArrayLength(env, ids);
    if ((*env)->GetArrayLength(env, flags) != out->count || (*env)->GetArrayLength(env, languages) != out->count ||
        (*env)->GetArrayLength(env, names) != out->count) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalArgumentException"), "track arrays differ in length");
        return 0;
    }
    if (out->count == 0) return 1;
    out->ids = (*env)->GetIntArrayElements(env, ids, NULL);
    out->flags = out->ids != NULL ? (*env)->GetIntArrayElements(env, flags, NULL) : NULL;
    out->language_count = out->flags != NULL ? texts_of(env, languages, &out->languages) : -1;
    out->name_count = out->language_count >= 0 ? texts_of(env, names, &out->names) : -1;
    if (out->name_count < 0 && !(*env)->ExceptionCheck(env)) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/OutOfMemoryError"), "track arrays");
    }
    return out->name_count >= 0;
}

static void release_tracks(JNIEnv *env, jintArray ids, jintArray flags, track_arrays *tracks) {
    if (tracks->ids != NULL) (*env)->ReleaseIntArrayElements(env, ids, tracks->ids, JNI_ABORT);
    if (tracks->flags != NULL) (*env)->ReleaseIntArrayElements(env, flags, tracks->flags, JNI_ABORT);
    free_texts(tracks->languages, tracks->language_count);
    free_texts(tracks->names, tracks->name_count);
}

/*
 * Rips a title to output_file when given, else to output_fd; both stay the caller's. Tracks are
 * given in output order, as parallel arrays: stream id, DR_TRACK_ flags, language (ISO 639-2/B)
 * and name, the last two possibly null.
 */
JNIEXPORT jbyteArray JNICALL
Java_be_discrip_rip_NativeRip_rip(JNIEnv *env, jclass clazz, jlong handle, jint title, jint angle,
                                  jintArray audio_ids, jintArray audio_flags, jobjectArray audio_languages,
                                  jobjectArray audio_names, jintArray subtitle_ids, jintArray subtitle_flags,
                                  jobjectArray subtitle_languages, jobjectArray subtitle_names, jboolean lpcm_to_flac,
                                  jbyteArray movie_title, jbyteArray release_date, jobjectArray tag_keys,
                                  jobjectArray tag_values, jobjectArray chapter_names, jint output_fd,
                                  jobject output_file, jobject listener) {
    (void) clazz;
    session *s = session_of(handle);
    atomic_store(&s->cancel, 0);

    jclass listener_type = (*env)->GetObjectClass(env, listener);
    jmethodID on_progress_id = (*env)->GetMethodID(env, listener_type, "onProgress", "(JJJJ)V");
    (*env)->DeleteLocalRef(env, listener_type);
    if (on_progress_id == NULL) return NULL; /* NoSuchMethodError pending */
    java_file output = {0};
    if (output_file != NULL && !java_file_init(env, output_file, &output)) return NULL;

    track_arrays audio_in = {0};
    track_arrays subtitles_in = {0};
    int read = read_tracks(env, audio_ids, audio_flags, audio_languages, audio_names, &audio_in);
    read = read && read_tracks(env, subtitle_ids, subtitle_flags, subtitle_languages, subtitle_names, &subtitles_in);
    if (!read) {
        release_tracks(env, audio_ids, audio_flags, &audio_in);
        release_tracks(env, subtitle_ids, subtitle_flags, &subtitles_in);
        return NULL;
    }

    char **chapters = NULL;
    char **keys = NULL;
    char **values = NULL;
    char *movie = text_of(env, movie_title);
    char *date = text_of(env, release_date);
    int chapter_count = texts_of(env, chapter_names, &chapters);
    int key_count = texts_of(env, tag_keys, &keys);
    int value_count = texts_of(env, tag_values, &values);
    dr_audio_choice *audio = calloc(audio_in.count > 0 ? (size_t) audio_in.count : 1, sizeof(dr_audio_choice));
    dr_subtitle_choice *subtitles = calloc(subtitles_in.count > 0 ? (size_t) subtitles_in.count : 1, sizeof(dr_subtitle_choice));

    char *report;
    if (audio == NULL || subtitles == NULL || chapter_count < 0 || key_count < 0 || value_count != key_count ||
        (movie_title != NULL && movie == NULL) || (release_date != NULL && date == NULL)) {
        report = NULL;
    } else {
        for (int i = 0; i < audio_in.count; i++) {
            audio[i] = (dr_audio_choice) {audio_in.ids[i], audio_in.languages[i], audio_in.names[i], audio_in.flags[i]};
        }
        for (int i = 0; i < subtitles_in.count; i++) {
            subtitles[i] = (dr_subtitle_choice) {subtitles_in.ids[i], subtitles_in.languages[i], subtitles_in.names[i],
                                                 subtitles_in.flags[i]};
        }
        dr_rip_options options = {
            .title = title,
            .angle = angle,
            .audio = audio,
            .audio_count = audio_in.count,
            .subtitles = subtitles,
            .subtitle_count = subtitles_in.count,
            .lpcm_to_flac = lpcm_to_flac,
            .movie_title = movie,
            .release_date = date,
            .tag_keys = (const char *const *) keys,
            .tag_values = (const char *const *) values,
            .tag_count = key_count,
            .chapter_names = (const char *const *) chapters,
            .chapter_name_count = chapter_count,
            .output = output_file != NULL ? java_file_as_file(&output) : dr_fd_as_file(output_fd),
        };
        rip_call call = {env, listener, on_progress_id, s};
        dr_rip_hooks hooks = {&call, on_progress, is_cancelled};
        report = dr_rip(s->dvd, &options, &hooks, &logger);
    }

    if (output_file != NULL) java_file_release(&output);
    release_tracks(env, audio_ids, audio_flags, &audio_in);
    release_tracks(env, subtitle_ids, subtitle_flags, &subtitles_in);
    free_texts(chapters, chapter_count);
    free_texts(keys, key_count);
    free_texts(values, value_count);
    free(audio);
    free(subtitles);
    free(movie);
    free(date);
    return take_json(env, report);
}

/* Asks a running rip to stop; it returns {"ok":false,"error":"cancelled"} soon after. */
JNIEXPORT void JNICALL
Java_be_discrip_rip_NativeRip_cancel(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    atomic_store(&session_of(handle)->cancel, 1);
}

JNIEXPORT void JNICALL
Java_be_discrip_rip_NativeRip_close(JNIEnv *env, jclass clazz, jlong handle) {
    (void) clazz;
    session *s = session_of(handle);
    if (s == NULL) return;
    dr_dvd_close(s->dvd);
    if (s->image.fd >= 0) close(s->image.fd);
    java_blocks_free(env, s->blocks);
    free(s);
    release_slot();
}

/* Describes a finished Matroska file: file when given, else the one on fd; both stay the caller's. */
JNIEXPORT jbyteArray JNICALL
Java_be_discrip_rip_NativeRip_probe(JNIEnv *env, jclass clazz, jint fd, jobject file) {
    (void) clazz;
    if (file == NULL) {
        dr_file input = dr_fd_as_file(fd);
        return take_json(env, dr_probe(&input));
    }
    java_file reader;
    if (!java_file_init(env, file, &reader)) return NULL;
    dr_file input = java_file_as_file(&reader);
    char *json = dr_probe(&input);
    java_file_release(&reader);
    return take_json(env, json);
}
