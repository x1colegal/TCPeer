package com.tcppeer.android.vpn

import android.net.Network
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException

/** A real AF_INET/AF_INET6 socket backed only by Android's API 21+ public android.system.Os API. */
internal class OsTcpSocket internal constructor(
    private val family: Int,
    internal val fileDescriptor: FileDescriptor,
    configureIpv6Only: Boolean = true,
    accepted: Boolean = false,
) : Socket() {
    constructor(family: Int) : this(
        family,
        Os.socket(family, OsConstants.SOCK_STREAM or OsConstants.O_NONBLOCK, OsConstants.IPPROTO_TCP),
    )

    @Volatile private var closed = false
    @Volatile private var bound = accepted
    @Volatile private var connected = accepted
    @Volatile private var timeoutMillis = 0
    @Volatile private var cachedLocalAddress: SocketAddress? =
        if (accepted) runCatching { Os.getsockname(fileDescriptor) }.getOrNull() else null
    @Volatile private var cachedRemoteAddress: SocketAddress? =
        if (accepted) runCatching { Os.getpeername(fileDescriptor) }.getOrNull() else null
    private var reuseAddressEnabled = false
    private var tcpNoDelayEnabled = false
    private var sendBufferBytes = 0
    private var receiveBufferBytes = 0

    private fun pollReady(events: Int, timeoutMs: Int, timeoutMessage: String) {
        val deadline = if (timeoutMs > 0) SystemClock.elapsedRealtime() + timeoutMs else Long.MAX_VALUE
        val poll = StructPollfd().apply {
            fd = fileDescriptor
            this.events = events.toShort()
        }
        while (true) {
            if (closed) throw SocketException("Socket closed")
            val stepMs = if (timeoutMs > 0) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) throw SocketTimeoutException(timeoutMessage)
                minOf(remaining, POLL_STEP_MS.toLong()).toInt()
            } else {
                POLL_STEP_MS
            }
            poll.revents = 0
            val ready = try {
                Os.poll(arrayOf(poll), stepMs)
            } catch (error: ErrnoException) {
                if (closed) throw SocketException("Socket closed")
                if (error.errno == OsConstants.EINTR) continue
                throw SocketException(error.message).also { it.initCause(error) }
            }
            if (closed) throw SocketException("Socket closed")
            if (ready > 0) {
                if ((poll.revents.toInt() and OsConstants.POLLNVAL) != 0) {
                    throw SocketException("Socket closed")
                }
                return
            }
        }
    }

    private val inputStream = object : InputStream() {
        override fun read(): Int {
            val single = ByteArray(1)
            val count = read(single, 0, 1)
            return if (count == -1) -1 else (single[0].toInt() and 0xff)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (off < 0 || len < 0 || len > b.size - off) {
                throw IndexOutOfBoundsException()
            }
            if (len == 0) return 0
            while (true) {
                pollReady(OsConstants.POLLIN, timeoutMillis, "Read timed out")
                try {
                    val bytesRead = Os.read(fileDescriptor, b, off, len)
                    return if (bytesRead == 0) -1 else bytesRead
                } catch (error: ErrnoException) {
                    if (closed || error.errno == OsConstants.EBADF) {
                        throw SocketException("Socket closed")
                    }
                    if (error.errno == OsConstants.EAGAIN || error.errno == OsConstants.EINTR) {
                        continue
                    }
                    throw IOException(error.message, error)
                }
            }
        }

        override fun close() {
            this@OsTcpSocket.close()
        }
    }

    private val outputStream = object : OutputStream() {
        override fun write(oneByte: Int) {
            write(byteArrayOf(oneByte.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (off < 0 || len < 0 || len > b.size - off) {
                throw IndexOutOfBoundsException()
            }
            var offset = off
            var remaining = len
            while (remaining > 0) {
                if (closed) throw SocketException("Socket closed")
                try {
                    val written = Os.write(fileDescriptor, b, offset, remaining)
                    if (written <= 0) {
                        throw IOException("Socket write returned $written")
                    }
                    offset += written
                    remaining -= written
                } catch (error: ErrnoException) {
                    if (closed || error.errno == OsConstants.EBADF) {
                        throw SocketException("Socket closed")
                    }
                    if (error.errno == OsConstants.EINTR) continue
                    if (error.errno != OsConstants.EAGAIN) {
                        throw IOException(error.message, error)
                    }
                    pollReady(OsConstants.POLLOUT, 0, "Write timed out")
                }
            }
        }

        override fun close() {
            this@OsTcpSocket.close()
        }
    }

    init {
        if (configureIpv6Only && family == OsConstants.AF_INET6) {
            Os.setsockoptInt(fileDescriptor, OsConstants.IPPROTO_IPV6, OsConstants.IPV6_V6ONLY, 1)
        }
        if (family == OsConstants.AF_INET6) {
            runCatching {
                // IPV6_ADDR_PREFERENCES = 72, IPV6_PREFER_SRC_PUBLIC = 0x0002 (RFC 5014)
                // Instruct kernel source address selection to prefer public (stable) addresses
                // over temporary Privacy Extension addresses that rotate and drop connections.
                Os.setsockoptInt(fileDescriptor, OsConstants.IPPROTO_IPV6, 72, 2)
            }
        }
    }

    fun bindNetwork(network: Network?) {
        network?.bindSocket(fileDescriptor)
    }

    override fun bind(bindpoint: SocketAddress?) {
        val endpoint = bindpoint as? InetSocketAddress
            ?: InetSocketAddress(if (family == OsConstants.AF_INET6) "::" else "0.0.0.0", 0)
        val address = endpoint.address
        require(addressFamily(address) == family) { "Socket bind address has the wrong family" }
        Os.bind(fileDescriptor, address, endpoint.port)
        bound = true
        cachedLocalAddress = runCatching { Os.getsockname(fileDescriptor) }.getOrNull() ?: endpoint
    }

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        val remote = endpoint as? InetSocketAddress
            ?: throw IllegalArgumentException("Socket requires an InetSocketAddress")
        val address = remote.address
        require(addressFamily(address) == family) { "Socket remote address has the wrong family" }
        try {
            Os.connect(fileDescriptor, address, remote.port)
        } catch (error: ErrnoException) {
            if (error.errno != OsConstants.EINPROGRESS && error.errno != OsConstants.EALREADY) {
                throw connectException(error)
            }
            pollReady(OsConstants.POLLOUT, timeout, "Connect timed out")
            try {
                Os.connect(fileDescriptor, address, remote.port)
            } catch (completion: ErrnoException) {
                if (completion.errno != OsConstants.EISCONN) throw connectException(completion)
            }
        }
        connected = true
        bound = true
        cachedLocalAddress = runCatching { Os.getsockname(fileDescriptor) }.getOrNull() ?: cachedLocalAddress
        cachedRemoteAddress = runCatching { Os.getpeername(fileDescriptor) }.getOrNull() ?: remote
    }

    override fun connect(endpoint: SocketAddress?) = connect(endpoint, 0)

    override fun getInputStream(): InputStream = inputStream
    override fun getOutputStream(): OutputStream = outputStream
    override fun getLocalSocketAddress(): SocketAddress? {
        if (!bound) return null
        if (!closed) {
            runCatching { Os.getsockname(fileDescriptor) }.getOrNull()?.let {
                cachedLocalAddress = it
                return it
            }
        }
        return cachedLocalAddress
    }

    override fun getRemoteSocketAddress(): SocketAddress? {
        if (!connected) return null
        if (!closed) {
            runCatching { Os.getpeername(fileDescriptor) }.getOrNull()?.let {
                cachedRemoteAddress = it
                return it
            }
        }
        return cachedRemoteAddress
    }
    override fun getInetAddress(): InetAddress? = (remoteSocketAddress as? InetSocketAddress)?.address
    override fun getPort(): Int = (remoteSocketAddress as? InetSocketAddress)?.port ?: 0
    override fun isBound(): Boolean = bound
    override fun isConnected(): Boolean = connected
    override fun isClosed(): Boolean = closed

    override fun setReuseAddress(on: Boolean) {
        setInt(OsConstants.SOL_SOCKET, OsConstants.SO_REUSEADDR, on)
        try {
            setInt(OsConstants.SOL_SOCKET, SO_REUSEPORT_LINUX, on)
        } catch (_: Exception) {
        }
        reuseAddressEnabled = on
    }

    override fun getReuseAddress(): Boolean = reuseAddressEnabled

    override fun setTcpNoDelay(on: Boolean) {
        setInt(OsConstants.IPPROTO_TCP, OsConstants.TCP_NODELAY, on)
        tcpNoDelayEnabled = on
    }

    override fun getTcpNoDelay(): Boolean = tcpNoDelayEnabled

    override fun setSendBufferSize(size: Int) {
        Os.setsockoptInt(fileDescriptor, OsConstants.SOL_SOCKET, OsConstants.SO_SNDBUF, size)
        sendBufferBytes = size
    }

    override fun getSendBufferSize(): Int = sendBufferBytes

    override fun setReceiveBufferSize(size: Int) {
        Os.setsockoptInt(fileDescriptor, OsConstants.SOL_SOCKET, OsConstants.SO_RCVBUF, size)
        receiveBufferBytes = size
    }

    override fun getReceiveBufferSize(): Int = receiveBufferBytes

    override fun setSoTimeout(timeout: Int) {
        timeoutMillis = timeout
    }

    override fun getSoTimeout(): Int = timeoutMillis

    override fun close() {
        if (closed) return
        closed = true
        try {
            NativeSocketOptions.setLingerReset(fileDescriptor)
        } catch (_: Throwable) {
        }
        try {
            Os.close(fileDescriptor)
        } catch (_: ErrnoException) {
        }
    }

    private fun setInt(level: Int, option: Int, enabled: Boolean) =
        Os.setsockoptInt(fileDescriptor, level, option, if (enabled) 1 else 0)

    companion object {
        private const val POLL_STEP_MS = 250
        private const val SO_REUSEPORT_LINUX = 15
        private fun addressFamily(address: InetAddress): Int =
            if (address.address.size == 16) OsConstants.AF_INET6 else OsConstants.AF_INET

        private fun connectException(error: ErrnoException): ConnectException =
            ConnectException(error.message).also { it.initCause(error) }
    }
}

internal class OsTcpServerSocket(private val family: Int) : ServerSocket() {
    private val fileDescriptor = Os.socket(family, OsConstants.SOCK_STREAM, OsConstants.IPPROTO_TCP)
    @Volatile private var closed = false
    @Volatile private var bound = false
    @Volatile private var timeoutMillis = 0
    @Volatile private var cachedLocalAddress: SocketAddress? = null
    private var reuseAddressEnabled = false

    init {
        if (family == OsConstants.AF_INET6) {
            Os.setsockoptInt(fileDescriptor, OsConstants.IPPROTO_IPV6, OsConstants.IPV6_V6ONLY, 1)
            runCatching {
                Os.setsockoptInt(fileDescriptor, OsConstants.IPPROTO_IPV6, 72, 2)
            }
        }
    }

    override fun bind(endpoint: SocketAddress?, backlog: Int) {
        val local = endpoint as? InetSocketAddress
            ?: InetSocketAddress(if (family == OsConstants.AF_INET6) "::" else "0.0.0.0", 0)
        val address = local.address
        require((address.address.size == 16) == (family == OsConstants.AF_INET6)) {
            "Listener bind address has the wrong family"
        }
        Os.bind(fileDescriptor, address, local.port)
        Os.listen(fileDescriptor, if (backlog > 0) backlog else 50)
        bound = true
        cachedLocalAddress = runCatching { Os.getsockname(fileDescriptor) }.getOrNull() ?: local
    }

    override fun bind(endpoint: SocketAddress?) = bind(endpoint, 50)

    override fun accept(): Socket {
        if (closed) throw SocketException("Socket closed")
        if (timeoutMillis > 0) {
            val poll = StructPollfd().apply {
                fd = fileDescriptor
                events = OsConstants.POLLIN.toShort()
            }
            if (Os.poll(arrayOf(poll), timeoutMillis) == 0) throw SocketTimeoutException("Accept timed out")
            if (closed || (poll.revents.toInt() and OsConstants.POLLNVAL) != 0) {
                throw SocketException("Socket closed")
            }
        }
        return OsTcpSocket(
            family,
            Os.accept(fileDescriptor, InetSocketAddress(0)),
            configureIpv6Only = false,
            accepted = true,
        )
    }

    override fun getLocalSocketAddress(): SocketAddress? {
        if (!bound) return null
        if (!closed) {
            runCatching { Os.getsockname(fileDescriptor) }.getOrNull()?.let {
                cachedLocalAddress = it
                return it
            }
        }
        return cachedLocalAddress
    }
    override fun isBound(): Boolean = bound
    override fun isClosed(): Boolean = closed
    override fun setReuseAddress(on: Boolean) {
        Os.setsockoptInt(fileDescriptor, OsConstants.SOL_SOCKET, OsConstants.SO_REUSEADDR, if (on) 1 else 0)
        try {
            Os.setsockoptInt(fileDescriptor, OsConstants.SOL_SOCKET, 15, if (on) 1 else 0)
        } catch (_: Exception) {
        }
        reuseAddressEnabled = on
    }
    override fun getReuseAddress(): Boolean = reuseAddressEnabled
    override fun setSoTimeout(timeout: Int) { timeoutMillis = timeout }
    override fun getSoTimeout(): Int = timeoutMillis

    override fun close() {
        if (closed) return
        closed = true
        try {
            NativeSocketOptions.setLingerReset(fileDescriptor)
        } catch (_: Throwable) {
        }
        try {
            Os.close(fileDescriptor)
        } catch (_: ErrnoException) {
        }
    }
}
