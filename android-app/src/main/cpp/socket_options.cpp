#include <jni.h>
#include <sys/socket.h>

extern "C" JNIEXPORT void JNICALL
Java_com_tcppeer_android_vpn_NativeSocketOptions_setLingerReset(
        JNIEnv* env, jobject, jobject fileDescriptor) {
    if (fileDescriptor == nullptr) return;
    jclass fd_class = env->FindClass("java/io/FileDescriptor");
    if (fd_class == nullptr) return;
    jfieldID value_field = env->GetFieldID(fd_class, "descriptor", "I");
    if (value_field == nullptr) return;
    const int fd = env->GetIntField(fileDescriptor, value_field);
    if (fd < 0) return;
    struct linger sl{};
    sl.l_onoff = 1;
    sl.l_linger = 0;
    setsockopt(fd, SOL_SOCKET, SO_LINGER, &sl, sizeof(sl));
}
