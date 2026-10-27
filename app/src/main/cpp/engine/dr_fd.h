/*
 * Plain file descriptors as DVD sources: an image read with pread(), and the files of a
 * filesystem callback set, once the caller has opened them. Also the MKV a rip writes.
 */
#ifndef DR_FD_H
#define DR_FD_H

#include "dr_common.h"
#include "dr_file.h"

#include <dvdread/dvd_filesystem.h>

typedef struct dr_fd_image {
    int fd;
    uint64_t blocks;
} dr_fd_image;

/* Sizes up the image on fd (which stays the caller's). Returns 0, or -1 with errno set. */
int dr_fd_image_init(dr_fd_image *image, int fd);

/* dr_block_source.read_blocks over a dr_fd_image. */
int dr_fd_image_read(void *opaque, uint64_t lba, int count, uint8_t *buffer);

/* A dvd_reader_filesystem_h file handle that owns fd, or NULL (fd closed) when out of memory. */
void *dr_fd_file_wrap(int fd);
ssize_t dr_fd_file_read(void *file, char *buffer, size_t size);
off64_t dr_fd_file_seek(void *file, off64_t offset, int whence);
int dr_fd_file_close(void *file);

/* A dr_file over fd, which stays the caller's: pwrite(), pread() and fstat(). */
dr_file dr_fd_as_file(int fd);

#endif
