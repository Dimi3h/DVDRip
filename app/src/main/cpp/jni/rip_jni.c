/*
 * JNI bridge between be.discrip.rip.NativeRip and the rip engine.
 *
 * A session is one open DVD: an image read with pread() on a descriptor Kotlin hands over, or a
 * VIDEO_TS folder reached through a Kotlin NativeFolder (stat, list, open) whose files are then
 * read here, on the descriptors it opens. Text crosses the boundary as UTF-8 byte arrays because
 * JNI's own string functions use modified UTF-8.
 *
 * libdvdread picks its input functions through process-wide pointers, so one session at a time.
 */
#include <android/log.h>
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

typedef struct session {
    dr_dvd *dvd;
    dr_fd_image image; /* fd -1 for a folder */
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

/* --- Entry points ----------------------------------------------------------------------------- */

JNIEXPORT void JNICALL
Java_be_discrip_rip_NativeRip_configure(JNIEnv *env, jclass clazz, jbyteArray cache_dir) {
    (void) clazz;
    (*env)->GetJavaVM(env, &java_vm);
    char *path = text_of(env, cache_dir);
    dr_css_configure(path);
    free(path);
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

/*
 * Rips a title to output_fd (which stays the caller's). Audio tracks are given in output order:
 * audioIds[i] with audioLanguages[i] (ISO 639-2/B) and audioNames[i], either may be null.
 */
JNIEXPORT jbyteArray JNICALL
Java_be_discrip_rip_NativeRip_rip(JNIEnv *env, jclass clazz, jlong handle, jint title, jint angle,
                                  jintArray audio_ids, jobjectArray audio_languages, jobjectArray audio_names,
                                  jint default_audio, jboolean lpcm_to_flac, jbyteArray movie_title,
                                  jbyteArray release_date, jobjectArray chapter_names, jint output_fd,
                                  jobject listener) {
    (void) clazz;
    session *s = session_of(handle);
    atomic_store(&s->cancel, 0);

    jclass listener_type = (*env)->GetObjectClass(env, listener);
    jmethodID on_progress_id = (*env)->GetMethodID(env, listener_type, "onProgress", "(JJJJ)V");
    (*env)->DeleteLocalRef(env, listener_type);
    if (on_progress_id == NULL) return NULL; /* NoSuchMethodError pending */

    int audio_count = (*env)->GetArrayLength(env, audio_ids);
    if ((*env)->GetArrayLength(env, audio_languages) != audio_count ||
        (*env)->GetArrayLength(env, audio_names) != audio_count) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalArgumentException"), "audio arrays differ in length");
        return NULL;
    }
    jint *ids = audio_count > 0 ? (*env)->GetIntArrayElements(env, audio_ids, NULL) : NULL;
    char **languages = NULL;
    char **names = NULL;
    char **chapters = NULL;
    char *movie = text_of(env, movie_title);
    char *date = text_of(env, release_date);
    int language_count = texts_of(env, audio_languages, &languages);
    int name_count = texts_of(env, audio_names, &names);
    int chapter_count = texts_of(env, chapter_names, &chapters);
    dr_audio_choice *audio = calloc(audio_count > 0 ? (size_t) audio_count : 1, sizeof(dr_audio_choice));

    char *report;
    if (audio == NULL || language_count < 0 || name_count < 0 || chapter_count < 0 ||
        (audio_count > 0 && ids == NULL) || (movie_title != NULL && movie == NULL) ||
        (release_date != NULL && date == NULL)) {
        report = NULL;
    } else {
        for (int i = 0; i < audio_count; i++) {
            audio[i] = (dr_audio_choice) {ids[i], languages[i], names[i], i == default_audio};
        }
        dr_rip_options options = {
            .title = title,
            .angle = angle,
            .audio = audio,
            .audio_count = audio_count,
            .lpcm_to_flac = lpcm_to_flac,
            .movie_title = movie,
            .release_date = date,
            .chapter_names = (const char *const *) chapters,
            .chapter_name_count = chapter_count,
            .output_fd = output_fd,
        };
        rip_call call = {env, listener, on_progress_id, s};
        dr_rip_hooks hooks = {&call, on_progress, is_cancelled};
        report = dr_rip(dr_dvd_reader(s->dvd), &options, &hooks, &logger);
    }

    if (ids != NULL) (*env)->ReleaseIntArrayElements(env, audio_ids, ids, JNI_ABORT);
    free_texts(languages, language_count);
    free_texts(names, name_count);
    free_texts(chapters, chapter_count);
    free(audio);
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
    (void) env;
    (void) clazz;
    session *s = session_of(handle);
    if (s == NULL) return;
    dr_dvd_close(s->dvd);
    if (s->image.fd >= 0) close(s->image.fd);
    free(s);
    release_slot();
}

/* Describes a finished Matroska file on fd (which stays the caller's). */
JNIEXPORT jbyteArray JNICALL
Java_be_discrip_rip_NativeRip_probe(JNIEnv *env, jclass clazz, jint fd) {
    (void) clazz;
    return take_json(env, dr_probe(fd));
}
