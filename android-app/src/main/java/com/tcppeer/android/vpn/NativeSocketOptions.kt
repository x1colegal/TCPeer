package com.tcppeer.android.vpn

import java.net.Socket

/** Socket options which java.net does not expose on Android. */
internal object NativeSocketOptions {
    init {
        System.loadLibrary("tcppeer_socket_options")
    }

    fun enableIpv6Only(socket: Socket) = setIpv6Only(socket)

    external fun beginServerSocketCreation()

    external fun enableIpv6OnlyOnCreatedServerSocket()

    private external fun setIpv6Only(socket: Any)
}
