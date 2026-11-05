#ifndef LINUXDROID_COMPAT_GLIBC_H
#define LINUXDROID_COMPAT_GLIBC_H

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include <features.h>

/*
 * Prevent glibc >= 2.38 headers from redirecting strtol/strtoul/strtoll/strtoull
 * and *scanf to __isoc23_*@GLIBC_2.38 symbols so libblsession.so and
 * libblfastpath.so preload cleanly inside older Flatpak runtimes without
 * dynamic-linker symbol-version errors on stderr.
 */
#undef __GLIBC_USE_ISOC23
#define __GLIBC_USE_ISOC23 0
#undef __GLIBC_USE_C23_STRTOL
#define __GLIBC_USE_C23_STRTOL 0

#endif /* LINUXDROID_COMPAT_GLIBC_H */
