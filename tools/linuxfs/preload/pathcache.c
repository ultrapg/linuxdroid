#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stddef.h>
#include <unistd.h>

__attribute__((visibility("hidden"))) int bl_binfmt_runnable(const char *path, int mode);
__attribute__((visibility("hidden"))) int bl_writable_access(int dirfd, const char *path, int mode, int flags);
__attribute__((visibility("hidden"))) const char *bl_flatpak_map_path_mode(const char *path, char *buf, size_t bufsz,
                                                                            int follow_final);

static int (*next_faccessat(void))(int, const char *, int, int) {
  static int (*real)(int, const char *, int, int);
  if (real == NULL) real = (int (*)(int, const char *, int, int))dlsym(RTLD_NEXT, "faccessat");
  return real;
}

static int writable_as_owner(int (*real)(int, const char *, int, int), int dirfd, const char *path, int mode, int flags) {
  if (!bl_writable_access(dirfd, path, mode, flags)) return -1;
  if ((mode & ~W_OK) == 0 || real(dirfd, path, mode & ~W_OK, flags) == 0) return 0;
  return -1;
}

static int check_cwd(const char *path, int mode, int flags) {
  char vfs_buf[PATH_MAX];
  path = bl_flatpak_map_path_mode(path, vfs_buf, sizeof(vfs_buf), (flags & AT_SYMLINK_NOFOLLOW) ? 0 : 1);
  int (*real)(int, const char *, int, int) = next_faccessat();
  if (real == NULL) {
    errno = ENOSYS;
    return -1;
  }
  int result = real(AT_FDCWD, path, mode, flags);
  if (result == 0) return 0;
  int err = errno;
  if (err == EACCES && bl_binfmt_runnable(path, mode)) return 0;
  errno = err;
  if (writable_as_owner(real, AT_FDCWD, path, mode, flags) == 0) return 0;
  errno = err;
  return result;
}

int access(const char *path, int mode) {
  return check_cwd(path, mode, 0);
}

int euidaccess(const char *path, int mode) {
  return check_cwd(path, mode, AT_EACCESS);
}

int eaccess(const char *path, int mode) {
  return check_cwd(path, mode, AT_EACCESS);
}

int faccessat(int dirfd, const char *path, int mode, int flags) {
  if (dirfd == AT_FDCWD) return check_cwd(path, mode, flags);
  char vfs_buf[PATH_MAX];
  path = bl_flatpak_map_path_mode(path, vfs_buf, sizeof(vfs_buf), (flags & AT_SYMLINK_NOFOLLOW) ? 0 : 1);
  int (*real)(int, const char *, int, int) = next_faccessat();
  if (real == NULL) {
    errno = ENOSYS;
    return -1;
  }
  if (real(dirfd, path, mode, flags) == 0) return 0;
  int err = errno;
  if (writable_as_owner(real, dirfd, path, mode, flags) == 0) return 0;
  errno = err;
  return -1;
}
