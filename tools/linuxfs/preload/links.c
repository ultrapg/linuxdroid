#define _GNU_SOURCE
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static int refused(int error) { return error == EPERM || error == EACCES; }

static int is_l2s_entry(const char *name) {
  return strncmp(name, ".l2s.", 5) == 0 || strncmp(name, ".proot.l2s.", 11) == 0;
}

static int moves_instead(void) {
  const char *v = getenv("LINUXDROID_LINK_RENAME");
  return v != NULL && strcmp(v, "1") == 0;
}

static int has_suffix(const char *path, size_t n, const char *suf) {
  size_t m = strlen(suf);
  return n > m && memcmp(path + n - m, suf, m) == 0;
}

static int lock_name(const char *path) {
  if (!path) return 0;
  size_t n = strlen(path);
  return has_suffix(path, n, "-l") ||
         has_suffix(path, n, "-n") ||
         has_suffix(path, n, ".lock") ||
         has_suffix(path, n, ".lk") ||
         has_suffix(path, n, ".lck") ||
         has_suffix(path, n, "-lock");
}

static int fallback_symlink_lock(int olddirfd, const char *oldpath, int newdirfd, const char *newpath) {
  static int (*real_symlinkat)(const char *, int, const char *);
  if (!oldpath || !newpath) return -1;
  if (real_symlinkat == NULL) real_symlinkat = dlsym(RTLD_NEXT, "symlinkat");
  if (oldpath[0] == '/' || (olddirfd == newdirfd && strchr(newpath, '/') == NULL)) {
    return real_symlinkat(oldpath, newdirfd, newpath);
  }
  if (olddirfd == AT_FDCWD) {
    char cwd[PATH_MAX];
    char resolved[PATH_MAX];
    if (getcwd(cwd, sizeof(cwd)) != NULL) {
      int n = snprintf(resolved, sizeof(resolved), "%s/%s", cwd, oldpath);
      if (n > 0 && (size_t)n < sizeof(resolved)) {
        return real_symlinkat(resolved, newdirfd, newpath);
      }
    }
  }
  return real_symlinkat(oldpath, newdirfd, newpath);
}

__attribute__((visibility("hidden"))) int bl_link_lock(const char *oldpath, const char *newpath, int result) {
  if (result == 0 || !refused(errno)) return result;
  int saved_errno = errno;
  if (moves_instead()) {
    int r = renameat2(AT_FDCWD, oldpath, AT_FDCWD, newpath, RENAME_NOREPLACE);
    if (r == 0) return 0;
    errno = saved_errno;
    return result;
  }
  if (!lock_name(newpath)) {
    errno = saved_errno;
    return result;
  }
  int r = fallback_symlink_lock(AT_FDCWD, oldpath, AT_FDCWD, newpath);
  if (r != 0 && errno != EEXIST) errno = saved_errno;
  return r;
}

int link(const char *oldpath, const char *newpath) {
  static int (*real_link)(const char *, const char *);
  if (real_link == NULL) real_link = dlsym(RTLD_NEXT, "link");
  return bl_link_lock(oldpath, newpath, real_link(oldpath, newpath));
}

int linkat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath, int flags) {
  static int (*real_linkat)(int, const char *, int, const char *, int);
  if (real_linkat == NULL) real_linkat = dlsym(RTLD_NEXT, "linkat");
  int result = real_linkat(olddirfd, oldpath, newdirfd, newpath, flags);
  if (result == 0 || !refused(errno)) return result;
  int saved_errno = errno;
  if (flags == 0 && moves_instead()) {
    int r = renameat2(olddirfd, oldpath, newdirfd, newpath, RENAME_NOREPLACE);
    if (r == 0) return 0;
    errno = saved_errno;
    return result;
  }
  if (flags == 0 && lock_name(newpath)) {
    int r = fallback_symlink_lock(olddirfd, oldpath, newdirfd, newpath);
    if (r != 0 && errno != EEXIST) errno = saved_errno;
    return r;
  }
  errno = saved_errno;
  return result;
}

static int is_real_symlink_at(int dfd, const char *name) {
  char target[PATH_MAX];
  ssize_t n = readlinkat(dfd, name, target, sizeof(target) - 1);
  if (n <= 0) return 0;
  target[n] = '\0';
  const char *base = strrchr(target, '/');
  base = base ? base + 1 : target;
  return !is_l2s_entry(base);
}

struct dirent *readdir(DIR *dirp) {
  static struct dirent *(*real_readdir)(DIR *);
  if (real_readdir == NULL) real_readdir = dlsym(RTLD_NEXT, "readdir");
  int saved_errno = errno;
  for (;;) {
    struct dirent *ent = real_readdir(dirp);
    if (ent == NULL) return NULL;
    if (is_l2s_entry(ent->d_name)) {
      errno = saved_errno;
      continue;
    }
    if (ent->d_type == DT_LNK) {
      int dfd = dirfd(dirp);
      if (dfd >= 0 && !is_real_symlink_at(dfd, ent->d_name)) {
        struct stat st;
        if (fstatat(dfd, ent->d_name, &st, AT_SYMLINK_NOFOLLOW) == 0) {
          ent->d_type = IFTODT(st.st_mode);
          ent->d_ino = st.st_ino;
        }
      }
      errno = saved_errno;
    }
    return ent;
  }
}

struct dirent64 *readdir64(DIR *dirp) {
  static struct dirent64 *(*real_readdir64)(DIR *);
  if (real_readdir64 == NULL) real_readdir64 = dlsym(RTLD_NEXT, "readdir64");
  int saved_errno = errno;
  for (;;) {
    struct dirent64 *ent = real_readdir64(dirp);
    if (ent == NULL) return NULL;
    if (is_l2s_entry(ent->d_name)) {
      errno = saved_errno;
      continue;
    }
    if (ent->d_type == DT_LNK) {
      int dfd = dirfd(dirp);
      if (dfd >= 0 && !is_real_symlink_at(dfd, ent->d_name)) {
        struct stat64 st;
        if (fstatat64(dfd, ent->d_name, &st, AT_SYMLINK_NOFOLLOW) == 0) {
          ent->d_type = IFTODT(st.st_mode);
          ent->d_ino = st.st_ino;
        }
      }
      errno = saved_errno;
    }
    return ent;
  }
}
