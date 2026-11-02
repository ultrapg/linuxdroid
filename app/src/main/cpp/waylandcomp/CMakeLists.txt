add_library(waylandcomp SHARED
        src/compositor.c
        src/vk_present.c
        src/effects_chain.c
        src/vk_loader.c
        src/sc_layer.c
        src/ahb_swapchain.c
        src/waylandcomp_jni.c
        src/gpu_clock_pin.c
        src/droiddeck_ext.c
        src/wl_dmabuf.c
        src/wl_clipboard.c
        src/wl_text_input.c
        src/wl_toplevel_icon.c
        src/wl_single_pixel.c
        src/wl_color_mgmt.c
        src/hdr_compose.c
        src/blend_pass.c
        generated/color-management-v1-protocol.c
        generated/xdg-shell-protocol.c
        generated/linux-dmabuf-v1-protocol.c
        generated/viewporter-protocol.c
        generated/banner-desktop-v1-protocol.c
        generated/presentation-time-protocol.c
        generated/pointer-constraints-unstable-v1-protocol.c
        generated/relative-pointer-unstable-v1-protocol.c
        generated/wlr-data-control-unstable-v1-protocol.c
        generated/text-input-unstable-v3-protocol.c
        generated/xdg-toplevel-icon-v1-protocol.c
        generated/single-pixel-buffer-v1-protocol.c
        generated/banner-ahb-v1-protocol.c
)
target_compile_options(waylandcomp PRIVATE
        $<$<COMPILE_LANGUAGE:C>:-Wall -Wextra -Wno-unused-parameter -Wno-missing-field-initializers>)
target_include_directories(waylandcomp PRIVATE
        generated
        prebuilt/include
        src
        ${CMAKE_CURRENT_SOURCE_DIR}/../shaders
)

add_library(wayland-server SHARED IMPORTED)
set_target_properties(wayland-server PROPERTIES IMPORTED_LOCATION
        ${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/lib/libwayland-server.so)
target_link_libraries(waylandcomp
        wayland-server
        adrenotools
        dl
        log
        android
        nativewindow
)
