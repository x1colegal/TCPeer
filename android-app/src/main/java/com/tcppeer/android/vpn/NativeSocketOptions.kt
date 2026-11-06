package com.tcppeer.android.vpn

import java.net.Socket

/** Socket options which java.net does not expose on Android. */
internal object NativeSocketOptions {
    init {
        System.loadLibrary("tcppeer_socket_options")
    }

    fun enableIpv6Only(socket: Socket) = setIpv6Only(socket)

    fun enableMappingKeepAlive(socket: Socket) = setMappingKeepAlive(socket)

    fun useIpv4(socket: Any) = useIpv4Socket(socket)

    fun bindIpv4(socket: Any, port: Int, listener: Boolean) = bindIpv4Socket(socket, port, listener)

    external fun beginServerSocketCreation()

    external fun enableIpv6OnlyOnCreatedServerSocket()

    external fun useIpv4OnCreatedServerSocket()

    private external fun setIpv6Only(socket: Any)
    private external fun setMappingKeepAlive(socket: Any)
    private external fun useIpv4Socket(socket: Any)
    private external fun bindIpv4Socket(socket: Any, port: Int, listener: Boolean)
}
