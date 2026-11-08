#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stddef.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

__attribute__((visibility("hidden"))) int bl_binfmt_runnable(const char *path, int mode);
__attribute__((visibility("hidden"))) int bl_writable_access(int dirfd, const char *path, int mode, int flags);
__attribute__((visibility("hidden"))) const char *bl_flatpak_map_path_mode(const char *path, char *buf, size_t bufsz,
                                                                            int follow_final);

static int raw_faccessat(int dirfd, const char *path, int mode, int flags) {
  if (!path) {
    errno = EFAULT;
    return -1;
  }
  struct stat st;
  int st_flags = (flags & AT_SYMLINK_NOFOLLOW) ? AT_SYMLINK_NOFOLLOW : 0;
  if (syscall(SYS_newfstatat, dirfd, path, &st, st_flags) != 0) return -1;
  if (mode == F_OK || S_ISLNK(st.st_mode)) return 0;
  uid_t uid = (flags & AT_EACCESS) ? geteuid() : getuid();
  if (uid == 0) {
    if ((mode & X_OK) && !S_ISDIR(st.st_mode) && (st.st_mode & 0111) == 0) {
      errno = EACCES;
      return -1;
    }
    return 0;
  }
  unsigned int perm;
  if (st.st_uid == uid) {
    perm = (st.st_mode >> 6) & 7;
  } else if (st.st_gid == ((flags & AT_EACCESS) ? getegid() : getgid())) {
    perm = (st.st_mode >> 3) & 7;
  } else {
    perm = st.st_mode & 7;
  }
  unsigned int need = ((mode & R_OK) ? 4u : 0u) | ((mode & W_OK) ? 2u : 0u) | ((mode & X_OK) ? 1u : 0u);
  if ((perm & need) != need) {
    errno = EACCES;
    return -1;
  }
  return 0;
}

static int writable_as_owner(int dirfd, const char *path, int mode, int flags) {
  if (!bl_writable_access(dirfd, path, mode, flags)) return -1;
  if ((mode & ~W_OK) == 0 || raw_faccessat(dirfd, path, mode & ~W_OK, flags) == 0) return 0;
  return -1;
}

static int check_cwd(const char *path, int mode, int flags) {
  char vfs_buf[PATH_MAX];
  path = bl_flatpak_map_path_mode(path, vfs_buf, sizeof(vfs_buf), (flags & AT_SYMLINK_NOFOLLOW) ? 0 : 1);
  int result = raw_faccessat(AT_FDCWD, path, mode, flags);
  if (result == 0) return 0;
  int err = errno;
  if (err == EACCES && bl_binfmt_runnable(path, mode)) return 0;
  errno = err;
  if (writable_as_owner(AT_FDCWD, path, mode, flags) == 0) return 0;
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
  if (raw_faccessat(dirfd, path, mode, flags) == 0) return 0;
  int err = errno;
  if (writable_as_owner(dirfd, path, mode, flags) == 0) return 0;
  errno = err;
  return -1;
}
