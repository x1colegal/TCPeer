#include <jni.h>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <dirent.h>
#include <cstring>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <sys/socket.h>
#include <unistd.h>
#include <unordered_set>

namespace {

thread_local std::unordered_set<int> server_socket_snapshot;

void throw_socket_exception(JNIEnv* env, const char* message) {
    jclass exception = env->FindClass("java/net/SocketException");
    if (exception != nullptr) env->ThrowNew(exception, message);
}

jobject socket_impl(JNIEnv* env, jobject socket) {
    jclass socket_class = env->GetObjectClass(socket);
    jfieldID impl_field = env->GetFieldID(socket_class, "impl", "Ljava/net/SocketImpl;");
    if (impl_field == nullptr) return nullptr;
    return env->GetObjectField(socket, impl_field);
}

int socket_fd(JNIEnv* env, jobject socket) {
    jobject impl = socket_impl(env, socket);
    if (impl == nullptr) return -1;

    jclass impl_class = env->FindClass("java/net/SocketImpl");
    jfieldID fd_field = env->GetFieldID(impl_class, "fd", "Ljava/io/FileDescriptor;");
    jobject descriptor = env->GetObjectField(impl, fd_field);
    if (descriptor == nullptr) return -1;

    jclass descriptor_class = env->FindClass("java/io/FileDescriptor");
    jfieldID value_field = env->GetFieldID(descriptor_class, "descriptor", "I");
    return env->GetIntField(descriptor, value_field);
}

std::unordered_set<int> open_file_descriptors() {
    std::unordered_set<int> descriptors;
    DIR* directory = opendir("/proc/self/fd");
    if (directory == nullptr) return descriptors;
    const int directory_fd = dirfd(directory);
    while (dirent* entry = readdir(directory)) {
        char* end = nullptr;
        const long value = strtol(entry->d_name, &end, 10);
        if (end != entry->d_name && *end == '\0' && value >= 0 && value != directory_fd) {
            descriptors.insert(static_cast<int>(value));
        }
    }
    closedir(directory);
    return descriptors;
}

bool is_unbound_ipv6_stream_socket(int fd) {
    int type = 0;
    socklen_t type_length = sizeof(type);
    if (getsockopt(fd, SOL_SOCKET, SO_TYPE, &type, &type_length) != 0 || type != SOCK_STREAM) {
        return false;
    }
    sockaddr_storage address{};
    socklen_t address_length = sizeof(address);
    if (getsockname(fd, reinterpret_cast<sockaddr*>(&address), &address_length) != 0 ||
            address.ss_family != AF_INET6) {
        return false;
    }
    const auto* ipv6 = reinterpret_cast<const sockaddr_in6*>(&address);
    return ipv6->sin6_port == 0;
}

void enable_ipv6_only(JNIEnv* env, int fd) {
    const int enabled = 1;
    if (setsockopt(fd, IPPROTO_IPV6, IPV6_V6ONLY, &enabled, sizeof(enabled)) != 0) {
        char message[160];
        snprintf(message, sizeof(message), "Cannot enable IPV6_V6ONLY: %s", strerror(errno));
        throw_socket_exception(env, message);
    }
}

bool replace_with_ipv4(JNIEnv* env, int candidate) {
    const int ipv4_fd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (ipv4_fd < 0) {
        char message[160];
        snprintf(message, sizeof(message), "Cannot create AF_INET socket: %s", strerror(errno));
        throw_socket_exception(env, message);
        return false;
    }
    const int enabled = 1;
    if (setsockopt(ipv4_fd, SOL_SOCKET, SO_REUSEADDR, &enabled, sizeof(enabled)) != 0) {
        const int saved_errno = errno;
        close(ipv4_fd);
        char message[160];
        snprintf(message, sizeof(message), "Cannot configure AF_INET socket: %s", strerror(saved_errno));
        throw_socket_exception(env, message);
        return false;
    }
    if (setsockopt(ipv4_fd, SOL_SOCKET, SO_REUSEPORT, &enabled, sizeof(enabled)) != 0 &&
            errno != ENOPROTOOPT && errno != EOPNOTSUPP) {
        const int saved_errno = errno;
        close(ipv4_fd);
        char message[160];
        snprintf(message, sizeof(message), "Cannot configure AF_INET port reuse: %s", strerror(saved_errno));
        throw_socket_exception(env, message);
        return false;
    }
    if (dup2(ipv4_fd, candidate) < 0) {
        const int saved_errno = errno;
        close(ipv4_fd);
        char message[160];
        snprintf(message, sizeof(message), "Cannot install AF_INET socket: %s", strerror(saved_errno));
        throw_socket_exception(env, message);
        return false;
    }
    close(ipv4_fd);
    return true;
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_tcppeer_android_vpn_NativeSocketOptions_setIpv6Only(
        JNIEnv* env, jobject, jobject socket) {
    const int fd = socket_fd(env, socket);
    if (env->ExceptionCheck()) return;
    if (fd < 0) {
        throw_socket_exception(env, "IPv6 socket has no file descriptor");
        return;
    }

    enable_ipv6_only(env, fd);
}

extern "C" JNIEXPORT void JNICALL
Java_com_tcppeer_android_vpn_NativeSocketOptions_setMappingKeepAlive(
        JNIEnv* env, jobject, jobject socket) {
    const int fd = socket_fd(env, socket);
    if (env->ExceptionCheck()) return;
    if (fd < 0) {
        throw_socket_exception(env, "TCP socket has no file descriptor");
        return;
    }
    const int enabled = 1;
    const int idle = 15;
    const int interval = 5;
    const int count = 3;
    if (setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &enabled, sizeof(enabled)) != 0 ||
            setsockopt(fd, IPPROTO_TCP, TCP_KEEPIDLE, &idle, sizeof(idle)) != 0 ||
            setsockopt(fd, IPPROTO_TCP, TCP_KEEPINTVL, &interval, sizeof(interval)) != 0 ||
            setsockopt(fd, IPPROTO_TCP, TCP_KEEPCNT, &count, sizeof(count)) != 0) {
        char message[160];
        snprintf(message, sizeof(message), "Cannot configure endpoint keepalive: %s", strerror(errno));
        throw_socket_exception(env, message);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_tcppeer_android_vpn_NativeSocketOptions_useIpv4Socket(
        JNIEnv* env, jobject, jobject socket) {
    const int fd = socket_fd(env, socket);
    if (env->ExceptionCheck()) return;
    if (fd < 0) {
        throw_socket_exception(env, "TCP socket has no file descriptor");
        return;
    }
    replace_with_ipv4(env, fd);
}

extern "C" JNIEXPORT void JNICALL
Java_com_tcppeer_android_vpn_NativeSocketOptions_bindIpv4Socket(
        JNIEnv* env, jobject, jobject socket, jint port, jboolean listener) {
    const int fd = socket_fd(env, socket);
    if (env->ExceptionCheck()) return;
    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_ANY);
    address.sin_port = htons(static_cast<uint16_t>(port));
    if (bind(fd, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0 ||
            (listener == JNI_TRUE && listen(fd, 32) != 0)) {
        char message[160];
        snprintf(message, sizeof(message), "Cannot bind AF_INET socket: %s", strerror(errno));
        throw_socket_exception(env, message);
        return;
    }

}

extern "C" JNIEXPORT void JNICALL
Java_com_tcppeer_android_vpn_NativeSocketOptions_beginServerSocketCreation(
        JNIEnv*, jobject) {
    server_socket_snapshot = open_file_descriptors();
}

extern "C" JNIEXPORT void JNICALL
Java_com_tcppeer_android_vpn_NativeSocketOptions_enableIpv6OnlyOnCreatedServerSocket(
        JNIEnv* env, jobject) {
    const auto current = open_file_descriptors();
    int candidate = -1;
    for (const int fd : current) {
        if (server_socket_snapshot.count(fd) != 0 || !is_unbound_ipv6_stream_socket(fd)) continue;
        if (candidate != -1) {
            server_socket_snapshot.clear();
            throw_socket_exception(env, "Multiple new IPv6 server sockets were created");
            return;
        }
        candidate = fd;
    }
    server_socket_snapshot.clear();
    if (candidate == -1) {
        throw_socket_exception(env, "Cannot locate the new IPv6 server socket");
        return;
    }
    enable_ipv6_only(env, candidate);
}

extern "C" JNIEXPORT void JNICALL
Java_com_tcppeer_android_vpn_NativeSocketOptions_useIpv4OnCreatedServerSocket(
        JNIEnv* env, jobject) {
    const auto current = open_file_descriptors();
    int candidate = -1;
    for (const int fd : current) {
        if (server_socket_snapshot.count(fd) != 0 || !is_unbound_ipv6_stream_socket(fd)) continue;
        if (candidate != -1) {
            server_socket_snapshot.clear();
            throw_socket_exception(env, "Multiple new server sockets were created");
            return;
        }
        candidate = fd;
    }
    server_socket_snapshot.clear();
    if (candidate == -1) {
        throw_socket_exception(env, "Cannot locate the new server socket");
        return;
    }

    replace_with_ipv4(env, candidate);
}

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
