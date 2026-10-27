#include "dr_fd.h"

#include <errno.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <unistd.h>

/* arm64 Android and the 64-bit build machine: plain lseek() and pread() take 64-bit offsets. */
_Static_assert(sizeof(off_t) == 8, "64-bit file offsets required");

int dr_fd_image_init(dr_fd_image *image, int fd) {
    struct stat info;
    if (fstat(fd, &info) != 0) return -1;
    image->fd = fd;
    image->blocks = (uint64_t) info.st_size / DR_BLOCK_SIZE;
    return 0;
}

int dr_fd_image_read(void *opaque, uint64_t lba, int count, uint8_t *buffer) {
    const dr_fd_image *image = opaque;
    if (lba >= image->blocks) return 0;
    if (lba + (uint64_t) count > image->blocks) count = (int) (image->blocks - lba);
    size_t wanted = (size_t) count * DR_BLOCK_SIZE;
    size_t done = 0;
    while (done < wanted) {
        ssize_t got = pread(image->fd, buffer + done, wanted - done, (off_t) (lba * DR_BLOCK_SIZE + done));
        if (got < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (got == 0) break;
        done += (size_t) got;
    }
    return (int) (done / DR_BLOCK_SIZE);
}

typedef struct fd_file {
    int fd;
} fd_file;

void *dr_fd_file_wrap(int fd) {
    fd_file *file = malloc(sizeof(fd_file));
    if (file == NULL) {
        close(fd);
        return NULL;
    }
    file->fd = fd;
    return file;
}

ssize_t dr_fd_file_read(void *file, char *buffer, size_t size) {
    ssize_t got;
    do {
        got = read(((fd_file *) file)->fd, buffer, size);
    } while (got < 0 && errno == EINTR);
    return got;
}

off64_t dr_fd_file_seek(void *file, off64_t offset, int whence) {
    return lseek(((fd_file *) file)->fd, (off_t) offset, whence);
}

int dr_fd_file_close(void *file) {
    int result = close(((fd_file *) file)->fd);
    free(file);
    return result;
}
