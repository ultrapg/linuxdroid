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
  "MOZ_ASSUME_USER_NS=0",
  "MOZ_CRASHREPORTER_DISABLE=1",
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
  setenv("MOZ_ASSUME_USER_NS", "0", 1);
  setenv("MOZ_CRASHREPORTER_DISABLE", "1", 1);
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

__attribute__((constructor)) static void webhelper_init(void) {
  char exe[512];
  ssize_t n = readlink("/proc/self/exe", exe, sizeof(exe) - 1);
  exe[n > 0 ? n : 0] = '\0';
  if (is_firefox_prog(program_invocation_short_name) ||
      is_firefox_prog(exe) ||
      getenv("MOZ_LAUNCHED_CHILD") != NULL ||
      getenv("MOZ_APP_LAUNCHER") != NULL) {
    apply_firefox_env();
  }
}

__attribute__((visibility("hidden"))) char *const *bl_webhelper_argv(const char *path, char *const argv[]) {
  if (is_firefox_prog(path) || (argv && argv[0] && is_firefox_prog(argv[0]))) {
    apply_firefox_env();
  }
  return argv;
}

__attribute__((visibility("hidden"))) char *const *bl_webhelper_envp(const char *path, char *const argv[], char *const envp[]) {
  if (!is_firefox_prog(path) && !(argv && argv[0] && is_firefox_prog(argv[0]))) {
    return envp;
  }
  apply_firefox_env();
  if (!envp || envp == environ) {
    return environ;
  }
  size_t env_count = 0;
  while (envp[env_count]) env_count++;
  size_t extra_count = 0;
  while (kFirefoxEnvVars[extra_count]) extra_count++;
  char **merged = calloc(env_count + extra_count + 1, sizeof(char *));
  if (!merged) return envp;
  for (size_t i = 0; i < env_count; i++) {
    merged[i] = envp[i];
  }
  for (size_t j = 0; j < extra_count; j++) {
    merged[env_count + j] = (char *)kFirefoxEnvVars[j];
  }
  merged[env_count + extra_count] = NULL;
  return merged;
}
