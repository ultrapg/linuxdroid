#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static int grant_owner_write(int dirfd, const char *path) {
  struct stat st;
  if (fstatat(dirfd, path, &st, 0) != 0 || (st.st_mode & S_IWUSR)) return 0;
  return fchmodat(dirfd, path, (st.st_mode & 07777) | S_IWUSR, 0) == 0;
}

__attribute__((visibility("hidden")))
int bl_writable_retry(int dirfd, const char *path, int flags) {
  int saved = errno;
  int granted = 0;
  char parent[PATH_MAX];
  const char *slash;

  if (saved != EACCES || path == NULL || (flags & O_ACCMODE) == O_RDONLY) return 0;
  if (faccessat(dirfd, path, F_OK, 0) == 0) {
    granted = grant_owner_write(dirfd, path);
  } else if (flags & O_CREAT) {
    slash = strrchr(path, '/');
    if (slash == NULL) {
      granted = grant_owner_write(dirfd, ".");
    } else if ((size_t)(slash - path) < sizeof(parent)) {
      memcpy(parent, path, slash - path);
      parent[slash - path] = '\0';
      granted = grant_owner_write(dirfd, parent[0] ? parent : "/");
    }
  }
  errno = saved;
  return granted;
}

__attribute__((visibility("hidden")))
int bl_writable_access(int dirfd, const char *path, int mode, int flags) {
  int saved = errno;
  struct stat st;
  int owned;

  if (saved != EACCES || path == NULL || !(mode & W_OK)) return 0;
  owned = fstatat(dirfd, path, &st, flags & AT_SYMLINK_NOFOLLOW) == 0 && st.st_uid == geteuid();
  errno = saved;
  return owned;
}
