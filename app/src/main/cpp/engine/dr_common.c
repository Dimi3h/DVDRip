#include "dr_common.h"

#include <stdio.h>

void dr_vlogf(const dr_logger *logger, int level, const char *format, va_list args) {
    if (logger == NULL || logger->log == NULL) return;
    char message[1024];
    vsnprintf(message, sizeof(message), format, args);
    logger->log(logger->opaque, level, message);
}

void dr_logf(const dr_logger *logger, int level, const char *format, ...) {
    va_list args;
    va_start(args, format);
    dr_vlogf(logger, level, format, args);
    va_end(args);
}

void dr_set_error(char *error, size_t error_size, const char *format, ...) {
    if (error == NULL || error_size == 0) return;
    va_list args;
    va_start(args, format);
    vsnprintf(error, error_size, format, args);
    va_end(args);
}
