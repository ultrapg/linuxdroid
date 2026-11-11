#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

extern char *program_invocation_short_name;
extern char **environ;

static const char *const kFirefoxEnvVars[] = {
  "MOZ_FAKE_NO_SANDBOX=1",
  "MOZ_DISABLE_CONTENT_SANDBOX=1",
  "MOZ_DISABLE_GMP_SANDBOX=1",
  "MOZ_DISABLE_RDD_SANDBOX=1",
  "MOZ_DISABLE_SOCKET_PROCESS_SANDBOX=1",
  "MOZ_DISABLE_UTILITY_SANDBOX=1",
  "MOZ_DISABLE_GPU_SANDBOX=1",
  "MOZ_DISABLE_NPAPI_SANDBOX=1",
  "MOZ_FORCE_DISABLE_E10S=1",
  "MOZ_SHM_NO_SEALS=1",
  "MOZ_ASSUME_USER_NS=0",
  "MOZ_CRASHREPORTER_DISABLE=1",
  "GTK_USE_PORTAL=0",
  "GDK_DEBUG=no-portals",
  NULL
};

static const char *const kElectronEnvVars[] = {
  "ELECTRON_DISABLE_SANDBOX=1",
  "GTK_USE_PORTAL=0",
  "GDK_DEBUG=no-portals",
  NULL
};

static void apply_firefox_env(void) {
  setenv("MOZ_FAKE_NO_SANDBOX", "1", 1);
  setenv("MOZ_DISABLE_CONTENT_SANDBOX", "1", 1);
  setenv("MOZ_DISABLE_GMP_SANDBOX", "1", 1);
  setenv("MOZ_DISABLE_RDD_SANDBOX", "1", 1);
  setenv("MOZ_DISABLE_SOCKET_PROCESS_SANDBOX", "1", 1);
  setenv("MOZ_DISABLE_UTILITY_SANDBOX", "1", 1);
  setenv("MOZ_DISABLE_GPU_SANDBOX", "1", 1);
  setenv("MOZ_DISABLE_NPAPI_SANDBOX", "1", 1);
  setenv("MOZ_FORCE_DISABLE_E10S", "1", 1);
  setenv("MOZ_SHM_NO_SEALS", "1", 1);
  setenv("MOZ_ASSUME_USER_NS", "0", 1);
  setenv("MOZ_CRASHREPORTER_DISABLE", "1", 1);
  setenv("GTK_USE_PORTAL", "0", 1);
  setenv("GDK_DEBUG", "no-portals", 1);
}

static void apply_electron_env(void) {
  setenv("ELECTRON_DISABLE_SANDBOX", "1", 1);
  setenv("GTK_USE_PORTAL", "0", 1);
  setenv("GDK_DEBUG", "no-portals", 1);
}

static int is_firefox_prog(const char *name) {
  if (!name || !name[0]) return 0;
  const char *base = strrchr(name, '/');
  if (base) name = base + 1;
  return strncmp(name, "firefox", 7) == 0 ||
         strcmp(name, "crashhelper") == 0 ||
         strcmp(name, "glxtest") == 0 ||
         strcmp(name, "vaapitest") == 0 ||
         strcmp(name, "gfxtest") == 0 ||
         strcmp(name, "pingsender") == 0;
}

static int is_electron_prog(const char *name) {
  if (!name || !name[0]) return 0;
  const char *base = strrchr(name, '/');
  if (base) name = base + 1;
  return strcmp(name, "code") == 0 ||
         strcmp(name, "codium") == 0 ||
         strcmp(name, "electron") == 0;
}

static int is_chromium_prog(const char *name) {
  if (!name || !name[0]) return 0;
  const char *base = strrchr(name, '/');
  if (base) name = base + 1;
  return strcmp(name, "chromium") == 0 ||
         strcmp(name, "chromium-browser") == 0 ||
         strcmp(name, "brave") == 0 ||
         strcmp(name, "brave-browser") == 0 ||
         strcmp(name, "chrome") == 0 ||
         strcmp(name, "google-chrome") == 0 ||
         strcmp(name, "google-chrome-stable") == 0;
}

static const char *env_lookup(char *const envp[], const char *key) {
  char *const *list = envp ? envp : environ;
  if (!list || !key) return NULL;
  size_t klen = strlen(key);
  const char *found = NULL;
  for (size_t i = 0; list[i]; i++) {
    if (strncmp(list[i], key, klen) == 0 && list[i][klen] == '=') {
      found = list[i] + klen + 1;
      break;
    }
  }
  return found;
}

static int env_has_truthy(char *const envp[], const char *key) {
  const char *found = env_lookup(envp, key);
  return found != NULL && found[0] != '\0' && strcmp(found, "0") != 0;
}

static int check_elf_magic(const char *file_path) {
  int fd = open(file_path, O_RDONLY | O_CLOEXEC);
  if (fd < 0) return -1;
  unsigned char head[4];
  ssize_t n = read(fd, head, sizeof(head));
  close(fd);
  return (n == 4 && memcmp(head, "\x7f" "ELF", 4) == 0) ? 1 : 0;
}

static int is_elf_target(const char *path, char *const envp[]) {
  if (!path || !path[0]) return 0;
  if (strchr(path, '/')) {
    return check_elf_magic(path) == 1;
  }
  const char *paths = env_lookup(envp, "PATH");
  if (!paths || !paths[0]) paths = "/usr/local/bin:/usr/bin:/bin";
  char full[PATH_MAX];
  for (const char *p = paths;;) {
    const char *end = strchrnul(p, ':');
    int n = end > p ? snprintf(full, sizeof(full), "%.*s/%s", (int)(end - p), p, path)
                    : snprintf(full, sizeof(full), "%s", path);
    if (n > 0 && (size_t)n < sizeof(full) && access(full, X_OK) == 0) {
      return check_elf_magic(full) == 1;
    }
    if (!*end) return 0;
    p = end + 1;
  }
}

static int extra_defines_key(const char *const *extra, const char *entry) {
  const char *eq = strchr(entry, '=');
  if (!eq) return 0;
  size_t klen = (size_t)(eq - entry);
  for (size_t j = 0; extra[j]; j++) {
    if (strncmp(extra[j], entry, klen) == 0 && extra[j][klen] == '=') {
      return 1;
    }
  }
  return 0;
}

__attribute__((constructor)) static void webhelper_init(void) {
  char exe[512];
  ssize_t n = readlink("/proc/self/exe", exe, sizeof(exe) - 1);
  exe[n > 0 ? n : 0] = '\0';
  if (is_firefox_prog(program_invocation_short_name) ||
      is_firefox_prog(exe) ||
      getenv("MOZ_LAUNCHED_CHILD") != NULL ||
      getenv("MOZ_APP_LAUNCHER") != NULL) {
    apply_firefox_env();
  } else if (is_electron_prog(program_invocation_short_name) ||
             is_electron_prog(exe) ||
             is_chromium_prog(program_invocation_short_name) ||
             is_chromium_prog(exe)) {
    apply_electron_env();
  }
}

__attribute__((visibility("hidden"))) char *const *bl_webhelper_argv(const char *path, char *const argv[], char *const envp[]) {
  if (is_firefox_prog(path) || (argv && argv[0] && is_firefox_prog(argv[0]))) {
    return argv;
  }
  int is_elec = is_electron_prog(path) || (argv && argv[0] && is_electron_prog(argv[0]));
  int is_chrom = !is_elec && (is_chromium_prog(path) || (argv && argv[0] && is_chromium_prog(argv[0])));
  if (is_elec || is_chrom) {
    if (!argv || !argv[0]) return argv;
    if (is_elec) {
      if (env_has_truthy(envp, "ELECTRON_RUN_AS_NODE")) return argv;
      if (!is_elf_target(path, envp)) return argv;
    }
    size_t argc = 0;
    int has_no_sandbox = 0;
    int has_no_zygote = 0;
    int has_disable_gpu_sandbox = 0;
    int has_disable_dev_shm = 0;
    int has_disable_gpu_watchdog = 0;
    int has_disable_vulkan = 0;
    for (; argv[argc]; argc++) {
      if (strncmp(argv[argc], "--type=", 7) == 0 ||
          strcmp(argv[argc], "--version") == 0 ||
          strcmp(argv[argc], "-v") == 0 ||
          strcmp(argv[argc], "--help") == 0 ||
          strcmp(argv[argc], "-h") == 0) {
        return argv;
      }
      if (strcmp(argv[argc], "--no-sandbox") == 0) has_no_sandbox = 1;
      else if (strcmp(argv[argc], "--no-zygote") == 0) has_no_zygote = 1;
      else if (strcmp(argv[argc], "--disable-gpu-sandbox") == 0) has_disable_gpu_sandbox = 1;
      else if (strcmp(argv[argc], "--disable-dev-shm-usage") == 0) has_disable_dev_shm = 1;
      else if (strcmp(argv[argc], "--disable-gpu-watchdog") == 0) has_disable_gpu_watchdog = 1;
      else if (strcmp(argv[argc], "--disable-vulkan") == 0) has_disable_vulkan = 1;
    }
    const char *inject[7];
    size_t extra_flags = 0;
    if (!has_no_sandbox) inject[extra_flags++] = "--no-sandbox";
    if (!has_no_zygote) inject[extra_flags++] = "--no-zygote";
    if (!has_disable_gpu_sandbox) inject[extra_flags++] = "--disable-gpu-sandbox";
    if (!has_disable_dev_shm) inject[extra_flags++] = "--disable-dev-shm-usage";
    if (is_chrom) {
      if (!has_disable_gpu_watchdog) inject[extra_flags++] = "--disable-gpu-watchdog";
      if (!has_disable_vulkan) inject[extra_flags++] = "--disable-vulkan";
    }
    if (extra_flags == 0) return argv;
    char **out = calloc(argc + extra_flags + 1, sizeof(char *));
    if (!out) return argv;
    out[0] = argv[0];
    for (size_t j = 0; j < extra_flags; j++) {
      out[1 + j] = (char *)inject[j];
    }
    for (size_t i = 1; i < argc; i++) {
      out[i + extra_flags] = argv[i];
    }
    out[argc + extra_flags] = NULL;
    return out;
  }
  return argv;
}

__attribute__((visibility("hidden"))) char *const *bl_webhelper_envp(const char *path, char *const argv[], char *const envp[]) {
  const char *const *extra = NULL;
  if (is_firefox_prog(path) || (argv && argv[0] && is_firefox_prog(argv[0]))) {
    extra = kFirefoxEnvVars;
  } else if (is_electron_prog(path) || (argv && argv[0] && is_electron_prog(argv[0])) ||
             is_chromium_prog(path) || (argv && argv[0] && is_chromium_prog(argv[0]))) {
    extra = kElectronEnvVars;
  } else {
    return envp ? envp : environ;
  }
  char *const *src = envp ? envp : environ;
  size_t env_count = 0;
  while (src && src[env_count]) env_count++;
  size_t extra_count = 0;
  while (extra[extra_count]) extra_count++;
  char **merged = calloc(env_count + extra_count + 1, sizeof(char *));
  if (!merged) return src;
  size_t out_idx = 0;
  for (size_t j = 0; j < extra_count; j++) {
    merged[out_idx++] = (char *)extra[j];
  }
  for (size_t i = 0; i < env_count; i++) {
    if (!extra_defines_key(extra, src[i])) {
      merged[out_idx++] = src[i];
    }
  }
  merged[out_idx] = NULL;
  return merged;
}

__attribute__((visibility("hidden"))) void bl_webhelper_free(char *const *argv, char *const *argv_in,
                                                             char *const *envp, char *const *envp_in) {
  int saved = errno;
  if (argv && argv != argv_in) {
    free((void *)argv);
  }
  if (envp && envp != envp_in && envp != environ) {
    free((void *)envp);
  }
  errno = saved;
}
