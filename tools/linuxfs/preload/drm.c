#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#define MAX_HANDLES 4096

struct gem_close {
  uint32_t handle;
  uint32_t pad;
};
#define GEM_CLOSE _IOW('d', 0x09, struct gem_close)

struct bl_drm_version {
  int version_major;
  int version_minor;
  int version_patchlevel;
  size_t name_len;
  char *name;
  size_t date_len;
  char *date;
  size_t desc_len;
  char *desc;
};
#define BL_DRM_IOCTL_VERSION _IOWR('d', 0x00, struct bl_drm_version)

struct bl_drm_get_cap {
  uint64_t capability;
  uint64_t value;
};
#define BL_DRM_IOCTL_GET_CAP _IOWR('d', 0x0c, struct bl_drm_get_cap)

struct handle_entry {
  uint32_t handle;
  int dma_fd;
  int drm_fd;
};

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static struct handle_entry handle_entries[MAX_HANDLES];
static uint32_t next_handle = 1;
static dev_t kgsl_dev;
static dev_t mali_dev;
static int synth_drm_state;

static void init_synth_drm_devs_locked(void) {
  if (synth_drm_state != 0) {
    return;
  }
  struct stat st;
  if (stat("/dev/kgsl-3d0", &st) == 0 && S_ISCHR(st.st_mode)) {
    kgsl_dev = st.st_rdev;
  }
  if (stat("/dev/mali0", &st) == 0 && S_ISCHR(st.st_mode)) {
    mali_dev = st.st_rdev;
  }
  synth_drm_state = (kgsl_dev != 0 || mali_dev != 0) ? 1 : -1;
}

static int synth_drm_kind_dev(dev_t rdev) {
  if (rdev == 0) {
    return 0;
  }
  pthread_mutex_lock(&lock);
  init_synth_drm_devs_locked();
  pthread_mutex_unlock(&lock);
  if (kgsl_dev != 0 && rdev == kgsl_dev) {
    return 1;
  }
  if (mali_dev != 0 && rdev == mali_dev) {
    return 2;
  }
  return 0;
}

static int synth_drm_kind_fd(int fd) {
  struct stat st;
  if (fd < 0 || fstat(fd, &st) != 0 || !S_ISCHR(st.st_mode)) {
    return 0;
  }
  return synth_drm_kind_dev(st.st_rdev);
}

static int is_kgsl(int fd) {
  return synth_drm_kind_fd(fd) != 0;
}

static void *real(const char *name) {
  void *fn = dlsym(RTLD_NEXT, name);
  if (!fn) {
    errno = ENOSYS;
  }
  return fn;
}

static int same_file_description(int a, int b) {
  if (a == b) {
    return 1;
  }
#ifdef SYS_kcmp
  int saved_errno = errno;
  long ret = syscall(SYS_kcmp, getpid(), getpid(), 0  , a, b);
  errno = saved_errno;
  return ret == 0;
#else
  return 0;
#endif
}

static int find_handle_locked(int fd, uint32_t handle) {
  for (int i = 0; i < MAX_HANDLES; i++) {
    if (handle_entries[i].handle == handle &&
        same_file_description(handle_entries[i].drm_fd, fd)) {
      return i;
    }
  }
  return -1;
}

static uint32_t alloc_handle_locked(void) {
  for (int tries = 0; tries <= MAX_HANDLES; tries++) {
    uint32_t candidate = next_handle++;
    if (next_handle == 0) {
      next_handle = 1;
    }
    if (candidate == 0) {
      continue;
    }
    int used = 0;
    for (int i = 0; i < MAX_HANDLES; i++) {
      if (handle_entries[i].handle == candidate) {
        used = 1;
        break;
      }
    }
    if (!used) {
      return candidate;
    }
  }
  return 0;
}

int drmPrimeFDToHandle(int fd, int prime_fd, uint32_t *handle) {
  if (!is_kgsl(fd)) {
    int (*fn)(int, int, uint32_t *) = (int (*)(int, int, uint32_t *)) real("drmPrimeFDToHandle");
    return fn ? fn(fd, prime_fd, handle) : -ENOSYS;
  }
  int dup_fd = fcntl(prime_fd, F_DUPFD_CLOEXEC, 0);
  if (dup_fd < 0) {
    return -errno;
  }
  int ret = -ENOMEM;
  pthread_mutex_lock(&lock);
  for (int i = 0; i < MAX_HANDLES; i++) {
    if (handle_entries[i].handle == 0) {
      uint32_t token = alloc_handle_locked();
      if (!token) {
        break;
      }
      handle_entries[i].handle = token;
      handle_entries[i].dma_fd = dup_fd;
      handle_entries[i].drm_fd = fd;
      *handle = token;
      ret = 0;
      break;
    }
  }
  pthread_mutex_unlock(&lock);
  if (ret) {
    close(dup_fd);
  }
  return ret;
}

int drmPrimeHandleToFD(int fd, uint32_t handle, uint32_t flags, int *prime_fd) {
  if (!is_kgsl(fd)) {
    int (*fn)(int, uint32_t, uint32_t, int *) = (int (*)(int, uint32_t, uint32_t, int *)) real("drmPrimeHandleToFD");
    return fn ? fn(fd, handle, flags, prime_fd) : -ENOSYS;
  }
  int ret = -EINVAL;
  pthread_mutex_lock(&lock);
  int index = find_handle_locked(fd, handle);
  if (index >= 0) {
    *prime_fd = fcntl(handle_entries[index].dma_fd, F_DUPFD_CLOEXEC, 0);
    ret = *prime_fd < 0 ? -errno : 0;
  }
  pthread_mutex_unlock(&lock);
  return ret;
}

static int release_handle(int fd, uint32_t handle) {
  int found = 0;
  pthread_mutex_lock(&lock);
  int index = find_handle_locked(fd, handle);
  if (index >= 0) {
    close(handle_entries[index].dma_fd);
    handle_entries[index] = (struct handle_entry){0};
    found = 1;
  }
  pthread_mutex_unlock(&lock);
  return found;
}

int drmCloseBufferHandle(int fd, uint32_t handle) {
  if (!is_kgsl(fd)) {
    int (*fn)(int, uint32_t) = (int (*)(int, uint32_t)) real("drmCloseBufferHandle");
    return fn ? fn(fd, handle) : -ENOSYS;
  }
  return release_handle(fd, handle) ? 0 : -EINVAL;
}

int drmGetDeviceFromDevId(dev_t find_rdev, uint32_t flags, void *device) {
  int (*fn)(dev_t, uint32_t, void *) =
      (int (*)(dev_t, uint32_t, void *)) real("drmGetDeviceFromDevId");
  if (!fn) {
    return -ENOSYS;
  }
  if (find_rdev == 0) {
    static const char *fallback_nodes[] = {
      "/dev/dri/renderD128",
      "/dev/dri/renderD129",
      "/dev/kgsl-3d0",
      "/dev/mali0",
      "/dev/dri/card0",
    };
    struct stat st;
    for (size_t i = 0; i < sizeof(fallback_nodes) / sizeof(fallback_nodes[0]); i++) {
      if (stat(fallback_nodes[i], &st) == 0 && S_ISCHR(st.st_mode) && st.st_rdev != 0) {
        find_rdev = st.st_rdev;
        break;
      }
    }
  }
  return fn(find_rdev, flags, device);
}

int drmGetDevice2(int fd, uint32_t flags, void *device) {
  struct stat st;
  if (fd >= 0 && fstat(fd, &st) == 0 && S_ISCHR(st.st_mode) && synth_drm_kind_dev(st.st_rdev) != 0) {
    return drmGetDeviceFromDevId(st.st_rdev, flags, device);
  }
  int (*fn)(int, uint32_t, void *) = (int (*)(int, uint32_t, void *)) real("drmGetDevice2");
  return fn ? fn(fd, flags, device) : -ENOSYS;
}

char *drmGetRenderDeviceNameFromFd(int fd) {
  if (synth_drm_kind_fd(fd) != 0) {
    return strdup("/dev/dri/renderD128");
  }
  char *(*fn)(int) = (char *(*)(int)) real("drmGetRenderDeviceNameFromFd");
  return fn ? fn(fd) : NULL;
}

char *drmGetDeviceNameFromFd2(int fd) {
  if (synth_drm_kind_fd(fd) != 0) {
    return strdup("/dev/dri/renderD128");
  }
  char *(*fn)(int) = (char *(*)(int)) real("drmGetDeviceNameFromFd2");
  return fn ? fn(fd) : NULL;
}

static void copy_drm_field(char *dst, size_t *dst_len, const char *src) {
  size_t len = strlen(src);
  if (dst && *dst_len > 0) {
    size_t n = len < *dst_len ? len : *dst_len;
    memcpy(dst, src, n);
  }
  *dst_len = len;
}

int drmGetNodeTypeFromFd(int fd) {
  if (synth_drm_kind_fd(fd) != 0) {
    char proc_path[64];
    char link_buf[256];
    snprintf(proc_path, sizeof(proc_path), "/proc/self/fd/%d", fd);
    ssize_t n = readlink(proc_path, link_buf, sizeof(link_buf) - 1);
    if (n > 0) {
      link_buf[n] = '\0';
      if (strstr(link_buf, "/card0") != NULL) {
        return 0; /* DRM_NODE_PRIMARY */
      }
    }
    return 2; /* DRM_NODE_RENDER */
  }
  int (*fn)(int) = (int (*)(int)) real("drmGetNodeTypeFromFd");
  return fn ? fn(fd) : -1;
}

int drmGetNodeTypeFromDevId(dev_t devid) {
  if (synth_drm_kind_dev(devid) != 0) {
    return 2; /* DRM_NODE_RENDER */
  }
  int (*fn)(dev_t) = (int (*)(dev_t)) real("drmGetNodeTypeFromDevId");
  return fn ? fn(devid) : -1;
}

__attribute__((visibility("hidden"))) int bl_drm_gem_close(int fd, unsigned long request, void *arg, int *rc) {
  int kind = 0;
  if (request != GEM_CLOSE && request != BL_DRM_IOCTL_VERSION && request != BL_DRM_IOCTL_GET_CAP) {
    return 0;
  }
  if (!arg || (kind = synth_drm_kind_fd(fd)) == 0) {
    return 0;
  }
  if (request == GEM_CLOSE) {
    if (release_handle(fd, ((struct gem_close *) arg)->handle)) {
      *rc = 0;
    } else {
      errno = EINVAL;
      *rc = -1;
    }
    return 1;
  }
  if (request == BL_DRM_IOCTL_VERSION) {
    struct bl_drm_version *v = (struct bl_drm_version *) arg;
    v->version_major = 1;
    v->version_minor = 6;
    v->version_patchlevel = 0;
    copy_drm_field(v->name, &v->name_len, kind == 2 ? "panfrost" : "msm");
    copy_drm_field(v->date, &v->date_len, "20240101");
    copy_drm_field(v->desc, &v->desc_len, kind == 2 ? "ARM Mali DRM" : "Qualcomm KGSL DRM");
    *rc = 0;
    return 1;
  }
  if (request == BL_DRM_IOCTL_GET_CAP) {
    struct bl_drm_get_cap *cap = (struct bl_drm_get_cap *) arg;
    cap->value = (cap->capability == 0x5 /* DRM_CAP_PRIME */) ? 0x3 : 1;
    *rc = 0;
    return 1;
  }
  return 0;
}

int drmIoctl(int fd, unsigned long request, void *arg) {
  int rc = 0;
  if (bl_drm_gem_close(fd, request, arg, &rc)) {
    return rc;
  }
  int (*fn)(int, unsigned long, void *) =
      (int (*)(int, unsigned long, void *)) real("drmIoctl");
  return fn ? fn(fd, request, arg) : -ENOSYS;
}

void *gbm_create_device(int fd) {
  if (is_kgsl(fd)) {
    errno = ENODEV;
    return NULL;
  }
  void *(*fn)(int) = (void *(*)(int)) real("gbm_create_device");
  return fn ? fn(fd) : NULL;
}

static void drm_lock_before_fork(void) { pthread_mutex_lock(&lock); }
static void drm_unlock_after_fork(void) { pthread_mutex_unlock(&lock); }
static void drm_reset_after_fork(void) { pthread_mutex_init(&lock, NULL); }

__attribute__((constructor)) static void install_drm_fork_handlers(void) {
  pthread_atfork(drm_lock_before_fork, drm_unlock_after_fork, drm_reset_after_fork);
}
