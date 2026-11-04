#define _GNU_SOURCE
#include <errno.h>
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

static int env_has_truthy(char *const envp[], const char *key) {
  char *const *list = envp ? envp : environ;
  if (!list || !key) return 0;
  size_t klen = strlen(key);
  const char *found = NULL;
  for (size_t i = 0; list[i]; i++) {
    if (strncmp(list[i], key, klen) == 0 && list[i][klen] == '=') {
      found = list[i] + klen + 1;
    }
  }
  return found != NULL && found[0] != '\0' && strcmp(found, "0") != 0;
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
    apply_firefox_env();
    return argv;
  }
  int is_elec = is_electron_prog(path) || (argv && argv[0] && is_electron_prog(argv[0]));
  int is_chrom = !is_elec && (is_chromium_prog(path) || (argv && argv[0] && is_chromium_prog(argv[0])));
  if (is_elec || is_chrom) {
    apply_electron_env();
    if (!argv || !argv[0]) return argv;
    if (is_elec && env_has_truthy(envp, "ELECTRON_RUN_AS_NODE")) {
      return argv;
    }
    size_t argc = 0;
    int has_no_sandbox = 0;
    int has_no_zygote = 0;
    int has_disable_gpu_sandbox = 0;
    int has_disable_dev_shm = 0;
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
    }
    const char *inject[4];
    size_t extra_flags = 0;
    if (!has_no_sandbox) inject[extra_flags++] = "--no-sandbox";
    if (!has_no_zygote) inject[extra_flags++] = "--no-zygote";
    if (!has_disable_gpu_sandbox) inject[extra_flags++] = "--disable-gpu-sandbox";
    if (!has_disable_dev_shm) inject[extra_flags++] = "--disable-dev-shm-usage";
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
    apply_firefox_env();
    extra = kFirefoxEnvVars;
  } else if (is_electron_prog(path) || (argv && argv[0] && is_electron_prog(argv[0])) ||
             is_chromium_prog(path) || (argv && argv[0] && is_chromium_prog(argv[0]))) {
    apply_electron_env();
    extra = kElectronEnvVars;
  } else {
    return envp;
  }
  if (!envp || envp == environ) {
    return environ;
  }
  size_t env_count = 0;
  while (envp[env_count]) env_count++;
  size_t extra_count = 0;
  while (extra[extra_count]) extra_count++;
  char **merged = calloc(env_count + extra_count + 1, sizeof(char *));
  if (!merged) return envp;
  for (size_t i = 0; i < env_count; i++) {
    merged[i] = envp[i];
  }
  for (size_t j = 0; j < extra_count; j++) {
    merged[env_count + j] = (char *)extra[j];
  }
  merged[env_count + extra_count] = NULL;
  return merged;
}
