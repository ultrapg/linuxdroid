#define _GNU_SOURCE
#include <ctype.h>
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <grp.h>
#include <limits.h>
#include <pthread.h>
#include <pwd.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/syscall.h>
#include <sys/sysinfo.h>
#include <sys/types.h>
#include <sys/utsname.h>
#include <time.h>
#include <unistd.h>

#ifndef PROC_SUPER_MAGIC
#define PROC_SUPER_MAGIC 0x9fa0
#endif

#define PROC_KIND_NONE    0
#define PROC_KIND_STAT    1
#define PROC_KIND_UPTIME  2
#define PROC_KIND_LOADAVG 3
#define PROC_KIND_VMSTAT  4

#define MAX_CPUS 128
#define MAX_PIDS 512
#define TRACKED_MAX 64
#define SHM_MAGIC 0x424c5053U /* "BLPS" */
#define SHM_VERSION 1U

struct bl_pid_ticks {
  int32_t pid;
  uint64_t utime;
  uint64_t stime;
};

struct bl_procstat_state {
  uint32_t magic;
  uint32_t version;
  int32_t lock_pid;
  uint32_t ncpu;
  uint64_t last_boottime_ticks;
  uint64_t user[MAX_CPUS];
  uint64_t nice[MAX_CPUS];
  uint64_t sys[MAX_CPUS];
  uint64_t idle[MAX_CPUS];
  uint64_t iowait[MAX_CPUS];
  uint64_t irq[MAX_CPUS];
  uint64_t softirq[MAX_CPUS];
  uint64_t ctxt_total;
  uint64_t processes_total;
  uint32_t npids;
  struct bl_pid_ticks pids[MAX_PIDS];
};

struct tracked_proc_fd {
  int fd;
  int kind;
  ino_t ino;
};

static pthread_mutex_t proc_lock = PTHREAD_MUTEX_INITIALIZER;
static struct tracked_proc_fd tracked_fds[TRACKED_MAX];
static struct bl_procstat_state local_state;
static struct bl_procstat_state *shared_state;
static int shared_state_initialized;

static int raw_openat(int dirfd, const char *path, int flags, mode_t mode) {
  return (int)syscall(SYS_openat, dirfd, path, flags, mode);
}

static ssize_t raw_read(int fd, void *buf, size_t count) {
  return (ssize_t)syscall(SYS_read, fd, buf, count);
}

static ssize_t raw_write(int fd, const void *buf, size_t count) {
  return (ssize_t)syscall(SYS_write, fd, buf, count);
}

static int raw_close(int fd) {
  return (int)syscall(SYS_close, fd);
}

static off_t raw_lseek(int fd, off_t offset, int whence) {
  return (off_t)syscall(SYS_lseek, fd, offset, whence);
}

static int raw_ftruncate(int fd, off_t length) {
  return (int)syscall(SYS_ftruncate, fd, length);
}

static int write_all_raw(int fd, const char *data, size_t len) {
  while (len > 0) {
    ssize_t n = raw_write(fd, data, len);
    if (n < 0 && errno == EINTR) continue;
    if (n <= 0) return -1;
    data += (size_t)n;
    len -= (size_t)n;
  }
  return 0;
}

static struct bl_procstat_state *get_procstat_state(void) {
  if (shared_state_initialized) {
    return shared_state ? shared_state : &local_state;
  }
  shared_state_initialized = 1;
  const char *paths[] = {
    "/dev/shm/.linuxdroid-procstat-v1",
    "/tmp/.linuxdroid-procstat-v1",
  };
  for (size_t i = 0; i < sizeof(paths) / sizeof(paths[0]); i++) {
    int fd = raw_openat(AT_FDCWD, paths[i], O_RDWR | O_CREAT | O_CLOEXEC, 0666);
    if (fd < 0) continue;
    if (raw_ftruncate(fd, (off_t)sizeof(struct bl_procstat_state)) == 0) {
      void *map = mmap(NULL, sizeof(struct bl_procstat_state), PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
      raw_close(fd);
      if (map != MAP_FAILED) {
        shared_state = (struct bl_procstat_state *)map;
        return shared_state;
      }
    } else {
      raw_close(fd);
    }
  }
  return &local_state;
}

static void lock_state(struct bl_procstat_state *st) {
  int32_t self = (int32_t)syscall(SYS_gettid);
  for (int spin = 0; spin < 2000; spin++) {
    int32_t expected = 0;
    if (__atomic_compare_exchange_n(&st->lock_pid, &expected, self, 0, __ATOMIC_ACQUIRE, __ATOMIC_RELAXED)) {
      return;
    }
    struct timespec ts = {0, 50000};
    nanosleep(&ts, NULL);
  }
  __atomic_store_n(&st->lock_pid, self, __ATOMIC_SEQ_CST);
}

static void unlock_state(struct bl_procstat_state *st) {
  __atomic_store_n(&st->lock_pid, 0, __ATOMIC_RELEASE);
}

static int configured_cpu_count(void) {
  const char *env = getenv("BL_CPU_COUNT");
  if (env && *env) {
    int v = atoi(env);
    if (v >= 1 && v <= MAX_CPUS) return v;
  }
  long n = sysconf(_SC_NPROCESSORS_ONLN);
  if (n < 1) n = sysconf(_SC_NPROCESSORS_CONF);
  if (n < 1) n = 8;
  if (n > MAX_CPUS) n = MAX_CPUS;
  return (int)n;
}

static void get_boot_and_real_time(struct timespec *bt, struct timespec *rt) {
  if (clock_gettime(CLOCK_BOOTTIME, bt) != 0) {
    if (clock_gettime(CLOCK_MONOTONIC, bt) != 0) {
      bt->tv_sec = 3600;
      bt->tv_nsec = 0;
    }
  }
  if (clock_gettime(CLOCK_REALTIME, rt) != 0) {
    rt->tv_sec = (time_t)1730000000 + bt->tv_sec;
    rt->tv_nsec = 0;
  }
}

struct proc_scan_summary {
  unsigned int running;
  unsigned int blocked;
  unsigned int total_tasks;
  int max_pid;
  uint32_t npids;
  struct bl_pid_ticks pids[MAX_PIDS];
  uint32_t pid_cpu[MAX_PIDS];
  uint64_t raw_user[MAX_CPUS];
  uint64_t raw_sys[MAX_CPUS];
};

static int parse_pid_stat_line(const char *buf, char *state_out, uint64_t *utime_out,
                               uint64_t *stime_out, int *cpu_out) {
  const char *rparen = strrchr(buf, ')');
  if (!rparen || rparen[1] != ' ' || rparen[2] == '\0') return -1;
  *state_out = rparen[2];
  const char *p = rparen + 3;
  while (*p == ' ') p++;

  /* Fields after state (field 3) start at field 4 (ppid).
   * Field 14 = utime (index 10 after field 4)
   * Field 15 = stime (index 11 after field 4)
   * Field 39 = processor (index 35 after field 4)
   */
  uint64_t utime = 0, stime = 0;
  int cpu = 0;
  for (int idx = 0; idx <= 35 && *p; idx++) {
    char *end = NULL;
    if (idx == 10) {
      utime = strtoull(p, &end, 10);
    } else if (idx == 11) {
      stime = strtoull(p, &end, 10);
    } else if (idx == 35) {
      cpu = (int)strtol(p, &end, 10);
    } else {
      while (*p && *p != ' ' && *p != '\n') p++;
      end = (char *)p;
    }
    if (end == p) break;
    p = end;
    while (*p == ' ') p++;
  }
  *utime_out = utime;
  *stime_out = stime;
  *cpu_out = cpu >= 0 ? cpu : 0;
  return 0;
}

static void scan_proc_tasks(struct proc_scan_summary *sum, int ncpu) {
  memset(sum, 0, sizeof(*sum));
  sum->max_pid = (int)syscall(SYS_getpid);

  int pfd = raw_openat(AT_FDCWD, "/proc", O_RDONLY | O_DIRECTORY | O_CLOEXEC, 0);
  if (pfd < 0) {
    sum->running = 1;
    sum->total_tasks = 1;
    return;
  }
  DIR *dir = fdopendir(pfd);
  if (!dir) {
    raw_close(pfd);
    sum->running = 1;
    sum->total_tasks = 1;
    return;
  }

  struct dirent *de;
  while ((de = readdir(dir)) != NULL) {
    if (!isdigit((unsigned char)de->d_name[0])) continue;
    char *end = NULL;
    long pid = strtol(de->d_name, &end, 10);
    if (!end || *end != '\0' || pid <= 0) continue;

    char rel[64];
    snprintf(rel, sizeof(rel), "%ld/stat", pid);
    int sfd = raw_openat(pfd, rel, O_RDONLY | O_CLOEXEC, 0);
    if (sfd < 0) continue;
    char sbuf[1024];
    ssize_t n = raw_read(sfd, sbuf, sizeof(sbuf) - 1);
    raw_close(sfd);
    if (n <= 0) continue;
    sbuf[n] = '\0';

    char state = 'S';
    uint64_t utime = 0, stime = 0;
    int cpu = 0;
    if (parse_pid_stat_line(sbuf, &state, &utime, &stime, &cpu) != 0) continue;

    sum->total_tasks++;
    if ((int)pid > sum->max_pid) sum->max_pid = (int)pid;
    if (state == 'R') sum->running++;
    else if (state == 'D') sum->blocked++;

    int core = (ncpu > 0) ? (cpu % ncpu) : 0;
    if (core < 0 || core >= MAX_CPUS) core = 0;
    sum->raw_user[core] += utime;
    sum->raw_sys[core] += stime;

    if (sum->npids < MAX_PIDS) {
      uint32_t i = sum->npids++;
      sum->pids[i].pid = (int32_t)pid;
      sum->pids[i].utime = utime;
      sum->pids[i].stime = stime;
      sum->pid_cpu[i] = (uint32_t)core;
    }
  }
  closedir(dir);
  if (sum->running == 0) sum->running = 1;
  if (sum->total_tasks == 0) sum->total_tasks = 1;
}

static size_t generate_proc_stat(char *buf, size_t cap) {
  int ncpu = configured_cpu_count();
  struct timespec bt, rt;
  get_boot_and_real_time(&bt, &rt);

  uint64_t now_ticks = (uint64_t)bt.tv_sec * 100ULL + (uint64_t)(bt.tv_nsec / 10000000ULL);
  if (now_ticks < 100ULL) now_ticks = 360000ULL;
  long long btime = (long long)rt.tv_sec - (long long)bt.tv_sec;
  if (btime <= 0) btime = 1730000000LL;

  struct proc_scan_summary scan;
  scan_proc_tasks(&scan, ncpu);

  uint64_t user[MAX_CPUS], nice_v[MAX_CPUS], sys_v[MAX_CPUS], idle[MAX_CPUS];
  uint64_t iowait[MAX_CPUS], irq[MAX_CPUS], softirq[MAX_CPUS];
  uint64_t ctxt_total = 0;
  uint64_t processes_total = 0;

  struct bl_procstat_state *st = get_procstat_state();
  lock_state(st);

  if (st->magic != SHM_MAGIC || st->version != SHM_VERSION ||
      st->last_boottime_ticks == 0 || now_ticks < st->last_boottime_ticks) {
    st->magic = SHM_MAGIC;
    st->version = SHM_VERSION;
    st->ncpu = (uint32_t)ncpu;
    st->last_boottime_ticks = now_ticks;
    for (int c = 0; c < MAX_CPUS; c++) {
      uint64_t base_u = scan.raw_user[c] + (now_ticks / 25ULL) + (uint64_t)(c * 13);
      uint64_t base_s = scan.raw_sys[c] + (now_ticks / 50ULL) + (uint64_t)(c * 7);
      uint64_t busy = base_u + base_s;
      st->user[c] = base_u;
      st->nice[c] = 0;
      st->sys[c] = base_s;
      st->idle[c] = (now_ticks > busy) ? (now_ticks - busy) : (now_ticks / 2ULL + 1000ULL);
      st->iowait[c] = now_ticks / 500ULL;
      st->irq[c] = 0;
      st->softirq[c] = now_ticks / 400ULL;
    }
    st->ctxt_total = now_ticks * (uint64_t)ncpu * 12ULL;
    st->processes_total = (scan.max_pid > 256) ? (uint64_t)scan.max_pid : 256ULL;
  } else {
    uint64_t dt = now_ticks - st->last_boottime_ticks;
    uint64_t du_core[MAX_CPUS];
    uint64_t ds_core[MAX_CPUS];
    memset(du_core, 0, sizeof(du_core));
    memset(ds_core, 0, sizeof(ds_core));

    for (uint32_t i = 0; i < scan.npids; i++) {
      int32_t pid = scan.pids[i].pid;
      uint64_t u = scan.pids[i].utime;
      uint64_t s = scan.pids[i].stime;
      uint32_t core = scan.pid_cpu[i] % (uint32_t)ncpu;
      int found = 0;
      for (uint32_t j = 0; j < st->npids; j++) {
        if (st->pids[j].pid == pid) {
          found = 1;
          uint64_t du = (u >= st->pids[j].utime) ? (u - st->pids[j].utime) : u;
          uint64_t ds = (s >= st->pids[j].stime) ? (s - st->pids[j].stime) : s;
          du_core[core] += du;
          ds_core[core] += ds;
          break;
        }
      }
      if (!found) {
        du_core[core] += u;
        ds_core[core] += s;
        st->processes_total++;
      }
    }

    for (int c = 0; c < ncpu; c++) {
      st->user[c] += du_core[c];
      st->sys[c] += ds_core[c];
      uint64_t busy = du_core[c] + ds_core[c];
      if (dt > busy) {
        st->idle[c] += (dt - busy);
      } else if (dt > 0 && busy == 0) {
        st->idle[c] += dt;
      }
      st->ctxt_total += busy * 20ULL + dt * 4ULL;
    }
    if ((uint64_t)scan.max_pid > st->processes_total) {
      st->processes_total = (uint64_t)scan.max_pid;
    }
    st->last_boottime_ticks = now_ticks;
  }

  st->npids = scan.npids;
  if (scan.npids > 0) {
    memcpy(st->pids, scan.pids, scan.npids * sizeof(struct bl_pid_ticks));
  }

  for (int c = 0; c < ncpu; c++) {
    user[c] = st->user[c];
    nice_v[c] = st->nice[c];
    sys_v[c] = st->sys[c];
    idle[c] = st->idle[c];
    iowait[c] = st->iowait[c];
    irq[c] = st->irq[c];
    softirq[c] = st->softirq[c];
  }
  ctxt_total = st->ctxt_total;
  processes_total = st->processes_total;
  unlock_state(st);

  uint64_t agg_u = 0, agg_n = 0, agg_s = 0, agg_id = 0, agg_io = 0, agg_irq = 0, agg_sirq = 0;
  for (int c = 0; c < ncpu; c++) {
    agg_u += user[c];
    agg_n += nice_v[c];
    agg_s += sys_v[c];
    agg_id += idle[c];
    agg_io += iowait[c];
    agg_irq += irq[c];
    agg_sirq += softirq[c];
  }

  size_t off = 0;
  int w = snprintf(buf + off, cap - off,
                   "cpu  %llu %llu %llu %llu %llu %llu %llu 0 0 0\n",
                   (unsigned long long)agg_u, (unsigned long long)agg_n,
                   (unsigned long long)agg_s, (unsigned long long)agg_id,
                   (unsigned long long)agg_io, (unsigned long long)agg_irq,
                   (unsigned long long)agg_sirq);
  if (w > 0 && (size_t)w < cap - off) off += (size_t)w;

  for (int c = 0; c < ncpu; c++) {
    w = snprintf(buf + off, cap - off,
                 "cpu%d %llu %llu %llu %llu %llu %llu %llu 0 0 0\n",
                 c,
                 (unsigned long long)user[c], (unsigned long long)nice_v[c],
                 (unsigned long long)sys_v[c], (unsigned long long)idle[c],
                 (unsigned long long)iowait[c], (unsigned long long)irq[c],
                 (unsigned long long)softirq[c]);
    if (w > 0 && (size_t)w < cap - off) off += (size_t)w;
  }

  unsigned long long intr_total = (unsigned long long)(ctxt_total / 2ULL + 1024ULL);
  unsigned long long sirq_total = (unsigned long long)(agg_sirq > 0 ? agg_sirq : intr_total / 4ULL);
  w = snprintf(buf + off, cap - off,
               "intr %llu 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0\n"
               "ctxt %llu\n"
               "btime %lld\n"
               "processes %llu\n"
               "procs_running %u\n"
               "procs_blocked %u\n"
               "softirq %llu 0 %llu 0 %llu 0 0 0 %llu 0 %llu\n",
               intr_total,
               (unsigned long long)ctxt_total,
               btime,
               (unsigned long long)processes_total,
               scan.running,
               scan.blocked,
               sirq_total,
               sirq_total / 4ULL,
               sirq_total / 8ULL,
               sirq_total / 4ULL,
               sirq_total - (sirq_total / 4ULL) - (sirq_total / 8ULL) - (sirq_total / 4ULL));
  if (w > 0 && (size_t)w < cap - off) off += (size_t)w;
  return off;
}

static size_t generate_proc_uptime(char *buf, size_t cap) {
  int ncpu = configured_cpu_count();
  struct timespec bt, rt;
  get_boot_and_real_time(&bt, &rt);
  double up = (double)bt.tv_sec + (double)bt.tv_nsec / 1e9;
  if (up < 1.0) up = 3600.0;
  double idle = up * 0.85 * (double)ncpu;
  int n = snprintf(buf, cap, "%.2f %.2f\n", up, idle);
  return (n > 0 && (size_t)n < cap) ? (size_t)n : 0;
}

static size_t generate_proc_loadavg(char *buf, size_t cap) {
  struct proc_scan_summary scan;
  scan_proc_tasks(&scan, configured_cpu_count());
  double l1 = 0.20, l5 = 0.15, l15 = 0.10;
  unsigned int total_procs = scan.total_tasks;
  struct sysinfo si;
  if (sysinfo(&si) == 0) {
    double scale = (double)(1U << SI_LOAD_SHIFT);
    l1 = (double)si.loads[0] / scale;
    l5 = (double)si.loads[1] / scale;
    l15 = (double)si.loads[2] / scale;
    if (si.procs > total_procs) total_procs = si.procs;
  }
  int n = snprintf(buf, cap, "%.2f %.2f %.2f %u/%u %d\n",
                   l1, l5, l15, scan.running, total_procs, scan.max_pid);
  return (n > 0 && (size_t)n < cap) ? (size_t)n : 0;
}

static size_t generate_proc_vmstat(char *buf, size_t cap) {
  unsigned long long nr_free = 262144ULL;
  unsigned long long nr_inact_anon = 32768ULL;
  unsigned long long nr_act_anon = 65536ULL;
  unsigned long long nr_inact_file = 65536ULL;
  unsigned long long nr_act_file = 131072ULL;
  unsigned long long nr_unevict = 4096ULL;
  unsigned long long nr_mlock = 4096ULL;
  unsigned long long nr_slab_rec = 16384ULL;
  unsigned long long nr_slab_unrec = 16384ULL;
  unsigned long long nr_anon = 98304ULL;
  unsigned long long nr_mapped = 65536ULL;
  unsigned long long nr_file = 212992ULL;
  unsigned long long nr_dirty = 0ULL;
  unsigned long long nr_writeback = 0ULL;
  unsigned long long nr_shmem = 16384ULL;
  unsigned long long nr_kstack = 8192ULL;
  unsigned long long nr_ptables = 8192ULL;

  int mfd = raw_openat(AT_FDCWD, "/proc/meminfo", O_RDONLY | O_CLOEXEC, 0);
  if (mfd >= 0) {
    char mbuf[4096];
    ssize_t n = raw_read(mfd, mbuf, sizeof(mbuf) - 1);
    raw_close(mfd);
    if (n > 0) {
      mbuf[n] = '\0';
      unsigned long long cached_kb = 0, buffers_kb = 0;
      char *line = mbuf;
      while (*line) {
        char *nl = strchr(line, '\n');
        if (nl) *nl = '\0';
        unsigned long long val = 0;
        if (sscanf(line, "MemFree: %llu", &val) == 1) nr_free = val / 4ULL;
        else if (sscanf(line, "Buffers: %llu", &val) == 1) buffers_kb = val;
        else if (sscanf(line, "Cached: %llu", &val) == 1) cached_kb = val;
        else if (sscanf(line, "Active(anon): %llu", &val) == 1) nr_act_anon = val / 4ULL;
        else if (sscanf(line, "Inactive(anon): %llu", &val) == 1) nr_inact_anon = val / 4ULL;
        else if (sscanf(line, "Active(file): %llu", &val) == 1) nr_act_file = val / 4ULL;
        else if (sscanf(line, "Inactive(file): %llu", &val) == 1) nr_inact_file = val / 4ULL;
        else if (sscanf(line, "Unevictable: %llu", &val) == 1) nr_unevict = val / 4ULL;
        else if (sscanf(line, "Mlocked: %llu", &val) == 1) nr_mlock = val / 4ULL;
        else if (sscanf(line, "Dirty: %llu", &val) == 1) nr_dirty = val / 4ULL;
        else if (sscanf(line, "Writeback: %llu", &val) == 1) nr_writeback = val / 4ULL;
        else if (sscanf(line, "AnonPages: %llu", &val) == 1) nr_anon = val / 4ULL;
        else if (sscanf(line, "Mapped: %llu", &val) == 1) nr_mapped = val / 4ULL;
        else if (sscanf(line, "Shmem: %llu", &val) == 1) nr_shmem = val / 4ULL;
        else if (sscanf(line, "SReclaimable: %llu", &val) == 1) nr_slab_rec = val / 4ULL;
        else if (sscanf(line, "SUnreclaim: %llu", &val) == 1) nr_slab_unrec = val / 4ULL;
        else if (sscanf(line, "KernelStack: %llu", &val) == 1) nr_kstack = val;
        else if (sscanf(line, "PageTables: %llu", &val) == 1) nr_ptables = val / 4ULL;
        if (!nl) break;
        line = nl + 1;
      }
      if (cached_kb + buffers_kb > 0) nr_file = (cached_kb + buffers_kb) / 4ULL;
    }
  }

  struct timespec bt, rt;
  get_boot_and_real_time(&bt, &rt);
  unsigned long long ticks = (unsigned long long)bt.tv_sec * 100ULL + (unsigned long long)(bt.tv_nsec / 10000000ULL);

  int w = snprintf(
      buf, cap,
      "nr_free_pages %llu\n"
      "nr_zone_inactive_anon %llu\n"
      "nr_zone_active_anon %llu\n"
      "nr_zone_inactive_file %llu\n"
      "nr_zone_active_file %llu\n"
      "nr_zone_unevictable %llu\n"
      "nr_zone_write_pending %llu\n"
      "nr_mlock %llu\n"
      "nr_bounce 0\n"
      "nr_zspages 0\n"
      "nr_free_cma 0\n"
      "nr_inactive_anon %llu\n"
      "nr_active_anon %llu\n"
      "nr_inactive_file %llu\n"
      "nr_active_file %llu\n"
      "nr_unevictable %llu\n"
      "nr_slab_reclaimable %llu\n"
      "nr_slab_unreclaimable %llu\n"
      "nr_isolated_anon 0\n"
      "nr_isolated_file 0\n"
      "nr_anon_pages %llu\n"
      "nr_mapped %llu\n"
      "nr_file_pages %llu\n"
      "nr_dirty %llu\n"
      "nr_writeback %llu\n"
      "nr_writeback_temp 0\n"
      "nr_shmem %llu\n"
      "nr_shmem_hugepages 0\n"
      "nr_shmem_pmdmapped 0\n"
      "nr_file_hugepages 0\n"
      "nr_file_pmdmapped 0\n"
      "nr_anon_transparent_hugepages 0\n"
      "nr_vmscan_write 0\n"
      "nr_vmscan_immediate_reclaim 0\n"
      "nr_dirtied 1024\n"
      "nr_written 1024\n"
      "nr_kernel_misc_reclaimable 0\n"
      "nr_foll_pin_acquired 0\n"
      "nr_foll_pin_released 0\n"
      "nr_kernel_stack %llu\n"
      "nr_page_table_pages %llu\n"
      "nr_dirty_threshold 52428\n"
      "nr_dirty_background_threshold 26214\n"
      "pgpgin %llu\n"
      "pgpgout %llu\n"
      "pswpin 0\n"
      "pswpout 0\n"
      "pgalloc_dma 0\n"
      "pgalloc_dma32 0\n"
      "pgalloc_normal %llu\n"
      "pgalloc_movable 0\n"
      "pgfree %llu\n"
      "pgactivate %llu\n"
      "pgdeactivate %llu\n"
      "pglazyfree 0\n"
      "pgfault %llu\n"
      "pgmajfault 1024\n"
      "pglazyfreed 0\n"
      "pgrefill 0\n"
      "pgsteal_kswapd 0\n"
      "pgsteal_direct 0\n"
      "pgscan_kswapd 0\n"
      "pgscan_direct 0\n"
      "pgscan_direct_throttle 0\n"
      "pgscan_anon 0\n"
      "pgscan_file 0\n"
      "pgsteal_anon 0\n"
      "pgsteal_file 0\n"
      "pginodesteal 0\n"
      "slabs_scanned 0\n"
      "kswapd_inodesteal 0\n"
      "kswapd_low_wmark_hit_quickly 0\n"
      "kswapd_high_wmark_hit_quickly 0\n"
      "pageoutrun 0\n"
      "pgrotated 0\n"
      "drop_pagecache 0\n"
      "drop_slab 0\n"
      "oom_kill 0\n",
      nr_free,
      nr_inact_anon, nr_act_anon, nr_inact_file, nr_act_file, nr_unevict, nr_writeback, nr_mlock,
      nr_inact_anon, nr_act_anon, nr_inact_file, nr_act_file, nr_unevict,
      nr_slab_rec, nr_slab_unrec, nr_anon, nr_mapped, nr_file, nr_dirty, nr_writeback, nr_shmem,
      nr_kstack, nr_ptables,
      131072ULL + ticks * 4ULL,
      32768ULL + ticks * 2ULL,
      524288ULL + ticks * 16ULL,
      786432ULL + ticks * 16ULL,
      65536ULL + ticks * 2ULL,
      16384ULL + ticks,
      1048576ULL + ticks * 32ULL);
  return (w > 0 && (size_t)w < cap) ? (size_t)w : 0;
}

static size_t generate_dynamic_proc_kind(int kind, char *buf, size_t cap) {
  switch (kind) {
    case PROC_KIND_STAT:
      return generate_proc_stat(buf, cap);
    case PROC_KIND_UPTIME:
      return generate_proc_uptime(buf, cap);
    case PROC_KIND_LOADAVG:
      return generate_proc_loadavg(buf, cap);
    case PROC_KIND_VMSTAT:
      return generate_proc_vmstat(buf, cap);
    default:
      return 0;
  }
}

static void register_tracked_fd(int fd, int kind) {
  if (fd < 0 || kind == PROC_KIND_NONE) return;
  struct stat st;
  if (fstat(fd, &st) != 0) return;
  pthread_mutex_lock(&proc_lock);
  int free_slot = -1;
  for (int i = 0; i < TRACKED_MAX; i++) {
    if (tracked_fds[i].fd == fd) {
      tracked_fds[i].kind = kind;
      tracked_fds[i].ino = st.st_ino;
      pthread_mutex_unlock(&proc_lock);
      return;
    }
    if (free_slot < 0 && tracked_fds[i].kind == PROC_KIND_NONE) {
      free_slot = i;
    }
  }
  if (free_slot < 0) free_slot = fd % TRACKED_MAX;
  tracked_fds[free_slot].fd = fd;
  tracked_fds[free_slot].kind = kind;
  tracked_fds[free_slot].ino = st.st_ino;
  pthread_mutex_unlock(&proc_lock);
}

__attribute__((visibility("hidden"))) int bl_proc_is_tracked_fd(int fd) {
  if (fd < 0) return 0;
  int kind = PROC_KIND_NONE;
  ino_t ino = 0;
  pthread_mutex_lock(&proc_lock);
  for (int i = 0; i < TRACKED_MAX; i++) {
    if (tracked_fds[i].fd == fd && tracked_fds[i].kind != PROC_KIND_NONE) {
      kind = tracked_fds[i].kind;
      ino = tracked_fds[i].ino;
      break;
    }
  }
  pthread_mutex_unlock(&proc_lock);
  if (kind == PROC_KIND_NONE) return 0;
  struct stat st;
  return fstat(fd, &st) == 0 && st.st_ino == ino;
}

__attribute__((visibility("hidden"))) void bl_proc_close(int fd) {
  if (fd < 0) return;
  pthread_mutex_lock(&proc_lock);
  for (int i = 0; i < TRACKED_MAX; i++) {
    if (tracked_fds[i].fd == fd) {
      tracked_fds[i].fd = -1;
      tracked_fds[i].kind = PROC_KIND_NONE;
      tracked_fds[i].ino = 0;
    }
  }
  pthread_mutex_unlock(&proc_lock);
}

__attribute__((visibility("hidden"))) void bl_proc_refresh_fd(int fd) {
  if (fd < 0) return;
  int kind = PROC_KIND_NONE;
  ino_t expected_ino = 0;
  pthread_mutex_lock(&proc_lock);
  for (int i = 0; i < TRACKED_MAX; i++) {
    if (tracked_fds[i].fd == fd && tracked_fds[i].kind != PROC_KIND_NONE) {
      kind = tracked_fds[i].kind;
      expected_ino = tracked_fds[i].ino;
      break;
    }
  }
  pthread_mutex_unlock(&proc_lock);
  if (kind == PROC_KIND_NONE) return;

  int saved = errno;
  struct stat st;
  if (fstat(fd, &st) != 0 || st.st_ino != expected_ino) {
    bl_proc_close(fd);
    errno = saved;
    return;
  }

  char buf[16384];
  size_t len = generate_dynamic_proc_kind(kind, buf, sizeof(buf));
  if (len > 0 && raw_ftruncate(fd, 0) == 0 && raw_lseek(fd, 0, SEEK_SET) == 0) {
    write_all_raw(fd, buf, len);
    raw_lseek(fd, 0, SEEK_SET);
  }
  errno = saved;
}

static int create_memfd_with_content(const char *tag, const char *data, size_t len, int flags, int track_kind) {
  int fd = memfd_create(tag, (flags & O_CLOEXEC) ? MFD_CLOEXEC : 0);
  if (fd < 0) {
    char tmp[] = "/tmp/.linuxdroid-proc-XXXXXX";
    fd = mkostemp(tmp, flags & O_CLOEXEC);
    if (fd < 0) return -1;
    unlink(tmp);
  }
  if (write_all_raw(fd, data, len) != 0 || raw_lseek(fd, 0, SEEK_SET) != 0) {
    raw_close(fd);
    return -1;
  }
  if (track_kind != PROC_KIND_NONE) {
    register_tracked_fd(fd, track_kind);
  }
  return fd;
}

static int normalize_proc_target(int dirfd, const char *path, char *out, size_t out_cap) {
  if (!path || !*path) return 0;
  if (path[0] == '/') {
    size_t j = 0;
    for (size_t i = 0; path[i] && j + 1 < out_cap; i++) {
      if (path[i] == '/' && i > 0 && path[i - 1] == '/') continue;
      if (path[i] == '/' && path[i + 1] == '.' && (path[i + 2] == '/' || path[i + 2] == '\0')) {
        i += 1;
        continue;
      }
      out[j++] = path[i];
    }
    while (j > 1 && out[j - 1] == '/') j--;
    out[j] = '\0';
    return strncmp(out, "/proc/", 6) == 0;
  }

  if (dirfd >= 0 &&
      (strcmp(path, "stat") == 0 || strcmp(path, "uptime") == 0 ||
       strcmp(path, "loadavg") == 0 || strcmp(path, "vmstat") == 0 ||
       strcmp(path, "diskstats") == 0 || strcmp(path, "swaps") == 0 ||
       strcmp(path, "version") == 0 || strcmp(path, "locks") == 0 ||
       strcmp(path, "tty/drivers") == 0 || strncmp(path, "1/", 2) == 0)) {
    char fdlink[64];
    char resolved[128];
    snprintf(fdlink, sizeof(fdlink), "/proc/self/fd/%d", dirfd);
    ssize_t n = (ssize_t)syscall(SYS_readlinkat, AT_FDCWD, fdlink, resolved, sizeof(resolved) - 1);
    if (n > 0) {
      resolved[n] = '\0';
      if (strcmp(resolved, "/proc") == 0) {
        snprintf(out, out_cap, "/proc/%s", path);
        return 1;
      }
    }
  }
  return 0;
}

static int dynamic_kind_for_path(const char *norm_path) {
  if (strcmp(norm_path, "/proc/stat") == 0) return PROC_KIND_STAT;
  if (strcmp(norm_path, "/proc/uptime") == 0) return PROC_KIND_UPTIME;
  if (strcmp(norm_path, "/proc/loadavg") == 0) return PROC_KIND_LOADAVG;
  if (strcmp(norm_path, "/proc/vmstat") == 0) return PROC_KIND_VMSTAT;
  return PROC_KIND_NONE;
}

static int should_synthesize_dynamic_proc(const char *norm_path, int kind) {
  const char *force_env = getenv("BL_CPU_COUNT");
  if (kind == PROC_KIND_STAT && force_env && *force_env) {
    return 1;
  }
  int fd = raw_openat(AT_FDCWD, norm_path, O_RDONLY | O_CLOEXEC, 0);
  if (fd < 0) return 1;

  struct statfs sfs;
  if (fstatfs(fd, &sfs) == 0 && (unsigned long)sfs.f_type != (unsigned long)PROC_SUPER_MAGIC) {
    raw_close(fd);
    return 1;
  }

  char probe[4096];
  ssize_t n = raw_read(fd, probe, sizeof(probe) - 1);
  raw_close(fd);
  if (n <= 0) return 1;
  probe[n] = '\0';
  if (kind == PROC_KIND_STAT && strstr(probe, "\nbtime ") == NULL) {
    return 1;
  }
  return 0;
}

static const char default_tty_drivers[] =
    "/dev/tty             /dev/tty        5       0 system:/dev/tty\n"
    "/dev/console         /dev/console    5       1 system:console\n"
    "/dev/ptmx            /dev/ptmx       5       2 system\n"
    "rfcomm               /dev/rfcomm   216 0-255 serial\n"
    "ttyprintk            /dev/ttyprintk   5       3 console\n"
    "serial               /dev/ttyS       4 64-95 serial\n"
    "pty_slave            /dev/pts      136 0-1048575 pty:slave\n"
    "pty_master           /dev/ptm      128 0-1048575 pty:master\n";

static const char default_swaps[] =
    "Filename\t\t\t\tType\t\tSize\t\tUsed\t\tPriority\n";

static const char default_diskstats[] =
    "   7       0 loop0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0\n";

__attribute__((visibility("hidden"))) int bl_proc_open(int dirfd, const char *path, int flags) {
  if ((flags & O_ACCMODE) != O_RDONLY || (flags & (O_CREAT | O_TRUNC | O_DIRECTORY))) {
    return -1;
  }
  char norm[256];
  if (!normalize_proc_target(dirfd, path, norm, sizeof(norm))) {
    return -1;
  }

  int saved = errno;

  /* Redirect /proc/1/{mounts,mountinfo,mountstats,cgroup,status,stat,cmdline} to /proc/self/... if /proc/1/... is blocked by Android SELinux */
  if (strncmp(norm, "/proc/1/", 8) == 0) {
    const char *sub = norm + 8;
    if (strcmp(sub, "mounts") == 0 || strcmp(sub, "mountinfo") == 0 ||
        strcmp(sub, "mountstats") == 0 || strcmp(sub, "cgroup") == 0) {
      int fd = raw_openat(AT_FDCWD, norm, flags, 0);
      if (fd >= 0) {
        errno = saved;
        return fd;
      }
      char self_path[280];
      snprintf(self_path, sizeof(self_path), "/proc/self/%s", sub);
      fd = raw_openat(AT_FDCWD, self_path, flags, 0);
      if (fd >= 0) {
        errno = saved;
        return fd;
      }
    }
    errno = saved;
    return -1;
  }

  int kind = dynamic_kind_for_path(norm);
  if (kind != PROC_KIND_NONE) {
    if (should_synthesize_dynamic_proc(norm, kind)) {
      char buf[16384];
      size_t len = generate_dynamic_proc_kind(kind, buf, sizeof(buf));
      if (len > 0) {
        int mfd = create_memfd_with_content("linuxdroid-proc", buf, len, flags, kind);
        if (mfd >= 0) {
          errno = saved;
          return mfd;
        }
      }
    }
    errno = saved;
    return -1;
  }

  const char *static_fallback = NULL;
  char dynamic_small[512];
  if (strcmp(norm, "/proc/tty/drivers") == 0) {
    static_fallback = default_tty_drivers;
  } else if (strcmp(norm, "/proc/swaps") == 0) {
    static_fallback = default_swaps;
  } else if (strcmp(norm, "/proc/diskstats") == 0) {
    static_fallback = default_diskstats;
  } else if (strcmp(norm, "/proc/locks") == 0) {
    static_fallback = "";
  } else if (strcmp(norm, "/proc/sys/fs/file-nr") == 0) {
    static_fallback = "1024\t0\t9223372036854775807\n";
  } else if (strcmp(norm, "/proc/sys/kernel/pid_max") == 0) {
    static_fallback = "32768\n";
  } else if (strcmp(norm, "/proc/sys/kernel/sched_autogroup_enabled") == 0) {
    static_fallback = "0\n";
  } else if (strcmp(norm, "/proc/version") == 0) {
    struct utsname uts;
    if (uname(&uts) == 0) {
      snprintf(dynamic_small, sizeof(dynamic_small),
               "Linux version %s (linuxdroid@android) (gcc (Ubuntu 13.3.0) 13.3.0) %s\n",
               uts.release, uts.version);
      static_fallback = dynamic_small;
    }
  } else if (strcmp(norm, "/proc/sys/kernel/osrelease") == 0) {
    struct utsname uts;
    if (uname(&uts) == 0) {
      snprintf(dynamic_small, sizeof(dynamic_small), "%s\n", uts.release);
      static_fallback = dynamic_small;
    }
  }

  if (static_fallback != NULL) {
    int fd = raw_openat(AT_FDCWD, norm, flags, 0);
    if (fd >= 0) {
      char ch;
      ssize_t n = raw_read(fd, &ch, 1);
      if (n > 0 || *static_fallback == '\0') {
        raw_lseek(fd, 0, SEEK_SET);
        errno = saved;
        return fd;
      }
      raw_close(fd);
    }
    int mfd = create_memfd_with_content("linuxdroid-proc-ro", static_fallback,
                                        strlen(static_fallback), flags, PROC_KIND_NONE);
    if (mfd >= 0) {
      errno = saved;
      return mfd;
    }
  }

  errno = saved;
  return -1;
}

/* Intercept lseek / lseek64 / fseek / fseeko / fseeko64 / rewind to refresh tracked dynamic /proc fds */
off_t lseek(int fd, off_t offset, int whence) {
  static off_t (*real_lseek)(int, off_t, int);
  if (!real_lseek) real_lseek = (off_t (*)(int, off_t, int))dlsym(RTLD_NEXT, "lseek");
  if (offset == 0 && whence == SEEK_SET && fd >= 0) {
    bl_proc_refresh_fd(fd);
  }
  return real_lseek ? real_lseek(fd, offset, whence) : raw_lseek(fd, offset, whence);
}

off_t lseek64(int fd, off_t offset, int whence) __attribute__((alias("lseek")));

int fseek(FILE *stream, long offset, int whence) {
  static int (*real_fseek)(FILE *, long, int);
  if (!real_fseek) real_fseek = (int (*)(FILE *, long, int))dlsym(RTLD_NEXT, "fseek");
  if (offset == 0 && whence == SEEK_SET) {
    int fd = fileno(stream);
    if (fd >= 0) bl_proc_refresh_fd(fd);
  }
  return real_fseek(stream, offset, whence);
}

int fseeko(FILE *stream, off_t offset, int whence) {
  static int (*real_fseeko)(FILE *, off_t, int);
  if (!real_fseeko) real_fseeko = (int (*)(FILE *, off_t, int))dlsym(RTLD_NEXT, "fseeko");
  if (offset == 0 && whence == SEEK_SET) {
    int fd = fileno(stream);
    if (fd >= 0) bl_proc_refresh_fd(fd);
  }
  return real_fseeko(stream, offset, whence);
}

int fseeko64(FILE *stream, off_t offset, int whence) __attribute__((alias("fseeko")));

void rewind(FILE *stream) {
  static void (*real_rewind)(FILE *);
  if (!real_rewind) real_rewind = (void (*)(FILE *))dlsym(RTLD_NEXT, "rewind");
  int fd = fileno(stream);
  if (fd >= 0) bl_proc_refresh_fd(fd);
  real_rewind(stream);
}

/* Android supplementary GID & UID fallback resolution */
static void format_android_group_name(gid_t gid, char *buf, size_t cap) {
  switch (gid) {
    case 1015: snprintf(buf, cap, "sdcard_rw"); return;
    case 1023: snprintf(buf, cap, "media_rw"); return;
    case 1028: snprintf(buf, cap, "sdcard_r"); return;
    case 1077: snprintf(buf, cap, "external_storage"); return;
    case 1078: snprintf(buf, cap, "ext_data_rw"); return;
    case 1079: snprintf(buf, cap, "ext_obb_rw"); return;
    case 3001: snprintf(buf, cap, "net_bt_admin"); return;
    case 3002: snprintf(buf, cap, "net_bt"); return;
    case 3003: snprintf(buf, cap, "inet"); return;
    case 3004: snprintf(buf, cap, "net_raw"); return;
    case 3005: snprintf(buf, cap, "net_admin"); return;
    case 3006: snprintf(buf, cap, "net_bw_stats"); return;
    case 3007: snprintf(buf, cap, "net_bw_acct"); return;
    case 3009: snprintf(buf, cap, "readproc"); return;
    case 3010: snprintf(buf, cap, "wakelock"); return;
    case 3011: snprintf(buf, cap, "uhid"); return;
    case 3012: snprintf(buf, cap, "readtracefs"); return;
    case 9997: snprintf(buf, cap, "everybody"); return;
    default: break;
  }
  unsigned int u = (unsigned int)gid / 100000U;
  unsigned int rem = (unsigned int)gid % 100000U;
  if (rem >= 20000U && rem <= 29999U) {
    snprintf(buf, cap, "u%u_a%u_cache", u, rem - 20000U);
  } else if (rem >= 30000U && rem <= 39999U) {
    snprintf(buf, cap, "u%u_a%u_ext", u, rem - 30000U);
  } else if (rem >= 40000U && rem <= 49999U) {
    snprintf(buf, cap, "u%u_a%u_ext_cache", u, rem - 40000U);
  } else if (rem >= 50000U && rem <= 59999U) {
    if (u == 0U) snprintf(buf, cap, "all_a%u", rem - 50000U);
    else snprintf(buf, cap, "u%u_all_a%u", u, rem - 50000U);
  } else {
    snprintf(buf, cap, "aid_%u", (unsigned int)gid);
  }
}

static int should_synthesize_gid(gid_t gid) {
  if (gid == 0) return 0;
  if (gid == getgid() || gid == getegid()) return 1;
  gid_t list[128];
  int ng = getgroups((int)(sizeof(list) / sizeof(list[0])), list);
  for (int i = 0; i < ng; i++) {
    if (list[i] == gid) return 1;
  }
  unsigned int rem = (unsigned int)gid % 100000U;
  if ((gid >= 1000 && gid <= 9999) || (rem >= 20000U && rem <= 59999U)) {
    return 1;
  }
  return 0;
}

struct group *getgrgid(gid_t gid) {
  static struct group *(*real_getgrgid)(gid_t);
  if (!real_getgrgid) real_getgrgid = (struct group *(*)(gid_t))dlsym(RTLD_NEXT, "getgrgid");
  int saved = errno;
  struct group *g = real_getgrgid ? real_getgrgid(gid) : NULL;
  if (g != NULL) return g;
  if (!should_synthesize_gid(gid)) return NULL;

  static __thread struct group tls_grp;
  static __thread char tls_name[48];
  static __thread char *tls_mem[1];
  format_android_group_name(gid, tls_name, sizeof(tls_name));
  tls_mem[0] = NULL;
  tls_grp.gr_name = tls_name;
  tls_grp.gr_passwd = (char *)"x";
  tls_grp.gr_gid = gid;
  tls_grp.gr_mem = tls_mem;
  errno = saved;
  return &tls_grp;
}

int getgrgid_r(gid_t gid, struct group *grp, char *buf, size_t buflen, struct group **result) {
  static int (*real_getgrgid_r)(gid_t, struct group *, char *, size_t, struct group **);
  if (!real_getgrgid_r) {
    real_getgrgid_r = (int (*)(gid_t, struct group *, char *, size_t, struct group **))dlsym(RTLD_NEXT, "getgrgid_r");
  }
  int saved = errno;
  int rc = real_getgrgid_r ? real_getgrgid_r(gid, grp, buf, buflen, result) : ENOENT;
  if (rc == 0 && result && *result != NULL) return 0;
  if (!grp || !buf || !result || !should_synthesize_gid(gid)) {
    return rc;
  }

  char name[48];
  format_android_group_name(gid, name, sizeof(name));
  size_t name_len = strlen(name) + 1;
  uintptr_t base = (uintptr_t)buf;
  uintptr_t mem_ptr_addr = (base + name_len + 2 + sizeof(char *) - 1) & ~(uintptr_t)(sizeof(char *) - 1);
  size_t needed = (size_t)(mem_ptr_addr - base) + sizeof(char *);
  if (buflen < needed) {
    *result = NULL;
    errno = ERANGE;
    return ERANGE;
  }

  memcpy(buf, name, name_len);
  char *passwd_ptr = buf + name_len;
  passwd_ptr[0] = 'x';
  passwd_ptr[1] = '\0';
  char **mem_vec = (char **)mem_ptr_addr;
  mem_vec[0] = NULL;

  grp->gr_name = buf;
  grp->gr_passwd = passwd_ptr;
  grp->gr_gid = gid;
  grp->gr_mem = mem_vec;
  *result = grp;
  errno = saved;
  return 0;
}
