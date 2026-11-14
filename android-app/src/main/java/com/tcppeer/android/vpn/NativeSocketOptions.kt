package com.tcppeer.android.vpn

import java.io.FileDescriptor

/** Socket options which java.net / android.system.Os do not expose on Android. */
internal object NativeSocketOptions {
    init {
        System.loadLibrary("tcppeer_socket_options")
    }

    external fun setLingerReset(fileDescriptor: FileDescriptor)
}
