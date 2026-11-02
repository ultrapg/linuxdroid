#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/statvfs.h>
#include <unistd.h>

#if defined(__aarch64__) || defined(NOEXEC_TEST)

static int in_fex(void) {
  static int known = -1;
  if (known < 0) {
    char exe[512];
    ssize_t n = readlink("/proc/self/exe", exe, sizeof exe - 1);
    exe[n > 0 ? n : 0] = 0;
    const char *name = strrchr(exe, '/');
    name = name ? name + 1 : exe;
    known = !strcmp(name, "FEX") || !strcmp(name, "FEXInterpreter") || !strcmp(name, "FEXLoader");
  }
  return known;
}

__attribute__((visibility("hidden"))) int bl_noexec_retry(int prot, int flags, int fd) {
  int error = errno;
  struct statvfs vfs;
  int retry = (error == EACCES || error == EPERM) && (prot & PROT_EXEC) && fd >= 0 && !(flags & MAP_ANONYMOUS) &&
              in_fex() && fstatvfs(fd, &vfs) == 0 && (vfs.f_flag & ST_NOEXEC);
  errno = error;
  return retry;
}

static int (*real_mprotect)(void *, size_t, int);
static void *(*real_mmap)(void *, size_t, int, int, int, off_t);
static void *(*real_mmap64)(void *, size_t, int, int, int, off64_t);

__attribute__((constructor)) static void noexec_init(void) {
  real_mprotect = (int (*)(void *, size_t, int))dlsym(RTLD_NEXT, "mprotect");
  real_mmap = (void *(*)(void *, size_t, int, int, int, off_t))dlsym(RTLD_NEXT, "mmap");
  real_mmap64 = (void *(*)(void *, size_t, int, int, int, off64_t))dlsym(RTLD_NEXT, "mmap64");
}

int mprotect(void *addr, size_t len, int prot) {
  if (!real_mprotect) noexec_init();
  int result = real_mprotect(addr, len, prot);
  if (result && errno == EACCES && (prot & PROT_EXEC) && in_fex()) {
    result = real_mprotect(addr, len, prot & ~PROT_EXEC);
    if (result) errno = EACCES;
  }
  return result;
}

void *mmap(void *addr, size_t len, int prot, int flags, int fd, off_t off) {
  if (!real_mmap) noexec_init();
  void *p = real_mmap(addr, len, prot, flags, fd, off);
  if (p == MAP_FAILED && bl_noexec_retry(prot, flags, fd))
    p = real_mmap(addr, len, prot & ~PROT_EXEC, flags, fd, off);
  return p;
}

void *mmap64(void *addr, size_t len, int prot, int flags, int fd, off64_t off) {
  if (!real_mmap64) noexec_init();
  void *p = real_mmap64(addr, len, prot, flags, fd, off);
  if (p == MAP_FAILED && bl_noexec_retry(prot, flags, fd))
    p = real_mmap64(addr, len, prot & ~PROT_EXEC, flags, fd, off);
  return p;
}

#else

__attribute__((visibility("hidden"))) int bl_noexec_retry(int prot, int flags, int fd) {
  (void)prot, (void)flags, (void)fd;
  return 0;
}

#endif
