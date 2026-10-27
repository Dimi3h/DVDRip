/*
 * Shared definitions of the DiscRip rip engine. The engine is plain C on top of libdvdread,
 * libdvdcss and FFmpeg; it runs inside the app (through jni/) and on the build machine (through
 * host/) so the ripping logic can be tested without a phone.
 */
#ifndef DR_COMMON_H
#define DR_COMMON_H

#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>

#define DR_BLOCK_SIZE 2048

enum dr_log_level { DR_LOG_DEBUG, DR_LOG_INFO, DR_LOG_WARN, DR_LOG_ERROR };

typedef struct dr_logger {
    void *opaque;
    void (*log)(void *opaque, int level, const char *message);
} dr_logger;

void dr_logf(const dr_logger *logger, int level, const char *format, ...)
    __attribute__((format(printf, 3, 4)));

void dr_vlogf(const dr_logger *logger, int level, const char *format, va_list args);

/* Copies a formatted message into a caller's error buffer. */
void dr_set_error(char *error, size_t error_size, const char *format, ...)
    __attribute__((format(printf, 3, 4)));

#endif
