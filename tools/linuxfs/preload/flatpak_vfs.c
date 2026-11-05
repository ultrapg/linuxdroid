#define _GNU_SOURCE
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#define MAX_FP_EXTS 16

struct fp_ext_map {
  char virt[256];
  char real[PATH_MAX];
  size_t vlen;
};

static pthread_once_t vfs_once = PTHREAD_ONCE_INIT;
static int vfs_active;
static int vfs_exec_active;
static int vfs_has_lib64;
static char vfs_usr[PATH_MAX];
static char vfs_app[PATH_MAX];
static char vfs_info[PATH_MAX];
static char vfs_sbx[PATH_MAX];
static char vfs_exec[PATH_MAX];
static struct fp_ext_map vfs_exts[MAX_FP_EXTS];
static int vfs_nexts;

static int vfs_under(const char *path, const char *dir, size_t dlen) {
  if (dlen == 0) return 0;
  if (dlen == 1 && dir[0] == '/') return path[0] == '/';
  return strncmp(path, dir, dlen) == 0 && (path[dlen] == '\0' || path[dlen] == '/');
}

static void copy_trimmed_dir(char *dst, size_t cap, const char *src) {
  if (!src || src[0] != '/') {
    dst[0] = '\0';
    return;
  }
  size_t len = strlen(src);
  while (len > 1 && src[len - 1] == '/') len--;
  if (len >= cap) {
    dst[0] = '\0';
    return;
  }
  memcpy(dst, src, len);
  dst[len] = '\0';
}

static void parse_exts(const char *spec) {
  if (!spec || !*spec) return;
  const char *p = spec;
  while (*p && vfs_nexts < MAX_FP_EXTS) {
    const char *end = strchrnul(p, ':');
    const char *eq = memchr(p, '=', (size_t)(end - p));
    if (eq && eq > p && end > eq + 1) {
      size_t vlen = (size_t)(eq - p);
      size_t rlen = (size_t)(end - (eq + 1));
      while (vlen > 1 && p[vlen - 1] == '/') vlen--;
      while (rlen > 1 && eq[1 + rlen - 1] == '/') rlen--;
      if (p[0] == '/' && eq[1] == '/' && vlen < sizeof(vfs_exts[0].virt) && rlen < sizeof(vfs_exts[0].real)) {
        struct fp_ext_map item;
        memcpy(item.virt, p, vlen);
        item.virt[vlen] = '\0';
        memcpy(item.real, eq + 1, rlen);
        item.real[rlen] = '\0';
        item.vlen = vlen;
        /* Insert longest virt prefix first */
        int pos = vfs_nexts;
        while (pos > 0 && vfs_exts[pos - 1].vlen < item.vlen) {
          vfs_exts[pos] = vfs_exts[pos - 1];
          pos--;
        }
        vfs_exts[pos] = item;
        vfs_nexts++;
      }
    }
    if (!*end) break;
    p = end + 1;
  }
}

static int maps_has_any_flatpak(const char *u, const char *a, const char *ex, int *has_exec) {
  char maps[16384];
  int fd = (int)syscall(SYS_openat, AT_FDCWD, "/proc/self/maps", O_RDONLY | O_CLOEXEC, 0);
  if (fd < 0) return 0;
  ssize_t total = 0;
  while ((size_t)total < sizeof(maps) - 1) {
    ssize_t r = syscall(SYS_read, fd, maps + total, sizeof(maps) - 1 - (size_t)total);
    if (r <= 0) break;
    total += r;
  }
  syscall(SYS_close, fd);
  maps[total > 0 ? total : 0] = '\0';
  if (has_exec) {
    *has_exec = (ex && ex[0] && strstr(maps, ex) != NULL) ? 1 : 0;
  }
  if (strstr(maps, "/flatpak/runtime/") != NULL || strstr(maps, "/flatpak/app/") != NULL) return 1;
  if (u && u[0] && strstr(maps, u) != NULL) return 1;
  if (a && a[0] && strstr(maps, a) != NULL) return 1;
  return 0;
}

static void vfs_init(void) {
  const char *u = getenv("BL_FLATPAK_USR");
  const char *a = getenv("BL_FLATPAK_APP");
  const char *info = getenv("BL_FLATPAK_INFO");
  const char *sbx = getenv("BL_FLATPAK_SANDBOX_DIR");
  const char *ex = getenv("BL_FLATPAK_EXEC");
  const char *exts = getenv("BL_FLATPAK_EXTS");
  const char *force = getenv("BL_FLATPAK_FORCE_VFS");

  if ((!u || !*u) && (!a || !*a) && (!info || !*info)) return;

  copy_trimmed_dir(vfs_usr, sizeof(vfs_usr), u);
  if (strcmp(vfs_usr, "/usr") == 0 || vfs_under(vfs_usr, "/usr", 4)) {
    vfs_usr[0] = '\0';
  }
  copy_trimmed_dir(vfs_app, sizeof(vfs_app), a);
  if (strcmp(vfs_app, "/app") == 0 || vfs_under(vfs_app, "/app", 4)) {
    vfs_app[0] = '\0';
  }
  copy_trimmed_dir(vfs_info, sizeof(vfs_info), info);
  copy_trimmed_dir(vfs_sbx, sizeof(vfs_sbx), sbx);
  copy_trimmed_dir(vfs_exec, sizeof(vfs_exec), ex);
  parse_exts(exts);

  char raw_exe[PATH_MAX];
  ssize_t n = syscall(SYS_readlinkat, AT_FDCWD, "/proc/self/exe", raw_exe, sizeof(raw_exe) - 1);
  if (n > 0) {
    raw_exe[n] = '\0';
  } else {
    raw_exe[0] = '\0';
  }

  int forced = force && strcmp(force, "1") == 0;
  int maps_matched_exec = 0;
  int exe_in_flatpak = forced;
  if (!exe_in_flatpak && raw_exe[0]) {
    if (strstr(raw_exe, "/flatpak/runtime/") != NULL || strstr(raw_exe, "/flatpak/app/") != NULL) {
      exe_in_flatpak = 1;
    } else if (vfs_usr[0] && vfs_under(raw_exe, vfs_usr, strlen(vfs_usr))) {
      exe_in_flatpak = 1;
    } else if (vfs_app[0] && vfs_under(raw_exe, vfs_app, strlen(vfs_app))) {
      exe_in_flatpak = 1;
    }
  }
  if (!exe_in_flatpak || vfs_exec[0]) {
    int in_maps = maps_has_any_flatpak(vfs_usr, vfs_app, vfs_exec, &maps_matched_exec);
    if (!exe_in_flatpak) exe_in_flatpak = in_maps;
  }
  if (!exe_in_flatpak) return;

  if (vfs_usr[0]) {
    char lib64_probe[PATH_MAX];
    struct stat st;
    int m = snprintf(lib64_probe, sizeof(lib64_probe), "%s/lib64", vfs_usr);
    if (m > 0 && (size_t)m < sizeof(lib64_probe) &&
        syscall(SYS_newfstatat, AT_FDCWD, lib64_probe, &st, 0) == 0 && S_ISDIR(st.st_mode)) {
      vfs_has_lib64 = 1;
    }
  }

  if (vfs_exec[0]) {
    const char *base = strrchr(raw_exe, '/');
    base = base ? base + 1 : raw_exe;
    if (forced || maps_matched_exec || strncmp(base, "ld-linux", 8) == 0 || strcmp(raw_exe, vfs_exec) == 0) {
      vfs_exec_active = 1;
    }
  }
  __atomic_store_n(&vfs_active, 1, __ATOMIC_RELEASE);
}

__attribute__((visibility("hidden"))) int bl_flatpak_is_active(void) {
  extern char **environ;
  if (!environ) return 0;
  pthread_once(&vfs_once, vfs_init);
  return __atomic_load_n(&vfs_active, __ATOMIC_ACQUIRE);
}

static int fastpath_active(void) {
  static int (*fn)(void);
  static int looked_up;
  if (!__atomic_load_n(&looked_up, __ATOMIC_ACQUIRE)) {
    fn = (int (*)(void))dlsym(RTLD_DEFAULT, "bl_fastpath_is_active");
    __atomic_store_n(&looked_up, 1, __ATOMIC_RELEASE);
  }
  return fn ? fn() : 0;
}

static int vfs_is_l2s_name(const char *p) {
  const char *b = strrchr(p, '/');
  b = b ? b + 1 : p;
  return strncmp(b, ".l2s.", 5) == 0 || strncmp(b, ".proot.l2s.", 11) == 0;
}

static size_t vfs_sandbox_root_len(const char *p) {
  if (!p || p[0] != '/') return 0;
  for (int i = 0; i < vfs_nexts; i++) {
    size_t rlen = strlen(vfs_exts[i].real);
    if (rlen > 0 && vfs_under(p, vfs_exts[i].real, rlen)) return rlen;
  }
  if (vfs_app[0]) {
    size_t alen = strlen(vfs_app);
    if (vfs_under(p, vfs_app, alen)) return alen;
  }
  if (vfs_usr[0]) {
    size_t ulen = strlen(vfs_usr);
    if (vfs_under(p, vfs_usr, ulen)) return ulen;
  }
  return 0;
}

static const char *vfs_map_once(const char *path, char *buf, size_t bufsz) {
  if (!path || path[0] != '/') return path;
  char c1 = path[1];
  if (c1 != '.' && c1 != 'a' && c1 != 'u' && c1 != 'b' && c1 != 's' && c1 != 'l' && c1 != 'r' && c1 != 'e') {
    return path;
  }

  if (strcmp(path, "/.flatpak-info") == 0 && vfs_info[0]) {
    return vfs_info;
  }

  if (vfs_under(path, "/run/host", 9)) {
    if (strcmp(path, "/run/host/fonts") == 0 || strncmp(path, "/run/host/fonts/", 16) == 0) {
      int n = snprintf(buf, bufsz, "/usr/share/fonts%s", path + 15);
      return (n > 0 && (size_t)n < bufsz) ? buf : path;
    }
    if (strcmp(path, "/run/host/local-fonts") == 0 || strncmp(path, "/run/host/local-fonts/", 22) == 0) {
      int n = snprintf(buf, bufsz, "/usr/local/share/fonts%s", path + 21);
      return (n > 0 && (size_t)n < bufsz) ? buf : path;
    }
    if (strcmp(path, "/run/host/os-release") == 0) {
      return "/etc/os-release";
    }
    return path[9] ? (path + 9) : "/";
  }

  for (int i = 0; i < vfs_nexts; i++) {
    if (vfs_under(path, vfs_exts[i].virt, vfs_exts[i].vlen)) {
      int n = snprintf(buf, bufsz, "%s%s", vfs_exts[i].real, path + vfs_exts[i].vlen);
      return (n > 0 && (size_t)n < bufsz) ? buf : path;
    }
  }

  if (strcmp(path, "/usr/bin/bwrap") == 0 || strcmp(path, "/bin/bwrap") == 0) {
    return "/usr/local/bin/bwrap";
  }
  if (strcmp(path, "/usr/bin/flatpak-spawn") == 0 || strcmp(path, "/bin/flatpak-spawn") == 0) {
    return "/usr/local/bin/flatpak-spawn";
  }

  if (vfs_app[0] && vfs_under(path, "/app", 4)) {
    int n = snprintf(buf, bufsz, "%s%s", vfs_app, path + 4);
    return (n > 0 && (size_t)n < bufsz) ? buf : path;
  }

  if (vfs_usr[0]) {
    if (vfs_under(path, "/usr", 4) && !vfs_under(path, "/usr/local", 10)) {
      int n = snprintf(buf, bufsz, "%s%s", vfs_usr, path + 4);
      return (n > 0 && (size_t)n < bufsz) ? buf : path;
    }
    if (vfs_under(path, "/bin", 4)) {
      int n = snprintf(buf, bufsz, "%s/bin%s", vfs_usr, path + 4);
      return (n > 0 && (size_t)n < bufsz) ? buf : path;
    }
    if (vfs_under(path, "/sbin", 5)) {
      int n = snprintf(buf, bufsz, "%s/sbin%s", vfs_usr, path + 5);
      return (n > 0 && (size_t)n < bufsz) ? buf : path;
    }
    if (vfs_under(path, "/lib", 4)) {
      int n = snprintf(buf, bufsz, "%s/lib%s", vfs_usr, path + 4);
      return (n > 0 && (size_t)n < bufsz) ? buf : path;
    }
    if (vfs_under(path, "/lib64", 6)) {
      int n = snprintf(buf, bufsz, "%s/%s%s", vfs_usr, vfs_has_lib64 ? "lib64" : "lib", path + 6);
      return (n > 0 && (size_t)n < bufsz) ? buf : path;
    }
  }

  if (vfs_sbx[0] && strncmp(path, "/etc/ld.so.", 11) == 0) {
    const char *sub = path + 5;
    if (strcmp(sub, "ld.so.conf") == 0 || strcmp(sub, "ld.so.cache") == 0) {
      int n = snprintf(buf, bufsz, "%s/%s", vfs_sbx, sub);
      if (n > 0 && (size_t)n < bufsz && syscall(SYS_faccessat, AT_FDCWD, buf, F_OK) == 0) {
        return buf;
      }
    }
  }

  return path;
}

static int vfs_join_link(const char *from_path, const char *link_target, char *out, size_t outsz) {
  if (link_target[0] == '/') {
    char tmp[PATH_MAX];
    const char *m = vfs_map_once(link_target, tmp, sizeof(tmp));
    int n = snprintf(out, outsz, "%s", m);
    return (n > 0 && (size_t)n < outsz) ? 0 : -1;
  }
  const char *slash = strrchr(from_path, '/');
  if (!slash) return -1;
  size_t dlen = slash == from_path ? 1 : (size_t)(slash - from_path);
  size_t tlen = strlen(link_target);
  if (dlen + 1 + tlen >= outsz) return -1;
  memcpy(out, from_path, dlen);
  if (dlen > 1 || out[0] != '/') out[dlen++] = '/';
  memcpy(out + dlen, link_target, tlen + 1);
  return 0;
}

__attribute__((visibility("hidden"))) const char *bl_flatpak_map_path_mode(const char *path, char *buf, size_t bufsz,
                                                                            int follow_final) {
  if (!path || path[0] != '/') return path;
  char c1 = path[1];
  if (c1 != '.' && c1 != 'a' && c1 != 'u' && c1 != 'b' && c1 != 's' && c1 != 'l' && c1 != 'r' && c1 != 'e' &&
      c1 != 'h' && c1 != 'v') {
    return path;
  }
  if (!bl_flatpak_is_active()) return path;

  const char *mapped = vfs_map_once(path, buf, bufsz);
  if (fastpath_active()) return mapped;

  size_t root_prefix = vfs_sandbox_root_len(mapped);
  if (root_prefix == 0) return mapped;

  int saved_errno = errno;
  char cur[PATH_MAX];
  int cn = snprintf(cur, sizeof(cur), "%s", mapped);
  if (cn <= 0 || (size_t)cn >= sizeof(cur)) {
    errno = saved_errno;
    return mapped;
  }

  for (int hop = 0; hop < 8; hop++) {
    char linkbuf[PATH_MAX];
    if (follow_final) {
      ssize_t n = syscall(SYS_readlinkat, AT_FDCWD, cur, linkbuf, sizeof(linkbuf) - 1);
      if (n > 0) {
        linkbuf[n] = '\0';
        if (vfs_is_l2s_name(linkbuf)) break;
        char next[PATH_MAX];
        if (vfs_join_link(cur, linkbuf, next, sizeof(next)) < 0) break;
        memcpy(cur, next, strlen(next) + 1);
        root_prefix = vfs_sandbox_root_len(cur);
        if (root_prefix == 0) break;
        continue;
      }
      if (errno != ENOENT) break;
    } else {
      const char *last_slash = strrchr(cur, '/');
      if (!last_slash || (size_t)(last_slash - cur) <= root_prefix) break;
      char parent[PATH_MAX];
      size_t plen = (size_t)(last_slash - cur);
      memcpy(parent, cur, plen);
      parent[plen] = '\0';
      if (syscall(SYS_faccessat, AT_FDCWD, parent, F_OK) == 0) break;
      if (errno != ENOENT) break;
    }

    /* Check intermediate directory components below sandbox root for virtual-target symlinks */
    int replaced_prefix = 0;
    size_t cur_len = strlen(cur);
    for (size_t pos = root_prefix + 1; pos < cur_len; pos++) {
      if (cur[pos] != '/') continue;
      char prefix[PATH_MAX];
      memcpy(prefix, cur, pos);
      prefix[pos] = '\0';
      ssize_t pn = syscall(SYS_readlinkat, AT_FDCWD, prefix, linkbuf, sizeof(linkbuf) - 1);
      if (pn > 0) {
        linkbuf[pn] = '\0';
        if (!vfs_is_l2s_name(linkbuf)) {
          char resolved_pref[PATH_MAX];
          if (vfs_join_link(prefix, linkbuf, resolved_pref, sizeof(resolved_pref)) == 0) {
            size_t rplen = strlen(resolved_pref);
            size_t tail_len = cur_len - pos;
            if (rplen + tail_len < sizeof(cur)) {
              char combined[PATH_MAX];
              memcpy(combined, resolved_pref, rplen);
              memcpy(combined + rplen, cur + pos, tail_len + 1);
              memcpy(cur, combined, rplen + tail_len + 1);
              root_prefix = vfs_sandbox_root_len(cur);
              replaced_prefix = 1;
              break;
            }
          }
        }
      }
    }
    if (!replaced_prefix || root_prefix == 0) break;
  }

  errno = saved_errno;
  int out_n = snprintf(buf, bufsz, "%s", cur);
  return (out_n > 0 && (size_t)out_n < bufsz) ? buf : mapped;
}

__attribute__((visibility("hidden"))) const char *bl_flatpak_map_path(const char *path, char *buf, size_t bufsz) {
  return bl_flatpak_map_path_mode(path, buf, bufsz, 1);
}

static int is_self_exe_path(const char *path) {
  if (!path) return 0;
  if (strcmp(path, "/proc/self/exe") == 0 || strcmp(path, "/proc/thread-self/exe") == 0) return 1;
  if (strncmp(path, "/proc/", 6) == 0) {
    char expected[48];
    snprintf(expected, sizeof(expected), "/proc/%d/exe", (int)getpid());
    return strcmp(path, expected) == 0;
  }
  return 0;
}

/* PLT wrappers for filesystem lookups not already in opens.c / pathcache.c / netif.c */

DIR *opendir(const char *path) {
  static DIR *(*real_opendir)(const char *);
  if (!real_opendir) real_opendir = (DIR *(*)(const char *))dlsym(RTLD_NEXT, "opendir");
  char buf[PATH_MAX];
  return real_opendir(bl_flatpak_map_path(path, buf, sizeof(buf)));
}

int fstatat(int dirfd, const char *path, struct stat *st, int flags) {
  static int (*real_fstatat)(int, const char *, struct stat *, int);
  if (!real_fstatat) real_fstatat = (int (*)(int, const char *, struct stat *, int))dlsym(RTLD_NEXT, "fstatat");
  char buf[PATH_MAX];
  int follow = (flags & AT_SYMLINK_NOFOLLOW) ? 0 : 1;
  return real_fstatat(dirfd, bl_flatpak_map_path_mode(path, buf, sizeof(buf), follow), st, flags);
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

int statx(int dirfd, const char *path, int flags, unsigned int mask, struct statx *stx) {
  static int (*real_statx)(int, const char *, int, unsigned int, struct statx *);
  if (!real_statx) real_statx = (int (*)(int, const char *, int, unsigned int, struct statx *))dlsym(RTLD_NEXT, "statx");
  char buf[PATH_MAX];
  int follow = (flags & AT_SYMLINK_NOFOLLOW) ? 0 : 1;
  const char *mapped = bl_flatpak_map_path_mode(path, buf, sizeof(buf), follow);
  if (real_statx) return real_statx(dirfd, mapped, flags, mask, stx);
  return (int)syscall(SYS_statx, dirfd, mapped, flags, mask, stx);
}

ssize_t readlinkat(int dirfd, const char *path, char *out, size_t bufsiz) {
  static ssize_t (*real_readlinkat)(int, const char *, char *, size_t);
  if (!real_readlinkat) real_readlinkat = (ssize_t (*)(int, const char *, char *, size_t))dlsym(RTLD_NEXT, "readlinkat");
  if (dirfd == AT_FDCWD && is_self_exe_path(path) && bl_flatpak_is_active() && vfs_exec_active) {
    if (bufsiz == 0) {
      errno = EINVAL;
      return -1;
    }
    size_t len = strlen(vfs_exec);
    if (len > bufsiz) len = bufsiz;
    memcpy(out, vfs_exec, len);
    return (ssize_t)len;
  }
  char buf[PATH_MAX];
  return real_readlinkat(dirfd, bl_flatpak_map_path_mode(path, buf, sizeof(buf), 0), out, bufsiz);
}

ssize_t readlink(const char *path, char *out, size_t bufsiz) {
  return readlinkat(AT_FDCWD, path, out, bufsiz);
}

char *realpath(const char *path, char *resolved) {
  static char *(*real_realpath)(const char *, char *);
  if (!real_realpath) real_realpath = (char *(*)(const char *, char *))dlsym(RTLD_NEXT, "realpath");
  if (is_self_exe_path(path) && bl_flatpak_is_active() && vfs_exec_active) {
    size_t n = strlen(vfs_exec) + 1;
    if (!resolved) {
      resolved = malloc(n);
      if (!resolved) return NULL;
    }
    memcpy(resolved, vfs_exec, n);
    return resolved;
  }
  char buf[PATH_MAX];
  return real_realpath(bl_flatpak_map_path(path, buf, sizeof(buf)), resolved);
}
