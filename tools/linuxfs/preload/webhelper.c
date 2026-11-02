#define _GNU_SOURCE
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static char *const *chromium_argv(const char *path, char *const argv[]) {
  const char *slash = path ? strrchr(path, '/') : NULL;
  if (!slash || !argv || !argv[0]) return argv;
  int argc = 0;
  for (; argv[argc]; argc++)
    if (argc && (!strncmp(argv[argc], "--type=", 7) || !strcmp(argv[argc], "--no-sandbox"))) return argv;
  size_t dir = (size_t)(slash - path) + 1;
  char *sandbox = malloc(dir + sizeof "chrome-sandbox");
  if (!sandbox) return argv;
  memcpy(sandbox, path, dir);
  strcpy(sandbox + dir, "chrome-sandbox");
  int chromium = access(sandbox, F_OK) == 0;
  free(sandbox);
  if (!chromium) return argv;
  char **out = calloc((size_t)argc + 2, sizeof *out);
  if (!out) return argv;
  out[0] = argv[0];
  out[1] = (char *)"--no-sandbox";
  for (int i = 1; i < argc; i++) out[i + 1] = argv[i];
  return out;
}

__attribute__((visibility("hidden"))) char *const *bl_webhelper_argv(const char *path, char *const argv[]) {
  return chromium_argv(path, argv);
}
