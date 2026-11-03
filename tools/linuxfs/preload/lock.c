/*
 * Ported from WinNative (maxjivi05, feature/wayland-gamescope, 2026-09-18/19), GPL-3.0.
 */

#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdatomic.h>
#include <stdio.h>
#include <sys/file.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#ifndef F_ADD_SEALS
#define F_ADD_SEALS 1033
#endif
#ifndef F_GET_SEALS
#define F_GET_SEALS 1034
#endif
#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif

static int unsupported(int error) {
  return error == ENOSYS || error == ENOTSUP || error == EOPNOTSUPP;
}

static int seal_unsupported(int error) {
  return error == EINVAL || error == EPERM || error == EACCES || unsupported(error);
}

int memfd_create(const char *name, unsigned int flags) {
  static atomic_uint seq;
  const char *tag = (name && *name) ? name : "anon";
  char safe[32];
  size_t k = 0;
  for (size_t i = 0; tag[i] && k < sizeof(safe) - 1; i++) {
    char c = tag[i];
    if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
        (c >= '0' && c <= '9') || c == '-' || c == '_') {
      safe[k++] = c;
    }
  }
  safe[k] = '\0';
  if (!safe[0]) {
    safe[0] = 'm';
    safe[1] = '\0';
  }

  int oflags = O_RDWR | O_CREAT | O_EXCL | O_NOFOLLOW;
  if (flags & MFD_CLOEXEC) oflags |= O_CLOEXEC;

  const char *dirs[] = {"/dev/shm", "/tmp"};
  for (size_t d = 0; d < 2; d++) {
    for (int attempt = 0; attempt < 16; attempt++) {
      char path[160];
      unsigned int id = atomic_fetch_add(&seq, 1U);
      snprintf(path, sizeof(path), "%s/.memfd-%s-%d-%u", dirs[d], safe, (int)getpid(), id);
      int fd = open(path, oflags, 0600);
      if (fd >= 0) {
        unlink(path);
        return fd;
      }
      if (errno != EEXIST) break;
    }
  }

  static int (*real_memfd_create)(const char *, unsigned int);
  if (real_memfd_create == NULL) real_memfd_create = dlsym(RTLD_NEXT, "memfd_create");
  if (real_memfd_create != NULL) return real_memfd_create(name, flags);
  errno = ENOSYS;
  return -1;
}

int flock(int fd, int operation) {
  static int (*real_flock)(int, int);
  int result;

  if (real_flock == NULL) real_flock = dlsym(RTLD_NEXT, "flock");
  if (real_flock == NULL) return 0;
  result = real_flock(fd, operation);
  if (result < 0 && unsupported(errno)) return 0;
  return result;
}

static int is_lock_command(int cmd) {
  switch (cmd) {
    case F_SETLK:
    case F_SETLKW:
#ifdef F_OFD_SETLK
    case F_OFD_SETLK:
#endif
#ifdef F_OFD_SETLKW
    case F_OFD_SETLKW:
#endif
      return 1;
    default:
      return 0;
  }
}

static int is_lock_query(int cmd) {
  switch (cmd) {
    case F_GETLK:
#ifdef F_OFD_GETLK
    case F_OFD_GETLK:
#endif
      return 1;
    default:
      return 0;
  }
}

int fcntl(int fd, int cmd, ...) {
  static int (*real_fcntl)(int, int, ...);
  va_list ap;
  void *arg;
  int result;

  va_start(ap, cmd);
  arg = va_arg(ap, void *);
  va_end(ap);

  if (real_fcntl == NULL) real_fcntl = dlsym(RTLD_NEXT, "fcntl");
  if (real_fcntl == NULL) {
    errno = ENOSYS;
    return -1;
  }
  result = real_fcntl(fd, cmd, arg);
  if (result < 0 && (cmd == F_ADD_SEALS || cmd == F_GET_SEALS) && seal_unsupported(errno)) {
    errno = 0;
    return 0;
  }
  if (result >= 0 || !unsupported(errno)) return result;
  if (is_lock_command(cmd)) return 0;
  if (is_lock_query(cmd) && arg != NULL) {
    ((struct flock *)arg)->l_type = F_UNLCK;
    return 0;
  }
  return result;
}

#if defined(__GLIBC__) && defined(F_SETLK64)
int fcntl64(int fd, int cmd, ...) __attribute__((alias("fcntl")));
#endif
