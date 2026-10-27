/*
 * A file the engine writes a rip into, or reads a finished one back from, by position: a file
 * descriptor (dr_fd_as_file), or callbacks such as the app's SMB client on the NAS.
 */
#ifndef DR_FILE_H
#define DR_FILE_H

#include <stdint.h>

typedef struct dr_file {
    void *opaque;
    /* Writes all size bytes at position: 0, or a negative errno. */
    int (*write)(void *opaque, const uint8_t *data, int size, int64_t position);
    /* Reads up to size bytes at position: the count, 0 at the end, or a negative errno. */
    int (*read)(void *opaque, uint8_t *buffer, int size, int64_t position);
    /* The file's size: 0 or more, or a negative errno. */
    int64_t (*size)(void *opaque);
} dr_file;

#endif
