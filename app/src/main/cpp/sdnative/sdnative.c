#include <errno.h>
#include <jni.h>
#include <sys/syscall.h>
#include <unistd.h>

JNIEXPORT jint JNICALL
Java_com_linuxdroid_app_session_ThreadSignals_tgkill(JNIEnv *env, jclass clazz, jint tgid, jint tid, jint signal) {
    (void)env;
    (void)clazz;
    return syscall(SYS_tgkill, tgid, tid, signal) == 0 ? 0 : errno;
}
