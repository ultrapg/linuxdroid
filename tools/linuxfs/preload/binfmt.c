#define _GNU_SOURCE
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <spawn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <unistd.h>

#ifndef BINFMT_HANDLER
#define BINFMT_HANDLER "/usr/local/bin/linuxdroid-open"
#endif

typedef int (*spawn_fn)(pid_t *, const char *, const posix_spawn_file_actions_t *, const posix_spawnattr_t *,
                        char *const[], char *const[]);

#if defined(__aarch64__) || defined(BINFMT_TEST)

static int desktop(void) {
  const char *value = getenv("BL_DESKTOP");
  return value && value[0] == '1' && value[1] == 0;
}

static int foreign(const char *path, int error) {
  if (!path || !strcmp(path, BINFMT_HANDLER)) return 0;
  int fd = open(path, O_RDONLY | O_CLOEXEC);
  if (fd < 0) return 0;
  struct stat st;
  unsigned char head[20];
  ssize_t n = fstat(fd, &st) == 0 && S_ISREG(st.st_mode) ? read(fd, head, sizeof head) : -1;
  int kind = 0;
  if (n >= 20 && !memcmp(head, ELFMAG, SELFMAG)) {
    unsigned machine = head[EI_DATA] == ELFDATA2MSB ? (unsigned)head[18] << 8 | head[19] : head[18] | (unsigned)head[19] << 8;
    kind = machine == EM_X86_64 || machine == EM_386 || error == EACCES ? 1 : 0;
  } else if (n >= 2 && head[0] == '#' && head[1] == '!' && error == EACCES) {
    kind = 3;
  }
  struct statvfs vfs;
  int allowed = kind && (((st.st_mode & 0111) && error != EACCES) || (fstatvfs(fd, &vfs) == 0 && (vfs.f_flag & ST_NOEXEC)));
  close(fd);
  return allowed ? kind : 0;
}

static int wanted(const char *path, int error) {
  return (error == ENOEXEC || error == ENOENT || error == EACCES) && desktop() && foreign(path, error);
}

static const char *fp_env_get(char *const envp[], const char *key) {
  extern char **environ;
  char *const *list = envp ? envp : environ;
  if (!list || !key) return NULL;
  size_t klen = strlen(key);
  const char *val = NULL;
  for (size_t i = 0; list[i]; i++) {
    if (strncmp(list[i], key, klen) == 0 && list[i][klen] == '=') {
      val = list[i] + klen + 1;
      break;
    }
  }
  return (val && val[0]) ? val : NULL;
}

static const char *fp_shim_redirect(const char *path) {
  if (!path) return NULL;
  if ((strcmp(path, "/usr/bin/bwrap") == 0 || strcmp(path, "/bin/bwrap") == 0) &&
      access("/usr/local/bin/bwrap", X_OK) == 0) {
    return "/usr/local/bin/bwrap";
  }
  if ((strcmp(path, "/usr/bin/flatpak-spawn") == 0 || strcmp(path, "/bin/flatpak-spawn") == 0) &&
      access("/usr/local/bin/flatpak-spawn", X_OK) == 0) {
    return "/usr/local/bin/flatpak-spawn";
  }
  if ((strcmp(path, "/usr/bin/chromium") == 0 || strcmp(path, "/bin/chromium") == 0) &&
      access("/usr/local/bin/chromium", X_OK) == 0) {
    return "/usr/local/bin/chromium";
  }
  if ((strcmp(path, "/usr/bin/chromium-browser") == 0 || strcmp(path, "/bin/chromium-browser") == 0) &&
      access("/usr/local/bin/chromium-browser", X_OK) == 0) {
    return "/usr/local/bin/chromium-browser";
  }
  return NULL;
}

static int fp_is_shim_name(const char *file) {
  if (!file) return 0;
  return strcmp(file, "bwrap") == 0 ||
         strcmp(file, "flatpak-spawn") == 0 ||
         strcmp(file, "chromium") == 0 ||
         strcmp(file, "chromium-browser") == 0 ||
         fp_shim_redirect(file) != NULL;
}

static int fp_is_l2s_target(const char *link_target) {
  if (!link_target) return 0;
  const char *base = strrchr(link_target, '/');
  base = base ? base + 1 : link_target;
  return strncmp(base, ".l2s.", 5) == 0 || strncmp(base, ".proot.l2s.", 11) == 0;
}

static void fp_map_virtual(const char *in, const char *fp_app, const char *fp_usr, char *out, size_t out_sz) {
  const char *shim = fp_shim_redirect(in);
  if (shim) {
    snprintf(out, out_sz, "%s", shim);
    return;
  }
  if (fp_app && (strcmp(in, "/app") == 0 || strncmp(in, "/app/", 5) == 0)) {
    int n = snprintf(out, out_sz, "%s%s", fp_app, in + 4);
    if (n > 0 && (size_t)n < out_sz && access(out, F_OK) == 0) return;
  }
  if (fp_usr && strncmp(in, "/usr/local/", 11) != 0 && (strcmp(in, "/usr") == 0 || strncmp(in, "/usr/", 5) == 0)) {
    int n = snprintf(out, out_sz, "%s%s", fp_usr, in + 4);
    if (n > 0 && (size_t)n < out_sz && access(out, F_OK) == 0) return;
  }
  if (fp_usr && strncmp(in, "/bin/", 5) == 0) {
    int n = snprintf(out, out_sz, "%s/bin/%s", fp_usr, in + 5);
    if (n > 0 && (size_t)n < out_sz && access(out, F_OK) == 0) return;
  }
  if (fp_usr && strncmp(in, "/sbin/", 6) == 0) {
    int n = snprintf(out, out_sz, "%s/sbin/%s", fp_usr, in + 6);
    if (n > 0 && (size_t)n < out_sz && access(out, F_OK) == 0) return;
  }
  snprintf(out, out_sz, "%s", in);
}

static int lookup(const char *file, char *const envp[], char *out, size_t size) {
  if (!file || !*file) return 0;
  if (strchr(file, '/')) {
    const char *shim = fp_shim_redirect(file);
    return snprintf(out, size, "%s", shim ? shim : file) < (int)size;
  }
  if (strcmp(file, "bwrap") == 0 && access("/usr/local/bin/bwrap", X_OK) == 0) {
    return snprintf(out, size, "/usr/local/bin/bwrap") < (int)size;
  }
  if (strcmp(file, "flatpak-spawn") == 0 && access("/usr/local/bin/flatpak-spawn", X_OK) == 0) {
    return snprintf(out, size, "/usr/local/bin/flatpak-spawn") < (int)size;
  }
  const char *fp_ld = fp_env_get(envp, "BL_FLATPAK_LD");
  const char *fp_app = fp_ld ? fp_env_get(envp, "BL_FLATPAK_APP") : NULL;
  const char *fp_usr = fp_ld ? fp_env_get(envp, "BL_FLATPAK_USR") : NULL;
  const char *paths = fp_env_get(envp, "PATH");
  if (!paths) paths = "/usr/local/bin:/bin:/usr/bin";
  for (const char *p = paths;;) {
    const char *end = strchrnul(p, ':');
    char cand[PATH_MAX];
    int n = end > p ? snprintf(cand, sizeof(cand), "%.*s/%s", (int)(end - p), p, file)
                    : snprintf(cand, sizeof(cand), "%s", file);
    if (n > 0 && (size_t)n < sizeof(cand)) {
      if (fp_ld) {
        fp_map_virtual(cand, fp_app, fp_usr, out, size);
      } else {
        snprintf(out, size, "%s", cand);
      }
      struct stat st;
      if (stat(out, &st) == 0 && S_ISREG(st.st_mode)) return 1;
    }
    if (!*end) break;
    p = end + 1;
  }
  if (fp_app) {
    int n = snprintf(out, size, "%s/bin/%s", fp_app, file);
    struct stat st;
    if (n > 0 && (size_t)n < size && stat(out, &st) == 0 && S_ISREG(st.st_mode)) return 1;
  }
  if (fp_usr) {
    int n = snprintf(out, size, "%s/bin/%s", fp_usr, file);
    struct stat st;
    if (n > 0 && (size_t)n < size && stat(out, &st) == 0 && S_ISREG(st.st_mode)) return 1;
  }
  return 0;
}

static int count(char *const argv[]) {
  int n = 0;
  while (argv && argv[n]) n++;
  return n;
}

static void handler_args(char **args, const char *path, char *const argv[], int n) {
  args[0] = "linuxdroid-open";
  args[1] = "--exec";
  args[2] = (char *)path;
  int i = 3;
  for (int k = 1; k < n; k++) args[i++] = argv[k];
  args[i] = NULL;
}

static void hand_off(const char *path, char *const argv[], char *const envp[]) {
  static int (*real)(const char *, char *const[], char *const[]);
  if (!real) real = (int (*)(const char *, char *const[], char *const[]))dlsym(RTLD_NEXT, "execve");
  int n = count(argv);
  char *args[n + 4];
  handler_args(args, path, argv, n);
  real(BINFMT_HANDLER, args, envp);
}

static int spawn_handler(pid_t *pid, const char *path, const posix_spawn_file_actions_t *fa, const posix_spawnattr_t *attr,
                         char *const argv[], char *const envp[], spawn_fn real) {
  int n = count(argv);
  char *args[n + 4];
  handler_args(args, path, argv, n);
  return real(pid, BINFMT_HANDLER, fa, attr, args, envp);
}

static void fp_resolve_target(const char *in, const char *fp_app, const char *fp_usr, char *out, size_t out_sz) {
  fp_map_virtual(in, fp_app, fp_usr, out, out_sz);
  for (int hop = 0; hop < 8; hop++) {
    char link_buf[PATH_MAX];
    ssize_t len = readlink(out, link_buf, sizeof(link_buf) - 1);
    if (len <= 0) break;
    link_buf[len] = '\0';
    if (fp_is_l2s_target(link_buf)) break;
    char next_buf[PATH_MAX];
    if (link_buf[0] == '/') {
      fp_map_virtual(link_buf, fp_app, fp_usr, next_buf, sizeof(next_buf));
    } else {
      const char *slash = strrchr(out, '/');
      if (slash) {
        size_t dir_len = (size_t)(slash - out);
        size_t link_len = strlen(link_buf);
        if (dir_len + 1 + link_len >= sizeof(next_buf)) break;
        memcpy(next_buf, out, dir_len);
        next_buf[dir_len] = '/';
        memcpy(next_buf + dir_len + 1, link_buf, link_len + 1);
      } else {
        snprintf(next_buf, sizeof(next_buf), "%s", link_buf);
      }
    }
    snprintf(out, out_sz, "%s", next_buf);
  }
}

static int fp_is_owned(const char *p, const char *fp_app, const char *fp_usr) {
  if (!p || strncmp(p, "/usr/local/", 11) == 0) return 0;
  if (fp_app && strncmp(p, fp_app, strlen(fp_app)) == 0 && p[strlen(fp_app)] == '/') return 1;
  if (fp_usr && strncmp(p, fp_usr, strlen(fp_usr)) == 0 && p[strlen(fp_usr)] == '/') return 1;
  if (strncmp(p, "/var/lib/flatpak/", 17) == 0 || strstr(p, "/.local/share/flatpak/") != NULL) return 1;
  return 0;
}

static int fp_is_aarch64_elf(const char *p) {
  int fd = open(p, O_RDONLY | O_CLOEXEC);
  if (fd < 0) return 0;
  unsigned char head[20];
  ssize_t n = read(fd, head, sizeof(head));
  close(fd);
  if (n < 20 || memcmp(head, ELFMAG, SELFMAG) != 0 || head[EI_CLASS] != ELFCLASS64) return 0;
  unsigned machine = head[EI_DATA] == ELFDATA2MSB ? ((unsigned)head[18] << 8 | head[19]) : (head[18] | ((unsigned)head[19] << 8));
  return machine == EM_AARCH64;
}

static char *pool_copy(char **cursor, const char *src) {
  size_t len = strlen(src) + 1;
  char *dst = *cursor;
  memcpy(dst, src, len);
  *cursor += len;
  return dst;
}

static int fp_prepare_exec(const char *path, char *const argv[], char *const envp[],
                           const char **out_ld, char ***out_args, char ***out_envp) {
  const char *fp_ld = fp_env_get(envp, "BL_FLATPAK_LD");
  if (!fp_ld || !path || !path[0]) return 0;
  if (strcmp(path, fp_ld) == 0 || strncmp(path, "/usr/local/", 11) == 0 || fp_shim_redirect(path) != NULL) return 0;

  const char *fp_usr = fp_env_get(envp, "BL_FLATPAK_USR");
  const char *fp_app = fp_env_get(envp, "BL_FLATPAK_APP");
  const char *fp_lib = fp_env_get(envp, "BL_FLATPAK_LIBPATH");

  char resolved[PATH_MAX];
  fp_resolve_target(path, fp_app, fp_usr, resolved, sizeof(resolved));
  if (strcmp(resolved, fp_ld) == 0 || !fp_is_owned(resolved, fp_app, fp_usr)) return 0;

  char interp_resolved[PATH_MAX];
  char shebang_buf[256];
  const char *shebang_extra[8];
  int shebang_extra_count = 0;
  const char *shebang_env[8];
  int shebang_env_count = 0;
  int has_shebang = 0;

  if (!fp_is_aarch64_elf(resolved)) {
    int fd = open(resolved, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    ssize_t n = read(fd, shebang_buf, sizeof(shebang_buf) - 1);
    close(fd);
    if (n < 3 || shebang_buf[0] != '#' || shebang_buf[1] != '!') return 0;
    shebang_buf[n] = '\0';
    char *nl = strpbrk(shebang_buf + 2, "\r\n");
    if (nl) *nl = '\0';
    char *s = shebang_buf + 2;
    while (*s == ' ' || *s == '\t') s++;
    if (!*s) return 0;
    char *sp = s;
    while (*sp && *sp != ' ' && *sp != '\t') sp++;
    if (*sp) {
      *sp++ = '\0';
      while (*sp == ' ' || *sp == '\t') sp++;
    }
    const char *s_base = strrchr(s, '/');
    s_base = s_base ? s_base + 1 : s;
    if (strcmp(s_base, "env") == 0 && *sp) {
      char *tok_cur = sp;
      const char *tool_name = NULL;
      while (*tok_cur) {
        while (*tok_cur == ' ' || *tok_cur == '\t') tok_cur++;
        if (!*tok_cur) break;
        char *tok_start = tok_cur;
        while (*tok_cur && *tok_cur != ' ' && *tok_cur != '\t') tok_cur++;
        if (*tok_cur) *tok_cur++ = '\0';
        if (!tool_name) {
          if (strcmp(tok_start, "-S") == 0) continue;
          if (strchr(tok_start, '=')) {
            if (shebang_env_count < 8) shebang_env[shebang_env_count++] = tok_start;
            continue;
          }
          tool_name = tok_start;
        } else if (shebang_extra_count < 8) {
          shebang_extra[shebang_extra_count++] = tok_start;
        }
      }
      if (!tool_name) return 0;
      char looked[PATH_MAX];
      if (!lookup(tool_name, envp, looked, sizeof(looked))) return 0;
      fp_resolve_target(looked, fp_app, fp_usr, interp_resolved, sizeof(interp_resolved));
    } else {
      if (*sp) shebang_extra[shebang_extra_count++] = sp;
      fp_resolve_target(s, fp_app, fp_usr, interp_resolved, sizeof(interp_resolved));
    }
    if (!fp_is_owned(interp_resolved, fp_app, fp_usr) || !fp_is_aarch64_elf(interp_resolved)) return 0;
    has_shebang = 1;
  }

  const char *elf_target = has_shebang ? interp_resolved : resolved;
  int orig_argc = count(argv);
  int max_args = orig_argc + shebang_extra_count + 10;
  size_t str_bytes = strlen(fp_ld) + 1 + strlen(elf_target) * 2 + 2 + strlen(resolved) + 1;
  for (int i = 0; i < shebang_extra_count; i++) str_bytes += strlen(shebang_extra[i]) + 1;

  size_t ptr_bytes = (size_t)max_args * sizeof(char *);
  char **ld_args = malloc(ptr_bytes + str_bytes);
  if (!ld_args) return 0;
  char *pool = (char *)ld_args + ptr_bytes;

  char *ld_copy = pool_copy(&pool, fp_ld);
  char *elf_copy = pool_copy(&pool, elf_target);
  int k = 0;
  ld_args[k++] = ld_copy;
  ld_args[k++] = "--inhibit-cache";
  ld_args[k++] = "--argv0";
  if (has_shebang) {
    ld_args[k++] = elf_copy;
  } else {
    ld_args[k++] = (argv && argv[0]) ? argv[0] : (char *)path;
  }
  if (fp_lib) {
    ld_args[k++] = "--library-path";
    ld_args[k++] = (char *)fp_lib;
  }
  ld_args[k++] = elf_copy;
  if (has_shebang) {
    for (int i = 0; i < shebang_extra_count; i++) {
      ld_args[k++] = pool_copy(&pool, shebang_extra[i]);
    }
    ld_args[k++] = pool_copy(&pool, resolved);
  }
  for (int i = 1; i < orig_argc; i++) {
    ld_args[k++] = argv[i];
  }
  ld_args[k] = NULL;

  char **new_envp = NULL;
  const char *base = strrchr(elf_target, '/');
  base = base ? base + 1 : elf_target;
  int need_pyhome = fp_usr && strncmp(base, "python3", 7) == 0 && !fp_env_get(envp, "PYTHONHOME");
  {
    extern char **environ;
    char *const *src_env = envp ? envp : environ;
    size_t ec = 0;
    while (src_env && src_env[ec]) ec++;
    size_t extra_ec = (size_t)shebang_env_count + (need_pyhome ? 1u : 0u) + 1u;
    size_t env_ptr_bytes = (ec + extra_ec + 1) * sizeof(char *);
    size_t exec_kv_cap = strlen(elf_target) + 20;
    size_t env_str_bytes = (need_pyhome ? (strlen(fp_usr) + 16) : 0) + exec_kv_cap;
    for (int i = 0; i < shebang_env_count; i++) env_str_bytes += strlen(shebang_env[i]) + 1;
    new_envp = malloc(env_ptr_bytes + env_str_bytes);
    if (new_envp) {
      char *epool = (char *)new_envp + env_ptr_bytes;
      size_t idx = 0;
      int elen = snprintf(epool, exec_kv_cap, "BL_FLATPAK_EXEC=%s", elf_target);
      new_envp[idx++] = epool;
      epool += elen + 1;
      if (need_pyhome) {
        int plen = snprintf(epool, strlen(fp_usr) + 16, "PYTHONHOME=%s", fp_usr);
        new_envp[idx++] = epool;
        epool += plen + 1;
      }
      for (int i = 0; i < shebang_env_count; i++) {
        new_envp[idx++] = pool_copy(&epool, shebang_env[i]);
      }
      for (size_t i = 0; i < ec; i++) {
        if (strncmp(src_env[i], "BL_FLATPAK_EXEC=", 16) == 0) continue;
        new_envp[idx++] = src_env[i];
      }
      new_envp[idx] = NULL;
    }
  }

  *out_ld = fp_ld;
  *out_args = ld_args;
  *out_envp = new_envp;
  return 1;
}

__attribute__((visibility("hidden"))) void bl_binfmt_before(const char *path, char *const argv[], char *const envp[]) {
  extern char **environ;
  static int (*real)(const char *, char *const[], char *const[]);
  if (!real) real = (int (*)(const char *, char *const[], char *const[]))dlsym(RTLD_NEXT, "execve");
  const char *shim = fp_shim_redirect(path);
  if (shim) {
    real(shim, argv, envp ? envp : environ);
    return;
  }
  const char *fp_ld = NULL;
  char **fp_args = NULL;
  char **fp_envp = NULL;
  if (fp_prepare_exec(path, argv, envp, &fp_ld, &fp_args, &fp_envp)) {
    real(fp_ld, fp_args, fp_envp ? fp_envp : (envp ? envp : environ));
    int saved = errno;
    free(fp_args);
    free(fp_envp);
    errno = saved;
  }
  if (desktop() && foreign(path, ENOEXEC)) hand_off(path, argv, envp);
}

__attribute__((visibility("hidden"))) int bl_binfmt_retry(const char *path, char *const argv[], char *const envp[], int error) {
  if (wanted(path, error)) hand_off(path, argv, envp);
  errno = error;
  return -1;
}

__attribute__((visibility("hidden"))) void bl_binfmt_before_search(const char *file, char *const argv[], char *const envp[]) {
  char path[PATH_MAX];
  if ((fp_env_get(envp, "BL_FLATPAK_LD") || desktop() || fp_is_shim_name(file)) && lookup(file, envp, path, sizeof path)) {
    bl_binfmt_before(path, argv, envp);
  }
}

__attribute__((visibility("hidden"))) int bl_binfmt_after_search(const char *file, char *const argv[], char *const envp[], int error) {
  char path[PATH_MAX];
  if (desktop() && lookup(file, envp, path, sizeof path)) return bl_binfmt_retry(path, argv, envp, error);
  errno = error;
  return -1;
}

__attribute__((visibility("hidden"))) int bl_binfmt_runnable(const char *path, int mode) {
  if (mode != X_OK || !desktop()) return 0;
  int runs = foreign(path, EACCES) != 0;
  errno = EACCES;
  return runs;
}

__attribute__((visibility("hidden"))) int bl_binfmt_spawn(pid_t *pid, const char *path, const posix_spawn_file_actions_t *fa,
                                                          const posix_spawnattr_t *attr, char *const argv[], char *const envp[],
                                                          spawn_fn real) {
  extern char **environ;
  const char *shim = fp_shim_redirect(path);
  if (shim) {
    return real(pid, shim, fa, attr, argv, envp ? envp : environ);
  }
  const char *fp_ld = NULL;
  char **fp_args = NULL;
  char **fp_envp = NULL;
  if (fp_prepare_exec(path, argv, envp, &fp_ld, &fp_args, &fp_envp)) {
    int rc = real(pid, fp_ld, fa, attr, fp_args, fp_envp ? fp_envp : (envp ? envp : environ));
    int saved = errno;
    free(fp_args);
    free(fp_envp);
    errno = saved;
    return rc;
  }
  if (desktop() && foreign(path, ENOEXEC)) return spawn_handler(pid, path, fa, attr, argv, envp, real);
  int error = real(pid, path, fa, attr, argv, envp);
  return error && wanted(path, error) ? spawn_handler(pid, path, fa, attr, argv, envp, real) : error;
}

__attribute__((visibility("hidden"))) char *const *bl_webhelper_argv(const char *path, char *const argv[], char *const envp[]);
__attribute__((visibility("hidden"))) char *const *bl_webhelper_envp(const char *path, char *const argv[], char *const envp[]);
__attribute__((visibility("hidden"))) void bl_webhelper_free(char *const *argv, char *const *argv_in,
                                                             char *const *envp, char *const *envp_in);

int execvpe(const char *file, char *const argv_in[], char *const envp_in[]) {
  char *const *argv = bl_webhelper_argv(file, argv_in, envp_in);
  char *const *envp = bl_webhelper_envp(file, argv, envp_in);
  static int (*real)(const char *, char *const[], char *const[]);
  if (!real) real = (int (*)(const char *, char *const[], char *const[]))dlsym(RTLD_NEXT, "execvpe");
  bl_binfmt_before_search(file, argv, envp);
  real(file, argv, envp);
  int rc = bl_binfmt_after_search(file, argv, envp, errno);
  bl_webhelper_free(argv, argv_in, envp, envp_in);
  return rc;
}

int posix_spawnp(pid_t *pid, const char *file, const posix_spawn_file_actions_t *fa, const posix_spawnattr_t *attr,
                 char *const argv_in[], char *const envp_in[]) {
  char *const *argv = bl_webhelper_argv(file, argv_in, envp_in);
  char *const *envp = bl_webhelper_envp(file, argv, envp_in);
  static spawn_fn real, spawn;
  if (!real) real = (spawn_fn)dlsym(RTLD_NEXT, "posix_spawnp");
  if (!spawn) spawn = (spawn_fn)dlsym(RTLD_NEXT, "posix_spawn");
  char path[PATH_MAX];
  int rc;
  if (!(fp_env_get(envp, "BL_FLATPAK_LD") || desktop() || fp_is_shim_name(file)) || !lookup(file, envp, path, sizeof path)) {
    rc = real(pid, file, fa, attr, argv, envp);
  } else {
    rc = bl_binfmt_spawn(pid, path, fa, attr, argv, envp, spawn);
  }
  bl_webhelper_free(argv, argv_in, envp, envp_in);
  return rc;
}

#else

__attribute__((visibility("hidden"))) int bl_binfmt_runnable(const char *path, int mode) {
  (void)path, (void)mode;
  return 0;
}

__attribute__((visibility("hidden"))) void bl_binfmt_before(const char *path, char *const argv[], char *const envp[]) {
  (void)path, (void)argv, (void)envp;
}

__attribute__((visibility("hidden"))) int bl_binfmt_retry(const char *path, char *const argv[], char *const envp[], int error) {
  (void)path, (void)argv, (void)envp;
  errno = error;
  return -1;
}

__attribute__((visibility("hidden"))) void bl_binfmt_before_search(const char *file, char *const argv[], char *const envp[]) {
  (void)file, (void)argv, (void)envp;
}

__attribute__((visibility("hidden"))) int bl_binfmt_after_search(const char *file, char *const argv[], char *const envp[], int error) {
  (void)file, (void)argv, (void)envp;
  errno = error;
  return -1;
}

__attribute__((visibility("hidden"))) int bl_binfmt_spawn(pid_t *pid, const char *path, const posix_spawn_file_actions_t *fa,
                                                          const posix_spawnattr_t *attr, char *const argv[], char *const envp[],
                                                          spawn_fn real) {
  return real(pid, path, fa, attr, argv, envp);
}

#endif
