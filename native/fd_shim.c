// Preloaded into innoextract (LD_PRELOAD, set by gog_exec.c) for the GOG installer import.
//
// The installer's files reach innoextract as descriptors the app got from Android's file
// picker, behind links named like the files that point at /proc/self/fd/<n> (see gog_exec.c).
// Opening such a link opens the file again by its real path, and recent Android versions
// refuse that (the app may read the descriptor, not the path): Android 16 on a Galaxy S23 does,
// Android 13 on a Retroid Pocket 6 didn't. So fopen() of those links is answered here with a
// stream that reads the descriptor itself, with pread() at its own position: innoextract may
// have the same file open twice, and the streams must not move each other.
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define FD_PREFIX "/proc/self/fd/"

typedef struct {
    int fd;
    off64_t pos;
} fd_stream;

// The descriptor a path stands for (the path itself, or a link to it), or -1.
static int linked_fd(const char *path) {
    char target[256];
    const char *p = path;
    if (strncmp(path, FD_PREFIX, sizeof(FD_PREFIX) - 1) != 0) {
        ssize_t n = readlink(path, target, sizeof(target) - 1);
        if (n <= 0) return -1;
        target[n] = '\0';
        p = target;
        if (strncmp(p, FD_PREFIX, sizeof(FD_PREFIX) - 1) != 0) return -1;
    }
    char *end;
    long fd = strtol(p + sizeof(FD_PREFIX) - 1, &end, 10);
    if (*end != '\0' || fd < 0 || fd > 1023) return -1;
    return (int)fd;
}

static int stream_read(void *cookie, char *buf, int size) {
    fd_stream *s = cookie;
    ssize_t n;
    do n = pread64(s->fd, buf, (size_t)size, s->pos); while (n < 0 && errno == EINTR);
    if (n > 0) s->pos += n;
    return (int)n;
}

static fpos64_t stream_seek(void *cookie, fpos64_t offset, int whence) {
    fd_stream *s = cookie;
    off64_t base;
    if (whence == SEEK_SET) {
        base = 0;
    } else if (whence == SEEK_CUR) {
        base = s->pos;
    } else if (whence == SEEK_END) {
        struct stat64 st;
        if (fstat64(s->fd, &st) != 0) return -1;
        base = st.st_size;
    } else {
        errno = EINVAL;
        return -1;
    }
    if (base + offset < 0) {
        errno = EINVAL;
        return -1;
    }
    s->pos = base + offset;
    return s->pos;
}

static int stream_close(void *cookie) {
    free(cookie);  // the descriptor belongs to the process (fd 10+), not to this stream
    return 0;
}

static FILE *open_linked(const char *path, const char *mode) {
    if (mode[0] != 'r' || strchr(mode, '+') != NULL) return NULL;  // reading only
    int fd = linked_fd(path);
    if (fd < 0) return NULL;
    fd_stream *s = malloc(sizeof(*s));
    if (!s) return NULL;
    s->fd = fd;
    s->pos = 0;
    FILE *f = funopen64(s, stream_read, NULL, stream_seek, stream_close);
    if (!f) free(s);
    return f;
}

FILE *fopen(const char *path, const char *mode) {
    static FILE *(*real)(const char *, const char *);
    FILE *f = path && mode ? open_linked(path, mode) : NULL;
    if (f) return f;
    if (!real) real = (FILE * (*)(const char *, const char *)) dlsym(RTLD_NEXT, "fopen");
    return real(path, mode);
}

FILE *fopen64(const char *path, const char *mode) {
    static FILE *(*real)(const char *, const char *);
    FILE *f = path && mode ? open_linked(path, mode) : NULL;
    if (f) return f;
    if (!real) real = (FILE * (*)(const char *, const char *)) dlsym(RTLD_NEXT, "fopen64");
    return real(path, mode);
}
