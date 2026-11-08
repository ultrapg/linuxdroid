#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdarg.h>
#include <stddef.h>
#include <string.h>
#include <sys/types.h>
#include <unistd.h>

int bl_status_without_tracer(const char *path, int flags, const void *caller) __attribute__((visibility("hidden")));
int bl_proc_open(int dirfd, const char *path, int flags) __attribute__((visibility("hidden")));
void bl_proc_close(int fd) __attribute__((visibility("hidden")));
int bl_writable_retry(int dirfd, const char *path, int flags) __attribute__((visibility("hidden")));
int bl_is_root_dirfd(int dirfd) __attribute__((visibility("hidden")));
const char *bl_flatpak_map_path_mode(const char *path, char *buf, size_t bufsz, int follow_final)
    __attribute__((visibility("hidden")));

static int (*real_open)(const char *, int, ...);
static int (*real_openat)(int, const char *, int, ...);
static int (*real_close)(int);

static int retry_without_noatime(int fd, int flags) {
  return fd < 0 && errno == EPERM && (flags & O_NOATIME);
}

static int is_trace_marker(const char *path) {
  return path != NULL
      && (strcmp(path, "/sys/kernel/tracing/trace_marker") == 0
          || strcmp(path, "/sys/kernel/debug/tracing/trace_marker") == 0);
}

int open(const char *path, int flags, ...) {
  va_list ap;
  mode_t mode;
  int fd;
  char vfs_buf[PATH_MAX];

  va_start(ap, flags);
  mode = va_arg(ap, mode_t);
  va_end(ap);
  path = bl_flatpak_map_path_mode(path, vfs_buf, sizeof(vfs_buf), (flags & O_NOFOLLOW) ? 0 : 1);
  if (is_trace_marker(path)) {
    errno = EACCES;
    return -1;
  }
  if ((fd = bl_status_without_tracer(path, flags, __builtin_return_address(0))) >= 0) return fd;
  if ((fd = bl_proc_open(AT_FDCWD, path, flags)) >= 0) return fd;
  if (real_open == NULL) real_open = dlsym(RTLD_NEXT, "open");
  if (real_open == NULL) {
    errno = ENOSYS;
    return -1;
  }
  fd = real_open(path, flags, mode);
  if (retry_without_noatime(fd, flags)) fd = real_open(path, flags & ~O_NOATIME, mode);
  if (fd < 0 && bl_writable_retry(AT_FDCWD, path, flags)) fd = real_open(path, flags & ~O_NOATIME, mode);
  return fd;
}

int openat(int dirfd, const char *path, int flags, ...) {
  va_list ap;
  mode_t mode;
  int fd;
  char vfs_buf[PATH_MAX];

  va_start(ap, flags);
  mode = va_arg(ap, mode_t);
  va_end(ap);
  const char *p = path;
  if (dirfd >= 0 && p && strcmp(p, "..") == 0 && bl_is_root_dirfd(dirfd)) {
    path = ".";
  }
  path = bl_flatpak_map_path_mode(path, vfs_buf, sizeof(vfs_buf), (flags & O_NOFOLLOW) ? 0 : 1);
  if (is_trace_marker(path)) {
    errno = EACCES;
    return -1;
  }
  if ((fd = bl_status_without_tracer(path, flags, __builtin_return_address(0))) >= 0) return fd;
  if ((fd = bl_proc_open(dirfd, path, flags)) >= 0) return fd;
  if (real_openat == NULL) real_openat = dlsym(RTLD_NEXT, "openat");
  if (real_openat == NULL) {
    errno = ENOSYS;
    return -1;
  }
  fd = real_openat(dirfd, path, flags, mode);
  if (retry_without_noatime(fd, flags)) fd = real_openat(dirfd, path, flags & ~O_NOATIME, mode);
  if (fd < 0 && bl_writable_retry(dirfd, path, flags)) fd = real_openat(dirfd, path, flags & ~O_NOATIME, mode);
  return fd;
}

int close(int fd) {
  if (real_close == NULL) real_close = dlsym(RTLD_NEXT, "close");
  if (real_close == NULL) {
    errno = ENOSYS;
    return -1;
  }
  bl_proc_close(fd);
  int saved = errno;
  int mode = fd >= 0 ? fcntl(fd, F_GETFL) : -1;
  int r = real_close(fd);
  if (r < 0 && errno == EIO && mode >= 0 && (mode & O_ACCMODE) == O_RDONLY) {
    errno = saved;
    return 0;
  }
  return r;
}

int open64(const char *path, int flags, ...) __attribute__((alias("open")));
int openat64(int dirfd, const char *path, int flags, ...) __attribute__((alias("openat")));

int __open_2(const char *path, int flags) {
  return open(path, flags, 0);
}
int __open64_2(const char *path, int flags) __attribute__((alias("__open_2")));

int __openat_2(int dirfd, const char *path, int flags) {
  return openat(dirfd, path, flags, 0);
}
int __openat64_2(int dirfd, const char *path, int flags) __attribute__((alias("__openat_2")));
