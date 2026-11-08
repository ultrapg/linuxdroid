#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <link.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

/*
 * Wayland Kopper + Zink WSI integration fix for Mesa 26.2 (libEGL_mesa.so.0 + libgallium.so):
 *
 * 1. In Mesa's dri2_wl_create_window_surface(), when dri2_dpy->kopper != 0, Mesa skips
 *    installing wl_egl_window->resize_callback (+0x28). Furthermore, in libgallium's
 *    kopper_allocate_textures(), on Wayland Kopper (where screen->image.loader == NULL),
 *    dri_drawable->w (+0x1d8) and dri_drawable->h (+0x1dc) are only initialized once when
 *    textures[ST_ATTACHMENT_BACK_LEFT] is NULL, and are never updated on wl_egl_window_resize().
 *    As a result, resizing a Wayland EGL window or Plasma popup never updates dri_drawable->w/h,
 *    dri2_surf->base.Width/Height (+0x38/+0x3c), or wl_win->attached_width/height (+0x18/+0x1c),
 *    so Zink never recreates the Vulkan swapchain at the new window dimensions.
 *
 * 2. In Mesa's dri2_wl_kopper_swap_buffers_with_damage(), dri2_wl_kopper_display_vtbl has
 *    swap_interval == NULL while dri2_surf->base.SwapInterval (+0x80) defaults to 1, causing
 *    every swap after the first frame to register a wl_surface.frame throttle_callback and
 *    block inside wl_display_dispatch_queue(), conflicting with QtWayland's own frame callbacks.
 *
 * 3. Note on symbol resolution: libglvnd (libEGL.so.1) loads libEGL_mesa.so.0 (and its DT_NEEDED
 *    dependency libgallium-*.so) with dlopen(..., RTLD_LOCAL). Because RTLD_LOCAL libraries are
 *    not in the global symbol search list, dlsym(RTLD_NEXT, ...) and dlsym(RTLD_DEFAULT, ...)
 *    return NULL for libgallium symbols. We therefore locate the loaded library path via
 *    dl_iterate_phdr() and resolve its symbols via dlopen(path, RTLD_LAZY | RTLD_NOLOAD).
 */

#define VK_STRUCTURE_TYPE_WAYLAND_SURFACE_CREATE_INFO_KHR_VAL 1000006000U
#define WL_EGL_WINDOW_VERSION_VAL 3

typedef void (*dri_invalidate_drawable_fn)(void *drawable);
typedef void (*kopper_set_swap_interval_fn)(void *drawable, int interval);
typedef void *(*dri_create_drawable_fn)(void *screen, const void *config, int is_pixmap, void *loaderPrivate);
typedef int (*dri_bind_context_fn)(void *ctx, void *draw, void *read);
typedef int64_t (*kopper_swap_buffers_fn)(void *drawable, uint32_t flush_flags);
typedef int64_t (*kopper_swap_buffers_damage_fn)(void *drawable, uint32_t flush_flags, int nrects, const int *rects);
typedef void (*wl_egl_window_resize_fn)(void *win, int width, int height, int dx, int dy);

struct bl_lib_match_ctx {
  const char *substr;
  char path[512];
};

static int bl_find_lib_phdr_cb(struct dl_phdr_info *info, size_t size, void *data) {
  (void)size;
  struct bl_lib_match_ctx *ctx = (struct bl_lib_match_ctx *)data;
  if (info->dlpi_name && info->dlpi_name[0] != '\0' && strstr(info->dlpi_name, ctx->substr) != NULL) {
    strncpy(ctx->path, info->dlpi_name, sizeof(ctx->path) - 1);
    ctx->path[sizeof(ctx->path) - 1] = '\0';
    return 1;
  }
  return 0;
}

static void *resolve_lib_symbol(const char *lib_substr, const char *fallback_soname, const char *sym_name, void *self_addr) {
  void *sym = dlsym(RTLD_NEXT, sym_name);
  if (sym && sym != self_addr) {
    return sym;
  }
  if (!self_addr) {
    sym = dlsym(RTLD_DEFAULT, sym_name);
    if (sym) {
      return sym;
    }
  }
  struct bl_lib_match_ctx ctx;
  ctx.substr = lib_substr;
  ctx.path[0] = '\0';
  dl_iterate_phdr(bl_find_lib_phdr_cb, &ctx);
  if (ctx.path[0] != '\0') {
    void *h = dlopen(ctx.path, RTLD_LAZY | RTLD_NOLOAD);
    if (h) {
      sym = dlsym(h, sym_name);
      dlclose(h);
      if (sym && sym != self_addr) {
        return sym;
      }
    }
  }
  if (fallback_soname) {
    void *h = dlopen(fallback_soname, RTLD_LAZY | RTLD_NOLOAD);
    if (!h) {
      h = dlopen(fallback_soname, RTLD_LAZY);
    }
    if (h) {
      sym = dlsym(h, sym_name);
      dlclose(h);
      if (sym && sym != self_addr) {
        return sym;
      }
    }
  }
  return NULL;
}

static void call_dri_invalidate_drawable(void *drawable) {
  static dri_invalidate_drawable_fn fn = NULL;
  if (!fn) {
    fn = (dri_invalidate_drawable_fn)resolve_lib_symbol("libgallium", NULL, "dri_invalidate_drawable", NULL);
  }
  if (fn && drawable) {
    fn(drawable);
  }
}

static int is_valid_wayland_kopper_triplet(void *win, void *dri2_surf, void *dri_drawable) {
  if (!win || !dri2_surf || !dri_drawable) {
    return 0;
  }
  if (*(intptr_t *)win != WL_EGL_WINDOW_VERSION_VAL) {
    return 0;
  }
  if (*(uint32_t *)((char *)dri_drawable + 0x1e8) != VK_STRUCTURE_TYPE_WAYLAND_SURFACE_CREATE_INFO_KHR_VAL) {
    return 0;
  }
  if (*(void **)((char *)dri_drawable + 0x1c8) != dri2_surf) {
    return 0;
  }
  if (*(void **)((char *)dri2_surf + 0xd0) != dri_drawable) {
    return 0;
  }
  if (*(void **)((char *)dri2_surf + 0x158) != win) {
    return 0;
  }
  return 1;
}

static void bl_wl_kopper_resize_cb(void *win, void *data) {
  void *dri2_surf = data;
  if (!win || !dri2_surf) {
    return;
  }
  void *dri_drawable = *(void **)((char *)dri2_surf + 0xd0);
  if (!is_valid_wayland_kopper_triplet(win, dri2_surf, dri_drawable)) {
    return;
  }

  /* Ensure non-blocking swap on Wayland Kopper so QtWayland/GHOST handles frame pacing */
  if (*(int32_t *)((char *)dri2_surf + 0x80) != 0) {
    *(int32_t *)((char *)dri2_surf + 0x80) = 0;
  }

  int32_t w = *(int32_t *)((char *)win + 0x08);
  int32_t h = *(int32_t *)((char *)win + 0x0c);
  if (w <= 0 || h <= 0) {
    return;
  }

  int32_t cur_dw = *(int32_t *)((char *)dri_drawable + 0x1d8);
  int32_t cur_dh = *(int32_t *)((char *)dri_drawable + 0x1dc);
  int32_t cur_sw = *(int32_t *)((char *)dri2_surf + 0x38);
  int32_t cur_sh = *(int32_t *)((char *)dri2_surf + 0x3c);

  if (cur_dw != w || cur_dh != h || cur_sw != w || cur_sh != h) {
    *(int32_t *)((char *)dri2_surf + 0x38) = w;
    *(int32_t *)((char *)dri2_surf + 0x3c) = h;
    *((uint8_t *)dri2_surf + 0x2b8) = 1;
    *(int32_t *)((char *)dri_drawable + 0x1d8) = w;
    *(int32_t *)((char *)dri_drawable + 0x1dc) = h;
    if (cur_dw > 0 && cur_dh > 0 && (cur_dw != w || cur_dh != h)) {
      call_dri_invalidate_drawable(dri_drawable);
    }
  }
}

static void sync_kopper_drawable(void *dri_drawable, int is_post_swap) {
  if (!dri_drawable) {
    return;
  }
  if (*(uint32_t *)((char *)dri_drawable + 0x1e8) != VK_STRUCTURE_TYPE_WAYLAND_SURFACE_CREATE_INFO_KHR_VAL) {
    return;
  }
  void *dri2_surf = *(void **)((char *)dri_drawable + 0x1c8);
  if (!dri2_surf) {
    return;
  }
  void *win = *(void **)((char *)dri2_surf + 0x158);
  if (!is_valid_wayland_kopper_triplet(win, dri2_surf, dri_drawable)) {
    return;
  }

  if (*(void **)((char *)win + 0x28) == NULL) {
    *(void **)((char *)win + 0x28) = (void *)bl_wl_kopper_resize_cb;
  }

  if (*(int32_t *)((char *)dri2_surf + 0x80) != 0) {
    *(int32_t *)((char *)dri2_surf + 0x80) = 0;
  }

  bl_wl_kopper_resize_cb(win, dri2_surf);

  if (is_post_swap) {
    int32_t w = *(int32_t *)((char *)dri_drawable + 0x1d8);
    int32_t h = *(int32_t *)((char *)dri_drawable + 0x1dc);
    if (w > 0 && h > 0) {
      *(int32_t *)((char *)win + 0x18) = w;
      *(int32_t *)((char *)win + 0x1c) = h;
      *(int32_t *)((char *)dri2_surf + 0x38) = w;
      *(int32_t *)((char *)dri2_surf + 0x3c) = h;
    }
  }
}

void *dri_create_drawable(void *screen, const void *config, int is_pixmap, void *loaderPrivate) {
  static dri_create_drawable_fn real_fn = NULL;
  if (!real_fn) {
    real_fn = (dri_create_drawable_fn)resolve_lib_symbol("libgallium", NULL, "dri_create_drawable", (void *)dri_create_drawable);
  }
  if (!real_fn) {
    return NULL;
  }
  void *dri_drawable = real_fn(screen, config, is_pixmap, loaderPrivate);
  if (dri_drawable &&
      *(uint32_t *)((char *)dri_drawable + 0x1e8) == VK_STRUCTURE_TYPE_WAYLAND_SURFACE_CREATE_INFO_KHR_VAL) {
    void *dri2_surf = *(void **)((char *)dri_drawable + 0x1c8);
    if (dri2_surf) {
      void *win = *(void **)((char *)dri2_surf + 0x158);
      if (win && *(intptr_t *)win == WL_EGL_WINDOW_VERSION_VAL) {
        int32_t w = *(int32_t *)((char *)win + 0x08);
        int32_t h = *(int32_t *)((char *)win + 0x0c);
        if (w > 0 && h > 0) {
          *(int32_t *)((char *)dri_drawable + 0x1d8) = w;
          *(int32_t *)((char *)dri_drawable + 0x1dc) = h;
          *(int32_t *)((char *)dri2_surf + 0x38) = w;
          *(int32_t *)((char *)dri2_surf + 0x3c) = h;
        }
      }
    }
  }
  return dri_drawable;
}

int driBindContext(void *ctx, void *draw, void *read) {
  static dri_bind_context_fn real_fn = NULL;
  if (!real_fn) {
    real_fn = (dri_bind_context_fn)resolve_lib_symbol("libgallium", NULL, "driBindContext", (void *)driBindContext);
  }
  if (!real_fn) {
    return 0;
  }
  sync_kopper_drawable(draw, 0);
  if (read && read != draw) {
    sync_kopper_drawable(read, 0);
  }
  int ret = real_fn(ctx, draw, read);
  sync_kopper_drawable(draw, 0);
  return ret;
}

int64_t kopperSwapBuffers(void *drawable, uint32_t flush_flags) {
  static kopper_swap_buffers_fn real_fn = NULL;
  if (!real_fn) {
    real_fn = (kopper_swap_buffers_fn)resolve_lib_symbol("libgallium", NULL, "kopperSwapBuffers", (void *)kopperSwapBuffers);
  }
  if (!real_fn) {
    return -1;
  }
  sync_kopper_drawable(drawable, 0);
  int64_t ret = real_fn(drawable, flush_flags);
  sync_kopper_drawable(drawable, 1);
  return ret;
}

int64_t kopperSwapBuffersWithDamage(void *drawable, uint32_t flush_flags, int nrects, const int *rects) {
  static kopper_swap_buffers_damage_fn real_fn = NULL;
  if (!real_fn) {
    real_fn = (kopper_swap_buffers_damage_fn)resolve_lib_symbol("libgallium", NULL, "kopperSwapBuffersWithDamage", (void *)kopperSwapBuffersWithDamage);
  }
  if (!real_fn) {
    return -1;
  }
  sync_kopper_drawable(drawable, 0);
  int64_t ret = real_fn(drawable, flush_flags, nrects, rects);
  sync_kopper_drawable(drawable, 1);
  return ret;
}

void wl_egl_window_resize(void *win, int width, int height, int dx, int dy) {
  static wl_egl_window_resize_fn real_fn = NULL;
  if (!real_fn) {
    real_fn = (wl_egl_window_resize_fn)resolve_lib_symbol("libwayland-egl.so", "libwayland-egl.so.1", "wl_egl_window_resize", (void *)wl_egl_window_resize);
  }
  if (!real_fn) {
    return;
  }
  if (win && *(intptr_t *)win == WL_EGL_WINDOW_VERSION_VAL) {
    void *dri2_surf = *(void **)((char *)win + 0x20);
    if (dri2_surf && *(void **)((char *)win + 0x28) == NULL) {
      void *dri_drawable = *(void **)((char *)dri2_surf + 0xd0);
      if (is_valid_wayland_kopper_triplet(win, dri2_surf, dri_drawable)) {
        *(void **)((char *)win + 0x28) = (void *)bl_wl_kopper_resize_cb;
      }
    }
  }
  real_fn(win, width, height, dx, dy);
  if (win && *(intptr_t *)win == WL_EGL_WINDOW_VERSION_VAL) {
    void *dri2_surf = *(void **)((char *)win + 0x20);
    if (dri2_surf) {
      bl_wl_kopper_resize_cb(win, dri2_surf);
    }
  }
}
