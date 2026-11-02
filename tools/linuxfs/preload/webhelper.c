#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/statvfs.h>
#include <unistd.h>

extern char *program_invocation_short_name;

volatile int __bl_sbx_chrooted = 0;
static int g_sbx_fd = -2;

__attribute__((constructor)) static void sbx_client_init(void) {
  const char *d = getenv("SBX_D");
  const char *prv = getenv("SBX_CHROME_API_PRV");
  if (d && d[0] && prv && strcmp(prv, "1") == 0) {
    char *end = NULL;
    long fd = strtol(d, &end, 10);
    if (!*end && fd >= 0 && fd < 65536) {
      g_sbx_fd = (int)fd;
    }
  }
}

__attribute__((visibility("hidden"))) int bl_sbx_denied_path(int dirfd, const char *path) {
  if (!__builtin_expect(__bl_sbx_chrooted, 0)) return 0;
  if (!path) return 0;
  if (strcmp(path, "/") == 0 || strcmp(path, ".") == 0) return 0;
  if (path[0] == '/' || dirfd == AT_FDCWD) return 1;
  return 0;
}

ssize_t read(int fd, void *buf, size_t count) {
  static ssize_t (*real_read)(int, void *, size_t);
  if (!real_read) real_read = dlsym(RTLD_NEXT, "read");
  if (!real_read) { errno = ENOSYS; return -1; }
  ssize_t n = real_read(fd, buf, count);
  if (__builtin_expect(g_sbx_fd >= 0 && fd == g_sbx_fd, 0)) {
    if (n == 1 && buf && *(const char *)buf == 'O') {
      __bl_sbx_chrooted = 1;
      g_sbx_fd = -2;
    }
  }
  return n;
}

static int is_chrome_sandbox(const char *path) {
  if (!path) return 0;
  size_t len = strlen(path);
  const char suffix[] = "/chrome-sandbox";
  const size_t slen = sizeof(suffix) - 1;
  if (len >= slen && memcmp(path + len - slen, suffix, slen) == 0) return 1;
  return strcmp(path, "chrome-sandbox") == 0;
}

static int is_chromium_task_dir(const char *path) {
  if (!path || !program_invocation_short_name) return 0;
  if (strncmp(program_invocation_short_name, "chromium", 8) != 0 &&
      strncmp(program_invocation_short_name, "chrome", 6) != 0) {
    return 0;
  }
  return strcmp(path, "self/task/") == 0 ||
         strcmp(path, "self/task") == 0 ||
         strcmp(path, "/proc/self/task/") == 0 ||
         strcmp(path, "/proc/self/task") == 0;
}

static void spoof_sandbox_stat(const char *path, struct stat *st) {
  if (!st) return;
  if (is_chrome_sandbox(path) && S_ISREG(st->st_mode)) {
    st->st_uid = 0;
    st->st_gid = 0;
    st->st_mode = (st->st_mode & ~07777) | S_ISUID | 0755;
  } else if (S_ISDIR(st->st_mode) && st->st_nlink > 3 && is_chromium_task_dir(path)) {
    st->st_nlink = 3;
  }
}

static void spoof_sandbox_statx(const char *path, struct statx *stx) {
  if (stx && is_chrome_sandbox(path) && S_ISREG(stx->stx_mode)) {
    stx->stx_uid = 0;
    stx->stx_gid = 0;
    stx->stx_mode = (stx->stx_mode & ~07777) | S_ISUID | 0755;
  }
}

__attribute__((visibility("hidden"))) char *const *bl_webhelper_argv(const char *path, char *const argv[]) {
  (void)path;
  return argv;
}

int fstatat(int dirfd, const char *path, struct stat *st, int flags) {
  static int (*real_fn)(int, const char *, struct stat *, int);
  if (bl_sbx_denied_path(dirfd, path)) { errno = ENOENT; return -1; }
  if (!real_fn) real_fn = dlsym(RTLD_NEXT, "fstatat");
  if (!real_fn) { errno = ENOSYS; return -1; }
  const char *lookup = (__builtin_expect(__bl_sbx_chrooted, 0) && path && strcmp(path, "/") == 0)
                           ? "/tmp/.linuxdroid-sbx-empty"
                           : path;
  int r = real_fn(dirfd, lookup, st, flags);
  if (r == 0) spoof_sandbox_stat(path, st);
  return r;
}

int fstatat64(int dirfd, const char *path, struct stat64 *st, int flags) {
  return fstatat(dirfd, path, (struct stat *)st, flags);
}

int stat(const char *path, struct stat *st) {
  return fstatat(AT_FDCWD, path, st, 0);
}

int stat64(const char *path, struct stat64 *st) {
  return fstatat(AT_FDCWD, path, (struct stat *)st, 0);
}

int lstat(const char *path, struct stat *st) {
  return fstatat(AT_FDCWD, path, st, AT_SYMLINK_NOFOLLOW);
}

int lstat64(const char *path, struct stat64 *st) {
  return fstatat(AT_FDCWD, path, (struct stat *)st, AT_SYMLINK_NOFOLLOW);
}

int __fxstatat(int ver, int dirfd, const char *path, struct stat *st, int flags) {
  (void)ver;
  return fstatat(dirfd, path, st, flags);
}

int __fxstatat64(int ver, int dirfd, const char *path, struct stat64 *st, int flags) {
  (void)ver;
  return fstatat(dirfd, path, (struct stat *)st, flags);
}

int __xstat(int ver, const char *path, struct stat *st) {
  (void)ver;
  return fstatat(AT_FDCWD, path, st, 0);
}

int __xstat64(int ver, const char *path, struct stat64 *st) {
  (void)ver;
  return fstatat(AT_FDCWD, path, (struct stat *)st, 0);
}

int __lxstat(int ver, const char *path, struct stat *st) {
  (void)ver;
  return fstatat(AT_FDCWD, path, st, AT_SYMLINK_NOFOLLOW);
}

int __lxstat64(int ver, const char *path, struct stat64 *st) {
  (void)ver;
  return fstatat(AT_FDCWD, path, (struct stat *)st, AT_SYMLINK_NOFOLLOW);
}

int statx(int dirfd, const char *path, int flags, unsigned int mask, struct statx *stx) {
  static int (*real_fn)(int, const char *, int, unsigned int, struct statx *);
  if (bl_sbx_denied_path(dirfd, path)) { errno = ENOENT; return -1; }
  if (!real_fn) real_fn = dlsym(RTLD_NEXT, "statx");
  if (!real_fn) { errno = ENOSYS; return -1; }
  int r = real_fn(dirfd, path, flags, mask, stx);
  if (r == 0) spoof_sandbox_statx(path, stx);
  return r;
}

int statfs(const char *path, struct statfs *buf) {
  static int (*real_fn)(const char *, struct statfs *);
  if (bl_sbx_denied_path(AT_FDCWD, path)) { errno = ENOENT; return -1; }
  if (!real_fn) real_fn = dlsym(RTLD_NEXT, "statfs");
  if (!real_fn) { errno = ENOSYS; return -1; }
  int r = real_fn(path, buf);
  if (r == 0 && is_chrome_sandbox(path)) buf->f_flags &= ~ST_NOSUID;
  return r;
}

int statfs64(const char *path, struct statfs64 *buf) {
  return statfs(path, (struct statfs *)buf);
}

int statvfs(const char *path, struct statvfs *buf) {
  static int (*real_fn)(const char *, struct statvfs *);
  if (bl_sbx_denied_path(AT_FDCWD, path)) { errno = ENOENT; return -1; }
  if (!real_fn) real_fn = dlsym(RTLD_NEXT, "statvfs");
  if (!real_fn) { errno = ENOSYS; return -1; }
  int r = real_fn(path, buf);
  if (r == 0 && is_chrome_sandbox(path)) buf->f_flag &= ~ST_NOSUID;
  return r;
}

int statvfs64(const char *path, struct statvfs64 *buf) {
  return statvfs(path, (struct statvfs *)buf);
}

int prctl(int option, ...) {
  static int (*real_prctl)(int, unsigned long, unsigned long, unsigned long, unsigned long);
  static volatile int g_non_dumpable = 0;
  va_list ap;
  va_start(ap, option);
  unsigned long a2 = va_arg(ap, unsigned long);
  unsigned long a3 = va_arg(ap, unsigned long);
  unsigned long a4 = va_arg(ap, unsigned long);
  unsigned long a5 = va_arg(ap, unsigned long);
  va_end(ap);
  if (!real_prctl) real_prctl = dlsym(RTLD_NEXT, "prctl");
  if (!real_prctl) { errno = ENOSYS; return -1; }
  if (option == PR_SET_DUMPABLE) {
    g_non_dumpable = (a2 == 0);
    int r = real_prctl(option, a2, a3, a4, a5);
    if (r < 0) {
      errno = 0;
      return 0;
    }
    return r;
  }
  if (option == PR_GET_DUMPABLE && g_non_dumpable) {
    return 0;
  }
  return real_prctl(option, a2, a3, a4, a5);
}
