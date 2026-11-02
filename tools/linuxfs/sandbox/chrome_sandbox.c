#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <sched.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <unistd.h>

extern char **environ;

static int adjust_oom_score(const char *pid_str, const char *score_str) {
  char *end = NULL;
  long pid = strtol(pid_str, &end, 10);
  if (!pid_str[0] || (end && *end) || pid <= 0) return 1;
  end = NULL;
  long score = strtol(score_str, &end, 10);
  if (!score_str[0] || (end && *end) || score < -1000 || score > 1000) return 1;

  char path[64];
  snprintf(path, sizeof(path), "/proc/%ld/oom_score_adj", pid);
  int fd = open(path, O_WRONLY | O_CLOEXEC);
  if (fd >= 0) {
    char buf[24];
    int n = snprintf(buf, sizeof(buf), "%ld", score);
    if (n > 0) {
      ssize_t unused = write(fd, buf, (size_t)n);
      (void)unused;
    }
    close(fd);
  }
  return 0;
}

static void restore_sandbox_environ(void) {
  if (!environ) return;
  size_t count = 0;
  while (environ[count]) count++;
  char **snapshot = calloc(count + 1, sizeof(char *));
  if (!snapshot) return;
  for (size_t i = 0; i < count; i++) {
    snapshot[i] = strdup(environ[i]);
  }
  const char prefix[] = "SANDBOX_";
  const size_t plen = sizeof(prefix) - 1;
  for (size_t i = 0; i < count; i++) {
    char *entry = snapshot[i];
    if (!entry) continue;
    if (strncmp(entry, prefix, plen) == 0) {
      char *eq = strchr(entry + plen, '=');
      if (eq && eq > entry + plen) {
        *eq = '\0';
        const char *orig_name = entry + plen;
        const char *val = eq + 1;
        setenv(orig_name, val, 1);
        char sbx_name[256];
        if (snprintf(sbx_name, sizeof(sbx_name), "SANDBOX_%s", orig_name) < (int)sizeof(sbx_name)) {
          unsetenv(sbx_name);
        }
      }
    }
    free(entry);
  }
  free(snapshot);
}

static void run_chroot_helper(int sock_fd, const char *jail_dir) {
  char msg = 0;
  ssize_t n;
  do {
    n = read(sock_fd, &msg, 1);
  } while (n < 0 && errno == EINTR);

  if (n == 0) {
    _exit(0);
  }
  if (n != 1 || msg != 'C') {
    _exit(1);
  }

  if (chdir(jail_dir) != 0) {
    int unused = chdir("/");
    (void)unused;
  }

  const char ok = 'O';
  do {
    n = write(sock_fd, &ok, 1);
  } while (n < 0 && errno == EINTR);
  _exit(n == 1 ? 0 : 1);
}

int main(int argc, char *argv[]) {
  if (argc < 2) return 1;

  if (argc == 2 && strcmp(argv[1], "--get-api") == 0) {
    puts("1");
    return 0;
  }

  if (argc == 4 && strcmp(argv[1], "--adjust-oom-score") == 0) {
    return adjust_oom_score(argv[2], argv[3]);
  }

  const char *api_rq = getenv("SBX_CHROME_API_RQ");
  if (api_rq && api_rq[0] && strcmp(api_rq, "1") != 0) {
    return 1;
  }

  restore_sandbox_environ();
  unsetenv("SBX_PID_NS");
  unsetenv("SBX_NET_NS");
  setenv("SBX_CHROME_API_PRV", "1", 1);

  const char *jail_dir = "/tmp/.linuxdroid-sbx-empty";
  mkdir(jail_dir, 0555);
  chmod(jail_dir, 0555);

  int sv[2];
  if (socketpair(AF_UNIX, SOCK_STREAM, 0, sv) != 0) {
    return 1;
  }

  pid_t helper = (pid_t)syscall(SYS_clone, CLONE_FS | SIGCHLD, 0, 0, 0, 0);
  if (helper < 0) {
    helper = fork();
  }
  if (helper < 0) {
    close(sv[0]);
    close(sv[1]);
    return 1;
  }

  if (helper == 0) {
    close(sv[1]);
    run_chroot_helper(sv[0], jail_dir);
    _exit(0);
  }

  close(sv[0]);
  fcntl(sv[1], F_SETFD, 0);

  char fd_buf[32];
  char pid_buf[32];
  snprintf(fd_buf, sizeof(fd_buf), "%d", sv[1]);
  snprintf(pid_buf, sizeof(pid_buf), "%d", (int)helper);
  setenv("SBX_D", fd_buf, 1);
  setenv("SBX_HELPER_PID", pid_buf, 1);

  execv(argv[1], &argv[1]);
  return 1;
}
