#include <jni.h>
#include <fcntl.h>
#include <android/log.h>

#define LOG_TAG "FdHelper"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

JNIEXPORT void JNICALL
Java_com_simplexray_an_service_TProxyService_clearFdCloexec(JNIEnv *env, jobject thiz, jint fd) {
    int flags = fcntl(fd, F_GETFD);
    if (flags >= 0) {
        fcntl(fd, F_SETFD, flags & ~FD_CLOEXEC);
        LOGD("FD %d: cleared FD_CLOEXEC", fd);
    }
}
