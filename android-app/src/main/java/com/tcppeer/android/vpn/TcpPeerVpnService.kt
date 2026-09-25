package com.tcppeer.android.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.tcppeer.android.MainActivity
import com.tcppeer.android.R
import com.tcppeer.android.protocol.AddressNegotiation
import com.tcppeer.android.protocol.AuthProof
import com.tcppeer.android.protocol.ControlMessage
import com.tcppeer.android.protocol.DirectFamily
import com.tcppeer.android.protocol.ProtocolException
import com.tcppeer.android.protocol.TcpPeerProtocol
import com.tcppeer.android.protocol.TransportPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

private data class PublicEndpoint(val address: String, val port: Int)
private data class RoutePrefix(val address: ByteArray, val prefixLength: Int)
private data class TunStreams(
    val input: FileInputStream,
    val output: FileOutputStream,
)

private class TunPacketSink(private val output: FileOutputStream) {
    private val packets = Channel<ByteArray>(capacity = 2048)
    private val queued = AtomicInteger(0)
    private val writeLock = ReentrantLock()

    fun offer(packet: ByteArray, length: Int = packet.size): Boolean {
        if (writeLock.tryLock()) {
            try {
                if (queued.get() == 0) {
                    output.write(packet, 0, length)
                    return true
                }
            } finally {
                writeLock.unlock()
            }
        }
        queued.incrementAndGet()
        if (packets.trySend(packet.copyOf(length)).isSuccess) return true
        queued.decrementAndGet()
        return false
    }

    suspend fun consume() {
        for (packet in packets) {
            writeLock.lock()
            try {
                output.write(packet)
            } finally {
                queued.decrementAndGet()
                writeLock.unlock()
            }
        }
    }

    fun close() = packets.close()
}

class TcpPeerVpnService : VpnService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectionJob: Job? = null
    private var coordinatorSocket: Socket? = null
    private var directSocket: Socket? = null
    private val meshSockets = ConcurrentHashMap<String, Socket>()
    private val meshSocketKeys = ConcurrentHashMap<String, String>()
    private val meshEndpoints = ConcurrentHashMap<String, String>()
    private val meshCommitted = ConcurrentHashMap.newKeySet<String>()
    private val meshAdoptionLock = Any()
    private val meshConnecting = ConcurrentHashMap.newKeySet<String>()
    private val meshPunchActive = ConcurrentHashMap.newKeySet<String>()
    private val meshReadySentAt = ConcurrentHashMap<String, Long>()
    private val inFlightSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val nextTppPingId = AtomicLong(System.nanoTime())
    private val connectionGeneration = AtomicLong(0)
    private val pendingTppPings = ConcurrentHashMap<Long, Pair<String, Long>>()
    private val directListeners = mutableMapOf<DirectFamily, ServerSocket>()
    private var tunnel: ParcelFileDescriptor? = null
    private var tunnelInput: FileInputStream? = null
    private var tunnelOutput: FileOutputStream? = null
    private val disconnectRequested = AtomicBoolean(false)
    private val restartRequested = AtomicBoolean(false)
    private val stopRefreshStarted = AtomicBoolean(false)
    private lateinit var connectivityManager: ConnectivityManager
    @Volatile private var underlyingNetworkSignature: String? = null
    @Volatile private var underlyingNetwork: Network? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val linkProperties = connectivityManager.getLinkProperties(network) ?: return
            handleUnderlyingNetwork(network, linkProperties, "available")
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            handleUnderlyingNetwork(network, linkProperties, "addresses-changed")
        }

        override fun onLost(network: Network) {
            val lostActiveNetwork = synchronized(this@TcpPeerVpnService) {
                if (underlyingNetwork != network) {
                    false
                } else {
                    underlyingNetwork = null
                    underlyingNetworkSignature = null
                    true
                }
            }
            if (lostActiveNetwork && connectionJob?.isActive == true && !disconnectRequested.get()) {
                Log.i(TAG, "Physical underlay lost network=$network; abandoning the old TCPeer session")
                restartForNetworkChange()
            }
        }
    }

    private fun handleUnderlyingNetwork(network: Network, linkProperties: LinkProperties, reason: String) {
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return
            val addresses = linkProperties.linkAddresses.map { it.address }
            val (ipv4, ipv6) = TransportPolicy.localAddresses(addresses)
            // A Network handle identifies the physical underlay. LinkProperties
            // can change on the same Wi-Fi/LTE network whenever Android rotates
            // an IPv6 privacy address, renews DHCP, or updates DNS. Including
            // every address in this signature made those harmless updates tear
            // down the primary direct socket and, through its cleanup scope,
            // every mesh socket as well. A real Wi-Fi/LTE handover produces a
            // new Network handle (or onLost), which still triggers immediately.
            val signature = network.toString()
            val changed = synchronized(this) {
                val previous = underlyingNetworkSignature
                underlyingNetwork = network
                underlyingNetworkSignature = signature
                previous != null && previous != signature
            }
            if (changed && connectionJob?.isActive == true && !disconnectRequested.get()) {
                Log.i(
                    TAG,
                    "Physical underlay changed reason=$reason network=$network " +
                        "IPv4=${ipv4.joinToString { it.hostAddress.orEmpty() }} " +
                        "IPv6=${ipv6.joinToString { it.hostAddress?.substringBefore('%').orEmpty() }}; " +
                        "abandoning the old TCPeer session",
                )
                restartForNetworkChange()
            }
        }

    override fun onCreate() {
        super.onCreate()
        TcpPeerRuntime.setServiceActive(true)
        createNotificationChannel()
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> disconnect()
            ACTION_RENAME_DEVICE -> renameDevice(intent.getStringExtra(EXTRA_DEVICE_NAME).orEmpty())
            else -> {
                // API 28 commonly delivers a new CONNECT to the service
                // instance that handled DISCONNECT before onDestroy() runs.
                // onCreate() is therefore not a reliable indication that a
                // newly requested VPN session is active.
                TcpPeerRuntime.setServiceActive(true)
                if (connectionJob?.isActive != true) connect()
            }
        }
        return Service.START_NOT_STICKY
    }

    private fun renameDevice(displayName: String) {
        if (displayName.length !in 1..64 || displayName.any { it.code !in 32..126 }) return
        serviceScope.launch {
            runCatching {
                coordinatorSocket?.takeUnless { it.isClosed }?.getOutputStream()?.let { output ->
                    writeCoordinatorControl(output, ControlMessage("PEER-INFO", linkedMapOf(
                        "Action" to "Rename", "Device-Name" to displayName,
                    )))
                }
            }.onFailure { Log.w(TAG, "Could not rename this device while connected", it) }
        }
    }

    override fun onRevoke() {
        disconnect()
        super.onRevoke()
    }

    override fun onDestroy() {
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        connectionGeneration.incrementAndGet()
        closeResources()
        TcpPeerRuntime.setServiceActive(false)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun connect() {
        val generation = connectionGeneration.incrementAndGet()
        disconnectRequested.set(false)
        stopRefreshStarted.set(false)
        TcpPeerRuntime.setServiceActive(true)
        showForeground(ConnectionStatus.CONNECTING)
        TcpPeerRuntime.replace(VpnRuntimeState(
            status = ConnectionStatus.CONNECTING,
            detail = "Authenticating with the coordinator over cleartext TCP.",
        ))
        connectionJob = serviceScope.launch {
            try {
                val config = ConfigurationStore(this@TcpPeerVpnService).load().also { it.validate() }
                runConnection(config, generation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!disconnectRequested.get()) {
                    Log.e(TAG, "Connection failed", error)
                    reportClientError(error)
                    TcpPeerRuntime.update {
                        it.copy(
                            status = ConnectionStatus.NO_DIRECT_CONNECTION,
                            detail = error.message ?: "The direct connection failed.",
                            connectedAtMillis = null,
                        )
                    }
                    updateNotification(ConnectionStatus.NO_DIRECT_CONNECTION)
                }
            } finally {
                // A cancelled API 28 service session may finish after a new
                // ACTION_CONNECT has already started. Its cleanup must never
                // close sockets or overwrite UI state owned by that new run.
                if (connectionGeneration.get() != generation) {
                    // A user-requested disconnect also changes the generation.
                    // If no newer connect reset this flag, the old worker has
                    // only now actually left its blocking I/O. Repeat the
                    // service/foreground teardown at this point;
                    // otherwise its system VPN notification remains until
                    // unrelated traffic wakes the process again.
                    if (disconnectRequested.get()) {
                        Log.i(TAG, "Completing deferred disconnect for generation=$generation")
                        closeResources()
                        TcpPeerRuntime.replace(VpnRuntimeState())
                        TcpPeerRuntime.setServiceActive(false)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopAfterConnectivityRefresh()
                    } else {
                        Log.i(TAG, "Superseded connection generation=$generation stopped; starting the replacement session")
                        connectionJob = null
                        restartRequested.set(false)
                        TcpPeerRuntime.update {
                            it.copy(
                                status = ConnectionStatus.CONNECTING,
                                detail = "Underlying network changed. Reconnecting.",
                                connectedAtMillis = null,
                                devices = emptyList(),
                            )
                        }
                        updateNotification(ConnectionStatus.CONNECTING)
                        serviceScope.launch {
                            delay(250)
                            if (!disconnectRequested.get() && connectionJob?.isActive != true) connect()
                        }
                    }
                    return@launch
                }
                closeResources()

                val networkRestart = restartRequested.getAndSet(false)

                if (disconnectRequested.get()) {
                    connectionJob = null
                    TcpPeerRuntime.replace(VpnRuntimeState())
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopAfterConnectivityRefresh()
                } else {
                    connectionJob = null

                    TcpPeerRuntime.update {
                        it.copy(
                            status = ConnectionStatus.CONNECTING,
                            detail = if (networkRestart) {
                                "Underlying network changed. Reconnecting."
                            } else {
                                "Connection lost. Reconnecting."
                            },
                            connectedAtMillis = null,
                            devices = emptyList(),
                        )
                    }
                    updateNotification(ConnectionStatus.CONNECTING)

                    serviceScope.launch {
                        delay(750)
                        if (!disconnectRequested.get() &&
                            connectionJob?.isActive != true
                        ) {
                            connect()
                        }
                    }
                }
            }
        }
    }

    private fun restartForNetworkChange() {
        if (!restartRequested.compareAndSet(false, true)) return
        connectionGeneration.incrementAndGet()
        TcpPeerRuntime.update {
            it.copy(
                status = ConnectionStatus.CONNECTING,
                detail = "Underlying network changed. Rediscovering public endpoints.",
                devices = emptyList(),
            )
        }
        connectionJob?.cancel()
        closeResources()
    }

    private fun reportClientError(error: Exception) {
        val detail = (error.message ?: error.javaClass.simpleName)
            .replace('\r', ' ')
            .replace('\n', ' ')
            .take(300)
        runCatching {
            coordinatorSocket?.takeUnless { it.isClosed }?.getOutputStream()?.let { output ->
                writeCoordinatorControl(output, ControlMessage("PEER-INFO", linkedMapOf(
                    "Action" to "Client-Error",
                    "Detail" to detail,
                )))
            }
        }
    }

    private suspend fun runConnection(
        config: VpnConfiguration,
        generation: Long,
    ) = withContext(Dispatchers.IO) {
        updateConnecting("Resolving the coordinator DNS name.")
        val defaultNetwork = connectivityManager.activeNetwork
        val physicalNetwork = (
            listOfNotNull(defaultNetwork) +
                connectivityManager.allNetworks.filter { it != defaultNetwork }
            ).firstOrNull { network ->
                connectivityManager.getNetworkCapabilities(network)?.let { capabilities ->
                    !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                } == true
            }
        if (physicalNetwork != null) {
            synchronized(this@TcpPeerVpnService) {
                underlyingNetwork = physicalNetwork
                underlyingNetworkSignature = physicalNetwork.toString()
            }
        }
        val activeLinkProperties = physicalNetwork?.let(connectivityManager::getLinkProperties)
        val activeAddresses = activeLinkProperties?.linkAddresses?.map { it.address }.orEmpty() +
            TransportPolicy.clatIpv4Addresses()
        val (localIpv4, localIpv6) = TransportPolicy.localAddresses(activeAddresses)
        Log.i(
            TAG,
            "Endpoint discovery network=$physicalNetwork IPv4=${localIpv4.joinToString { it.hostAddress.orEmpty() }} " +
                "IPv6=${localIpv6.joinToString { it.hostAddress?.substringBefore('%').orEmpty() }}",
        )
        val coordinator = publishCoordinatorSocket(
            generation,
            openCoordinator(config, localIpv6.isNotEmpty(), physicalNetwork),
        )
        val controlInput = coordinator.getInputStream()
        val controlOutput = coordinator.getOutputStream()

        updateConnecting("Authenticating with the coordinator over cleartext TCP.")
        writeCoordinatorControl(controlOutput, ControlMessage("AUTH", linkedMapOf(
            "Network" to config.network,
            "Peer-ID" to config.peerId,
        )))
        val challenge = TcpPeerProtocol.readControl(controlInput)
        if (challenge.command != "AUTH-CHALLENGE") {
            throw ProtocolException(challenge.field("Reason") ?: "Coordinator did not issue an authentication challenge")
        }
        writeCoordinatorControl(controlOutput, ControlMessage("AUTH-PROOF", linkedMapOf(
            "Proof" to AuthProof.create(config.secret, config.network, config.peerId, challenge.field("Nonce").orEmpty()),
        )))
        val authentication = TcpPeerProtocol.readControl(controlInput)
        if (authentication.command != "AUTH-OK") {
            throw ProtocolException(authentication.field("Reason") ?: "Coordinator authentication failed")
        }
        val observed = TcpPeerProtocol.readControl(controlInput)
        if (observed.command != "ENDPOINT-INFO") throw ProtocolException("Coordinator did not report the TCP mapping")

        var endpointIpv4 = if (localIpv4.isNotEmpty()) {
            queryPublicEndpoint(config, DirectFamily.IPV4, physicalNetwork)
        } else null
        var endpointIpv6 = if (localIpv6.isNotEmpty()) {
            queryPublicEndpoint(config, DirectFamily.IPV6, physicalNetwork)
        } else null
        val directPublicIpv4 = localIpv4.firstOrNull(TransportPolicy::isPublicIpv4)?.hostAddress
        val directPublicIpv6 = localIpv6.firstOrNull(TransportPolicy::isPublicIpv6)?.hostAddress?.substringBefore('%')
        var advertisedIpv4 = ""
        var advertisedIpv6 = ""

        fun registerCurrentEndpoints() {
            advertisedIpv4 = endpointIpv4?.address ?: directPublicIpv4.orEmpty()
            advertisedIpv6 = endpointIpv6?.address ?: directPublicIpv6.orEmpty()
            val mappedIpv4Port = endpointIpv4?.port ?: if (directPublicIpv4 != null) config.directPort else null
            val mappedIpv6Port = endpointIpv6?.port ?: if (directPublicIpv6 != null) config.directPort else null
            writeCoordinatorControl(controlOutput, ControlMessage("REGISTER", linkedMapOf(
                "Peer-ID" to config.peerId,
                "Device-Name" to config.deviceName,
                "IPv4" to advertisedIpv4,
                "IPv6" to advertisedIpv6,
                "Mapped-IPv4-Port" to (mappedIpv4Port?.toString() ?: ""),
                "Mapped-IPv6-Port" to (mappedIpv6Port?.toString() ?: ""),
                "Local-IPv4" to (localIpv4.firstOrNull()?.hostAddress ?: ""),
                "Local-IPv6" to (localIpv6.firstOrNull()?.hostAddress?.substringBefore('%') ?: ""),
                "Port" to config.directPort.toString(),
                "Role" to "Client",
                "Platform" to "Android",
            )))
            readExpectedControl(controlInput, controlOutput, "ENDPOINT-INFO", "registration")
        }

        registerCurrentEndpoints()
        writeCoordinatorControl(controlOutput, ControlMessage("PEER-INFO", mapOf("Action" to "List")))
        readDeviceList(controlInput)
        val targetPeerId = config.targetPeerId
        var family: DirectFamily
        var address: InetAddress
        var peerPort: Int
        var direct: Socket

        while (true) {
            updateConnecting("Waiting for $targetPeerId to become ready.")
            writeCoordinatorControl(controlOutput, ControlMessage("PUNCH-READY", mapOf(
                "Peer-ID" to targetPeerId,
            )))

            val punch = awaitPunchGo(controlInput, controlOutput, targetPeerId)

            family = when (punch.field("Family")) {
                "IPv6" -> DirectFamily.IPV6
                "IPv4" -> DirectFamily.IPV4
                else -> throw ProtocolException("Coordinator returned an invalid direct family")
            }

            address = InetAddress.getByName(
                punch.field("Address")
                    ?: throw ProtocolException("PUNCH-GO has no address")
            )

            if (family == DirectFamily.IPV6 && address !is Inet6Address)
                throw ProtocolException("TCP6 requires an IPv6 endpoint")

            if (family == DirectFamily.IPV4 && address !is Inet4Address)
                throw ProtocolException("TCP4 requires an IPv4 endpoint")

            if (
                family == DirectFamily.IPV4 &&
                localIpv6.isNotEmpty() &&
                TransportPolicy.isUsableIpv6(address)
            ) {
                throw ProtocolException("TCP4 is forbidden when both peers have usable IPv6")
            }

            // Be reachable for the other half of the coordinated punch.  A
            // directly addressed peer (LAN or GUA) can then adopt the inbound
            // socket instead of rejecting it while Android only dials out.
            prepareDirectListener(config.directPort, family)

            val startMillis = punch.field("Start-Ms")?.toLongOrNull() ?: 0L
            val waitMillis = startMillis - System.currentTimeMillis()
            if (waitMillis > 0)
                kotlinx.coroutines.delay(waitMillis)

            peerPort = punch.field("Port")?.toIntOrNull()
                ?: throw ProtocolException("PUNCH-GO has no valid port")

            updateConnecting(
                "Opening a direct ${family.name.replace("IPV", "TCP")} connection."
            )

            try {
                direct = publishDirectSocket(
                    generation,
                    openDirect(address, peerPort, config.directPort, family),
                )

                break
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                updateConnecting(
                    "Attempt to direct connection timed out; retrying"
                )

                Log.w(
                    TAG,
                    "Direct connection attempt failed; requesting a new punch",
                    error,
                )

                // The failed active socket may have replaced the NAPT mapping
                // that was discovered before REGISTER. Refresh both mapped
                // endpoints before requesting another coordinated punch.
                endpointIpv4 = if (localIpv4.isNotEmpty()) {
                    queryPublicEndpoint(config, DirectFamily.IPV4, physicalNetwork)
                } else null
                endpointIpv6 = if (localIpv6.isNotEmpty()) {
                    queryPublicEndpoint(config, DirectFamily.IPV6, physicalNetwork)
                } else null
                registerCurrentEndpoints()
                Log.i(
                    TAG,
                    "Refreshed direct endpoint registration after failed punch " +
                        "IPv4=${endpointIpv4?.address}:${endpointIpv4?.port} " +
                        "IPv6=${endpointIpv6?.address}:${endpointIpv6?.port}",
                )
                kotlinx.coroutines.delay(1_000)
            }
        }
        val directInput = direct.getInputStream()
        val directOutput = direct.getOutputStream()
        TcpPeerProtocol.writeControl(directOutput, ControlMessage("PEER-INFO", linkedMapOf(
            "Network" to config.network,
            "Peer-ID" to config.peerId,
            "IPv4" to advertisedIpv4,
            "IPv6" to advertisedIpv6,
        )))
        val peerInfo = TcpPeerProtocol.readControl(directInput)
        if (peerInfo.command != "PEER-INFO" || peerInfo.field("Network") != config.network) {
            throw ProtocolException("Direct peer handshake failed")
        }
        val requiredFamily = TransportPolicy.choosePublic(advertisedIpv6, peerInfo.field("IPv6"))
        if (requiredFamily != family) {
            throw ProtocolException("Direct family violates the IPv6-first policy")
        }

        updateConnecting("Negotiating VPN IPv4 and IPv6 addresses.")
        val addresses = negotiateAddresses(directInput, directOutput, config)
        Log.i(
            TAG,
            "Negotiated overlay addresses: IPv4=${addresses.first.address.hostAddress} " +
                "IPv6=${addresses.second.address.hostAddress}"
        )

        Log.i(TAG, "Sending Overlay-Update to coordinator")

        writeCoordinatorControl(controlOutput, ControlMessage("PEER-INFO", linkedMapOf(
            "Action" to "Overlay-Update",
            "Overlay-IPv4" to (addresses.first.address.hostAddress ?: ""),
            "Overlay-IPv6" to (addresses.second.address.hostAddress ?: ""),
        )))

        Log.i(TAG, "Overlay-Update sent to coordinator")
        val descriptor = establishTunnel(config, addresses.first, addresses.second)
            ?: throw IllegalStateException("Android refused to establish the VPN interface")
        val tunStreams = publishTunnel(generation, descriptor)
        direct.soTimeout = 0
        val status = if (family == DirectFamily.IPV6) ConnectionStatus.TCP6_DIRECT else ConnectionStatus.TCP4_DIRECT
        TcpPeerRuntime.update { it.copy(
            status = status, endpoint = formatEndpoint(address, peerPort),
            overlayIpv4 = addresses.first.address.hostAddress ?: "-",
            overlayIpv6 = addresses.second.address.hostAddress ?: "-",
            connectedAtMillis = System.currentTimeMillis(),
            detail = "Direct cleartext TCP connection. No relay and no encryption.",
        ) }
        updateNotification(status)

        coroutineScope {
            // Coalesce adjacent raw IP packets before handing them to TCP.
            // Packet boundaries remain self-described by the IP headers; the
            // short periodic flush only changes syscall/segment batching.
            val primaryDataOutput = BufferedOutputStream(
                directOutput,
                DIRECT_STREAM_BUFFER_BYTES,
            )
            val peerOutputs = ConcurrentHashMap<String, java.io.OutputStream>()
            peerOutputs[targetPeerId] = primaryDataOutput
            meshSockets[targetPeerId] = direct
            meshSocketKeys[targetPeerId] = connectionKey(direct)
            meshEndpoints[targetPeerId] = formatSocketEndpoint(direct)
            meshCommitted.add(targetPeerId)
            updateConnectedUsing(
                targetPeerId,
                formatSocketEndpoint(direct),
                familyLabel(family),
            )
            val tunPackets = TunPacketSink(tunStreams.output)
            val tunWriterJob = launch(Dispatchers.IO) {
                tunPackets.consume()
            }
            tunWriterJob.invokeOnCompletion { error ->
                if (error != null && error !is CancellationException) {
                    Log.e(
                        TAG,
                        "TUN writer stopped unexpectedly; closing the primary direct socket to reconnect",
                        error,
                    )
                    closeQuietly(direct)
                }
            }
            prepareDirectListener(config.directPort, family)
            val passiveAcceptJob = launch(Dispatchers.IO) {
                acceptMeshConnections(
                    family,
                    config, advertisedIpv4, advertisedIpv6,
                    addresses.second.address, peerOutputs, tunPackets,
                )
            }
            val coordinatorControlJob = launch(Dispatchers.IO) {
                val devices = mutableListOf<NetworkDevice>()
                var listInProgress = false

                while (true) {
                    if (!listInProgress) {
                        devices.clear()
                        listInProgress = true
                        writeCoordinatorControl(
                            controlOutput,
                            ControlMessage("PEER-INFO", mapOf("Action" to "List")),
                        )
                    }

                    try {
                        coordinator.soTimeout = DEVICE_REFRESH_INTERVAL_MS.toInt()
                        val message = TcpPeerProtocol.readControl(controlInput)

                        when (message.command) {
                            "PEER-INFO" -> when (message.field("Action")) {
                                "Device" -> devices += NetworkDevice(
                                    peerId = message.field("Peer-ID") ?: "unknown",
                                    displayName = message.field("Device-Name")
                                        ?: message.field("Peer-ID") ?: "Unknown device",
                                    online = message.field("Online") == "yes" ||
                                        message.field("Peer-ID")?.let(meshSockets::containsKey) == true,
                                    role = message.field("Role") ?: "Client",
                                    platform = message.field("Platform") ?: "Unknown",
                                    transport = message.field("Peer-ID")?.let(meshSockets::get)
                                        ?.let(::socketFamily)
                                        ?: message.field("Transport")
                                        ?: "None",
                                    ipv4 = message.field("IPv4").orEmpty().ifBlank { "-" },
                                    ipv6 = message.field("IPv6").orEmpty().ifBlank { "-" },
                                    overlayIpv4 = message.field("Overlay-IPv4").orEmpty().ifBlank { "-" },
                                    overlayIpv6 = message.field("Overlay-IPv6").orEmpty().ifBlank { "-" },
                                    connectedUsing = message.field("Peer-ID")?.let(meshEndpoints::get) ?: "-",
                                ).also { device ->
                                    if (
                                        device.online &&
                                        device.peerId != config.peerId &&
                                        device.peerId != targetPeerId &&
                                        !meshSockets.containsKey(device.peerId) &&
                                        shouldRequestMeshPunch(device.peerId)
                                    ) {
                                        Log.i(TAG, "Requesting mesh punch peer_id=${device.peerId} reason=no-direct-socket")
                                        writeCoordinatorControl(
                                            controlOutput,
                                            ControlMessage("PUNCH-READY", mapOf("Peer-ID" to device.peerId)),
                                        )
                                    }
                                }

                                "List-End" -> {
                                    TcpPeerRuntime.update { state ->
                                        state.copy(
                                            devices = devices.sortedWith(
                                                compareByDescending<NetworkDevice> { it.online }
                                                    .thenBy { it.displayName.lowercase() },
                                            ),
                                        )
                                    }
                                    listInProgress = false
                                    delay(DEVICE_REFRESH_INTERVAL_MS)
                                }

                                "Punch-Request" -> {
                                    val requestedPeer = message.field("Peer-ID") ?: targetPeerId
                                    if (!meshSockets.containsKey(requestedPeer)) {
                                        writeCoordinatorControl(
                                            controlOutput,
                                            ControlMessage(
                                                "PUNCH-READY",
                                                mapOf("Peer-ID" to requestedPeer),
                                            ),
                                        )
                                    } else {
                                        Log.i(
                                            TAG,
                                            "Ignoring stale punch request for connected peer_id=$requestedPeer",
                                        )
                                    }
                                }

                            }

                            "PUNCH-GO" -> {
                                val punchPeer = message.field("Peer-ID")
                                if (punchPeer != null && meshPunchActive.add(punchPeer)) {
                                    meshReadySentAt.remove(punchPeer)
                                    launch {
                                        try {
                                            connectMeshPeer(
                                                message, config, advertisedIpv4, advertisedIpv6,
                                                addresses.second.address, peerOutputs, tunPackets,
                                            )
                                        } finally {
                                            meshPunchActive.remove(punchPeer)
                                        }
                                    }
                                }
                            }

                            "PING", "KEEPALIVE" -> {
                                writeCoordinatorControl(
                                    controlOutput,
                                    ControlMessage("PONG"),
                                )
                            }

                            "AUTH-ERROR", "DISCONNECT" -> {
                                throw ProtocolException(
                                    message.field("Reason")
                                        ?: "Coordinator disconnected",
                                )
                            }

                            "ERROR" -> {
                                val reason = message.field("Reason")
                                    ?: "Coordinator rejected the mesh request"
                                // Once the primary tunnel is established, coordinator
                                // errors belong to optional mesh attempts.  A peer with
                                // no compatible endpoint must not tear down the healthy
                                // Exit Node connection and the entire Android VPN.
                                meshConnecting.clear()
                                meshReadySentAt.clear()
                                Log.w(TAG, "Mesh request rejected by coordinator: $reason")
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                        // Keep waiting for the current list. Do not start
                        // another List request until List-End arrives.
                    }
                }
            }
            coordinatorControlJob.invokeOnCompletion { error ->
                if (error != null && error !is CancellationException) {
                    // java.io blocking reads do not observe coroutine
                    // cancellation. Without closing this socket, peerToTun can
                    // remain inside readData(), continue answering TPCP
                    // keepalives, and keep the service looking connected after
                    // the control plane and TUN writer have already died.
                    Log.e(
                        TAG,
                        "Coordinator control worker stopped; closing the primary direct socket to reconnect",
                        error,
                    )
                    closeQuietly(direct)
                }
            }

            try {
                exchangePackets(
                    tunStreams.input,
                    BufferedInputStream(directInput, DIRECT_STREAM_BUFFER_BYTES),
                    primaryDataOutput,
                    addresses.second.address,
                    targetPeerId,
                    peerOutputs,
                    tunPackets,
                )
            } finally {
                closeDirectListeners()
                meshSockets.values.forEach(::closeQuietly)
                coordinatorControlJob.cancel()
                passiveAcceptJob.cancel()
                tunPackets.close()
                tunWriterJob.cancel()
                closeQuietly(tunStreams.input)
                closeQuietly(tunStreams.output)
            }
        }
    }

    private suspend fun connectMeshPeer(
        punch: ControlMessage,
        config: VpnConfiguration,
        advertisedIpv4: String,
        advertisedIpv6: String,
        overlayIpv6: Inet6Address,
        peerOutputs: ConcurrentHashMap<String, java.io.OutputStream>,
        tunPackets: TunPacketSink,
    ) {
        val peerId = punch.field("Peer-ID") ?: return
        if (peerId == config.peerId || meshSockets.containsKey(peerId)) return
        meshConnecting.add(peerId)
        val family = when (punch.field("Family")) {
            "IPv6" -> DirectFamily.IPV6
            "IPv4" -> DirectFamily.IPV4
            else -> return
        }
        val address = InetAddress.getByName(punch.field("Address") ?: return)
        val port = punch.field("Port")?.toIntOrNull() ?: return
        val traversal = "Simultaneous-Open"
        val waitMillis = (punch.field("Start-Ms")?.toLongOrNull() ?: 0L) - System.currentTimeMillis()
        if (waitMillis > 0) delay(waitMillis)
        val activeLocalPort = config.directPort
        closeDirectListener(family)
        Log.i(
            TAG,
            "Mesh attempt peer_id=$peerId family=${familyLabel(family)} traversal=$traversal " +
                "initiated=true local_port=$activeLocalPort remote=${formatEndpoint(address, port)}",
        )
        val socket = try {
            openActiveDirect(address, port, activeLocalPort, family, peerId)
        } catch (error: Exception) {
            Log.w(TAG, "Direct mesh connection to $peerId failed", error)
            meshConnecting.remove(peerId)
            prepareDirectListener(config.directPort, family)
            return
        }
        prepareDirectListener(config.directPort, family)
        try {
            val input = BufferedInputStream(socket.getInputStream(), DIRECT_STREAM_BUFFER_BYTES)
            val output = socket.getOutputStream()
            TcpPeerProtocol.writeControl(output, ControlMessage("PEER-INFO", linkedMapOf(
                "Network" to config.network,
                "Peer-ID" to config.peerId,
                "IPv4" to advertisedIpv4,
                "IPv6" to advertisedIpv6,
            )))
            val peerInfo = TcpPeerProtocol.readControl(input)
            if (peerInfo.command != "PEER-INFO" || peerInfo.field("Network") != config.network) {
                throw ProtocolException("Direct mesh peer handshake failed")
            }
            // The handshake timeout must not become an idle lifetime for the
            // established raw-IP stream. Leaving 15 seconds here caused every
            // quiet mesh connection to be closed and punched again forever.
            socket.soTimeout = 0
            if (!adoptMeshSocket(peerId, socket, output, peerOutputs, initiated = true)) return
            val lastRx = AtomicLong(System.nanoTime())
            val keepalive = serviceScope.launch(Dispatchers.IO) {
                while (true) {
                    delay(15_000)
                    if (System.nanoTime() - lastRx.get() >= 45_000_000_000L) {
                        Log.w(TAG, "Mesh data-plane TPCP keepalive timeout peer_id=$peerId")
                        closeQuietly(socket)
                        return@launch
                    }
                    synchronized(output) { TcpPeerProtocol.writeDataPlaneControl(output, "KEEPALIVE") }
                }
            }
            try {
                while (true) {
                    val packet = TcpPeerProtocol.readData(
                        input, output, { lastRx.set(System.nanoTime()) },
                    ) { command, identifier, _ -> handleTppControl(peerId, command, identifier) }
                    commitMeshSocket(peerId, socket)
                    processInboundPacket(packet, peerId, overlayIpv6, output, tunPackets)
                }
            } finally {
                keepalive.cancel()
                peerOutputs.remove(peerId, output)
                if (meshSockets.remove(peerId, socket)) {
                    meshSocketKeys.remove(peerId, connectionKey(socket))
                    meshEndpoints.remove(peerId)
                    meshCommitted.remove(peerId)
                    updateConnectedUsing(peerId, "-", null)
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Direct mesh connection to $peerId closed", error)
        } finally {
            meshConnecting.remove(peerId)
            meshReadySentAt.remove(peerId)
            closeQuietly(socket)
        }
    }

    private suspend fun acceptMeshConnections(
        family: DirectFamily,
        config: VpnConfiguration,
        advertisedIpv4: String,
        advertisedIpv6: String,
        overlayIpv6: Inet6Address,
        peerOutputs: ConcurrentHashMap<String, java.io.OutputStream>,
        tunPackets: TunPacketSink,
    ) = coroutineScope {
        while (currentCoroutineContext().isActive) {
            val socket = try {
                acceptPassiveDirect(family)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (error: Exception) {
                val listenerActive = synchronized(directListeners) {
                    directListeners[family]?.isClosed == false
                }
                if (!currentCoroutineContext().isActive) break
                if (!listenerActive) {
                    delay(100)
                    continue
                }
                Log.w(TAG, "${familyLabel(family)} passive accept failed; listener remains active", error)
                delay(100)
                continue
            }
            launch(Dispatchers.IO) {
                handleAcceptedMeshSocket(
                    socket, config, advertisedIpv4, advertisedIpv6,
                    overlayIpv6, peerOutputs, tunPackets,
                )
            }
        }
    }

    private fun handleAcceptedMeshSocket(
        socket: Socket,
        config: VpnConfiguration,
        advertisedIpv4: String,
        advertisedIpv6: String,
        overlayIpv6: Inet6Address,
        peerOutputs: ConcurrentHashMap<String, java.io.OutputStream>,
        tunPackets: TunPacketSink,
    ) {
        var peerId = "unknown"
        var output: java.io.OutputStream? = null
        try {
            val input = BufferedInputStream(socket.getInputStream(), DIRECT_STREAM_BUFFER_BYTES)
            output = socket.getOutputStream()
            val peerInfo = TcpPeerProtocol.readControl(input)
            peerId = peerInfo.field("Peer-ID") ?: "unknown"
            if (peerInfo.command != "PEER-INFO" || peerInfo.field("Network") != config.network) {
                throw ProtocolException("Accepted mesh peer handshake failed")
            }
            TcpPeerProtocol.writeControl(output, ControlMessage("PEER-INFO", linkedMapOf(
                "Network" to config.network,
                "Peer-ID" to config.peerId,
                "IPv4" to advertisedIpv4,
                "IPv6" to advertisedIpv6,
            )))
            // Keep the established mesh stream blocking indefinitely after
            // the bounded handshake, matching the primary direct connection.
            socket.soTimeout = 0
            if (!adoptMeshSocket(peerId, socket, output, peerOutputs, initiated = false)) return
            val lastRx = AtomicLong(System.nanoTime())
            val keepalive = serviceScope.launch(Dispatchers.IO) {
                while (true) {
                    delay(15_000)
                    if (System.nanoTime() - lastRx.get() >= 45_000_000_000L) {
                        Log.w(TAG, "Accepted mesh data-plane TPCP keepalive timeout peer_id=$peerId")
                        closeQuietly(socket)
                        return@launch
                    }
                    synchronized(output) { TcpPeerProtocol.writeDataPlaneControl(output, "KEEPALIVE") }
                }
            }
            try {
                while (true) {
                    val packet = TcpPeerProtocol.readData(
                        input, output, { lastRx.set(System.nanoTime()) },
                    ) { command, identifier, _ -> handleTppControl(peerId, command, identifier) }
                    commitMeshSocket(peerId, socket)
                    processInboundPacket(packet, peerId, overlayIpv6, output, tunPackets)
                }
            } finally {
                keepalive.cancel()
            }
        } catch (error: Exception) {
            Log.w(
                TAG,
                "Mesh accepted socket closed peer_id=$peerId family=${socketFamily(socket)} " +
                    "socket=${socketToken(socket)} local=${socket.localSocketAddress} " +
                    "remote=${socket.remoteSocketAddress} reason=${error.message}",
                error,
            )
        } finally {
            output?.let { peerOutputs.remove(peerId, it) }
            if (meshSockets.remove(peerId, socket)) {
                meshSocketKeys.remove(peerId, connectionKey(socket))
                meshEndpoints.remove(peerId)
                meshCommitted.remove(peerId)
                updateConnectedUsing(peerId, "-", null)
            }
            meshConnecting.remove(peerId)
            meshReadySentAt.remove(peerId)
            closeQuietly(socket)
        }
    }

    private fun adoptMeshSocket(
        peerId: String,
        socket: Socket,
        output: java.io.OutputStream,
        peerOutputs: ConcurrentHashMap<String, java.io.OutputStream>,
        initiated: Boolean,
    ): Boolean {
        val key = connectionKey(socket)
        var replaced: Socket? = null
        synchronized(meshAdoptionLock) {
            val current = meshSockets[peerId]
            val currentKey = meshSocketKeys[peerId]
            if (
                current != null && currentKey != null &&
                (peerId in meshCommitted || key >= currentKey)
            ) {
                Log.i(
                    TAG,
                    "Mesh socket rejected peer_id=$peerId family=${socketFamily(socket)} " +
                        "socket=${socketToken(socket)} initiated=$initiated local=${socket.localSocketAddress} " +
                        "remote=${socket.remoteSocketAddress} reason=${if (peerId in meshCommitted) "current-owner-has-data" else "deterministic-loser"} " +
                        "winner_key=$currentKey loser_key=$key",
                )
                closeQuietly(socket)
                return false
            }
            replaced = current
            meshSockets[peerId] = socket
            meshSocketKeys[peerId] = key
            meshEndpoints[peerId] = formatSocketEndpoint(socket)
            peerOutputs[peerId] = output
        }
        replaced?.takeUnless { it === socket }?.let {
            Log.i(
                TAG,
                "Mesh socket replaced peer_id=$peerId old_socket=${socketToken(it)} " +
                    "new_socket=${socketToken(socket)} reason=deterministic-winner",
            )
            closeQuietly(it)
        }
        Log.i(
            TAG,
            "Mesh socket adopted peer_id=$peerId family=${socketFamily(socket)} " +
                "socket=${socketToken(socket)} initiated=$initiated local=${socket.localSocketAddress} " +
                "remote=${socket.remoteSocketAddress} key=$key",
        )
        updateConnectedUsing(peerId, formatSocketEndpoint(socket), socketFamily(socket))
        meshReadySentAt.remove(peerId)
        return true
    }

    private fun shouldRequestMeshPunch(peerId: String): Boolean {
        meshConnecting.add(peerId)
        if (meshPunchActive.contains(peerId)) return false
        val now = android.os.SystemClock.elapsedRealtime()
        val previous = meshReadySentAt[peerId]
        if (previous != null && now - previous < MESH_PUNCH_RETRY_MS) return false
        meshReadySentAt[peerId] = now
        return true
    }

    private fun commitMeshSocket(peerId: String, socket: Socket) {
        synchronized(meshAdoptionLock) {
            if (meshSockets[peerId] === socket) meshCommitted.add(peerId)
        }
    }

    private fun updateConnectedUsing(peerId: String, endpoint: String, transport: String?) {
        TcpPeerRuntime.update { state -> state.copy(
            devices = state.devices.map { device ->
                if (device.peerId == peerId) device.copy(
                    connectedUsing = endpoint,
                    transport = transport ?: device.transport,
                ) else device
            },
        ) }
    }

    private fun processInboundPacket(
        packet: ByteArray,
        peerId: String,
        overlayIpv6: Inet6Address,
        output: java.io.OutputStream,
        tunPackets: TunPacketSink,
        length: Int = packet.size,
    ): Int {
        if (AddressNegotiation.isRouterAdvertisement(packet)) return 0
        if (!tunPackets.offer(packet, length))
            Log.w(TAG, "TUN receive queue full; dropping packet from peer_id=$peerId")
        return 0
    }

    private fun handleTppControl(peerId: String, command: String, identifier: Long) {
        if (command != "TPP-PONG") return
        pendingTppPings.remove(identifier)?.let { (pingPeerId, sentAt) ->
            val latencyMillis = (System.nanoTime() - sentAt) / 1_000_000.0
            TcpPeerRuntime.recordPing(pingPeerId, latencyMillis)
            Log.d(TAG, "TPCP TPP reply received peer_id=$peerId identifier=$identifier")
        }
    }

    private fun awaitPunchGo(input: java.io.InputStream, output: java.io.OutputStream, targetPeerId: String): ControlMessage {
        while (true) {
            when (val message = TcpPeerProtocol.readControl(input)) {
                is ControlMessage -> when (message.command) {
                    "PUNCH-GO" -> if (message.field("Peer-ID") == targetPeerId) return message
                    "PEER-INFO" -> if (message.field("Action") == "Punch-Request") {
                        val requestedPeer = message.field("Peer-ID")
                        if (requestedPeer == targetPeerId) {
                            writeCoordinatorControl(output, ControlMessage("PUNCH-READY", mapOf(
                                "Peer-ID" to targetPeerId,
                            )))
                        } else {
                            Log.i(
                                TAG,
                                "Deferring mesh punch for peer_id=$requestedPeer until the primary tunnel is ready",
                            )
                        }
                    }
                    "PING", "KEEPALIVE" -> writeCoordinatorControl(output, ControlMessage("PONG"))
                    "AUTH-ERROR", "DISCONNECT" -> throw ProtocolException(message.field("Reason") ?: "Coordinator disconnected")
                    "ERROR" -> throw ProtocolException(message.field("Reason") ?: "Coordinator rejected the direct connection")
                }
            }
        }
    }

    private fun readExpectedControl(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        expectedCommand: String,
        phase: String,
    ): ControlMessage {
        while (true) {
            val message = TcpPeerProtocol.readControl(input)
            when (message.command) {
                expectedCommand -> return message
                "PING", "KEEPALIVE" -> {
                    Log.d(TAG, "Answered coordinator liveness probe while waiting for $phase")
                    writeCoordinatorControl(output, ControlMessage("PONG"))
                }
                "AUTH-ERROR", "DISCONNECT", "ERROR" -> throw ProtocolException(
                    message.field("Reason") ?: "Coordinator rejected $phase",
                )
                "PEER-INFO", "PUNCH-GO" -> {
                    // Directory and punch notifications are asynchronous and
                    // may be queued immediately before a retrying REGISTER.
                    // The primary/mesh loops request fresh state after this
                    // synchronous phase, so a stale notification must not
                    // tear down the newly established control session.
                    Log.i(
                        TAG,
                        "Deferred asynchronous coordinator message command=${message.command} " +
                            "while waiting for $expectedCommand during $phase",
                    )
                }
                else -> throw ProtocolException(
                    "Coordinator sent ${message.command} while waiting for $expectedCommand during $phase",
                )
            }
        }
    }

    private fun readDeviceList(input: java.io.InputStream) {
        val devices = mutableListOf<NetworkDevice>()
        while (true) {
            val message = TcpPeerProtocol.readControl(input)
            if (message.command == "ERROR") throw ProtocolException(message.field("Reason") ?: "Device list failed")
            if (message.command != "PEER-INFO") continue
            when (message.field("Action")) {
                "List-End" -> {
                    TcpPeerRuntime.update { state -> state.copy(devices = devices.sortedWith(
                        compareByDescending<NetworkDevice> { it.online }.thenBy { it.displayName.lowercase() },
                    )) }
                    return
                }
                "Device" -> devices += NetworkDevice(
                    peerId = message.field("Peer-ID") ?: "unknown",
                    displayName = message.field("Device-Name")
                        ?: message.field("Peer-ID") ?: "Unknown device",
                    online = message.field("Online") == "yes" ||
                        message.field("Peer-ID")?.let(meshSockets::containsKey) == true,
                    role = message.field("Role") ?: "Client",
                    platform = message.field("Platform") ?: "Unknown",
                    transport = message.field("Transport") ?: "None",
                    ipv4 = message.field("IPv4").orEmpty().ifBlank { "-" },
                    ipv6 = message.field("IPv6").orEmpty().ifBlank { "-" },
                    overlayIpv4 = message.field("Overlay-IPv4").orEmpty().ifBlank { "-" },
                    overlayIpv6 = message.field("Overlay-IPv6").orEmpty().ifBlank { "-" },
                    connectedUsing = message.field("Peer-ID")?.let(meshEndpoints::get) ?: "-",
                )
            }
        }
    }

    private fun updateConnecting(detail: String) {
        TcpPeerRuntime.update { it.copy(status = ConnectionStatus.CONNECTING, detail = detail) }
    }

    private fun writeCoordinatorControl(
        output: java.io.OutputStream,
        message: ControlMessage,
    ) {
        synchronized(output) {
            TcpPeerProtocol.writeControl(output, message)
        }
    }

    private fun resolveCoordinatorAddresses(
        host: String,
        physicalNetwork: Network?,
    ): List<InetAddress> {
        val normalized = host.trim().removeSurrounding("[", "]")
        require(normalized.isNotEmpty()) { "Coordinator DNS name or IP address is required" }
        return try {
            physicalNetwork?.getAllByName(normalized)?.toList()
                ?: TransportPolicy.resolveTcpAddresses(normalized)
        } catch (error: java.net.UnknownHostException) {
            throw IllegalArgumentException("Cannot resolve coordinator DNS name: $host", error)
        }
    }

    private fun openCoordinator(
        config: VpnConfiguration,
        preferIpv6: Boolean,
        physicalNetwork: Network?,
    ): Socket {
        val addresses = resolveCoordinatorAddresses(
            config.coordinatorAddress, physicalNetwork,
        ).sortedBy {
            if (preferIpv6) if (it is Inet6Address) 0 else 1 else if (it is Inet4Address) 0 else 1
        }
        var lastError: Exception? = null
        addresses.forEach { address ->
            val family = if (address is Inet6Address) DirectFamily.IPV6 else DirectFamily.IPV4
            val socket = Socket()
            inFlightSockets.add(socket)
            try {
                socket.reuseAddress = true
                physicalNetwork?.bindSocket(socket)
                val wildcard = if (address is Inet6Address) InetAddress.getByName("::") else InetAddress.getByName("0.0.0.0")
                // The coordinator is control-plane traffic. Binding it to the direct
                // port conflicts with the passive TCP listener on Android kernels.
                // The coordinator still observes the public IP; REGISTER explicitly
                // advertises the direct listener port.
                socket.bind(InetSocketAddress(wildcard, 0))
                if (!protect(socket)) throw IllegalStateException("Cannot protect the coordinator socket from the VPN")
                socket.connect(InetSocketAddress(address, config.coordinatorPort), 10_000)
                socket.tcpNoDelay = false
                socket.soTimeout = COORDINATOR_TIMEOUT_MS
                return socket
            } catch (error: Exception) {
                lastError = error
                inFlightSockets.remove(socket)
                socket.close()
            }
        }
        throw IllegalStateException("Cannot connect to the coordinator", lastError)
    }

    private fun queryPublicEndpoint(
        config: VpnConfiguration,
        family: DirectFamily,
        physicalNetwork: Network?,
    ): PublicEndpoint? {
        val addresses = resolveCoordinatorAddresses(
            config.coordinatorAddress, physicalNetwork,
        ).filter {
            (family == DirectFamily.IPV6 && it is Inet6Address) ||
                (family == DirectFamily.IPV4 && it is Inet4Address)
        }
        addresses.forEach { address ->
            val socket = Socket()
            inFlightSockets.add(socket)
            try {
                socket.reuseAddress = true
                physicalNetwork?.bindSocket(socket)
                val wildcard = if (family == DirectFamily.IPV6) InetAddress.getByName("::") else InetAddress.getByName("0.0.0.0")
                socket.bind(InetSocketAddress(wildcard, config.directPort))
                if (!protect(socket)) throw IllegalStateException("Cannot protect the endpoint query socket")
                socket.connect(InetSocketAddress(address, config.coordinatorPort), 5_000)
                socket.soTimeout = 5_000
                TcpPeerProtocol.writeControl(socket.getOutputStream(), ControlMessage("ENDPOINT-QUERY"))
                val response = TcpPeerProtocol.readControl(socket.getInputStream())
                if (response.command == "ENDPOINT-INFO") {
                    val endpointAddress = response.field("Address")
                    val endpointPort = response.field("Port")?.toIntOrNull()
                    if (!endpointAddress.isNullOrBlank() && endpointPort != null) {
                        return PublicEndpoint(endpointAddress, endpointPort)
                    }
                }
            } catch (error: Exception) {
                Log.w(
                    TAG,
                    "${familyLabel(family)} endpoint discovery failed " +
                        "network=$physicalNetwork coordinator=${address.hostAddress}",
                    error,
                )
            } finally {
                inFlightSockets.remove(socket)
                socket.close()
            }
        }
        return null
    }

    private suspend fun openDirect(address: InetAddress, port: Int, localPort: Int, family: DirectFamily): Socket =
        withContext(Dispatchers.IO) {
            try {
                Log.i(
                    TAG,
                    "Direct passive window started peer_id=primary family=${familyLabel(family)} " +
                        "initiated=false local_port=$localPort remote=${formatEndpoint(address, port)}",
                )
                return@withContext acceptPassiveDirect(family).also {
                    Log.i(
                        TAG,
                        "Direct passive winner peer_id=primary family=${familyLabel(family)} " +
                            "socket=${socketToken(it)} initiated=false local=${it.localSocketAddress} " +
                            "remote=${it.remoteSocketAddress}",
                    )
                }
            } catch (_: SocketTimeoutException) {
                Log.i(
                    TAG,
                    "Direct passive window expired peer_id=primary family=${familyLabel(family)} " +
                        "reason=no-inbound-syn",
                )
            }
            synchronized(directListeners) {
                directListeners.remove(family)?.let(::closeQuietly)
            }
            openActiveDirect(address, port, localPort, family)
        }

    private fun openActiveDirect(address: InetAddress, port: Int, localPort: Int, family: DirectFamily): Socket {
        return openActiveDirect(address, port, localPort, family, "primary")
    }

    private fun openActiveDirect(
        address: InetAddress,
        port: Int,
        localPort: Int,
        family: DirectFamily,
        peerId: String,
    ): Socket {
        val socket = Socket()
        inFlightSockets.add(socket)
        try {
            socket.reuseAddress = true
            underlyingNetwork?.bindSocket(socket)
            val wildcard = if (family == DirectFamily.IPV6) InetAddress.getByName("::") else InetAddress.getByName("0.0.0.0")
            // TCP simultaneous-open requires the same local endpoint that was
            // used for public mapping discovery. An ephemeral source port makes
            // two-NAT hole punching impossible.
            socket.bind(InetSocketAddress(wildcard, localPort))
            Log.i(
                TAG,
                "Direct bind succeeded peer_id=$peerId family=${familyLabel(family)} " +
                    "socket=${socketToken(socket)} initiated=true local=${socket.localSocketAddress} " +
                    "remote=${formatEndpoint(address, port)}",
            )
            if (!protect(socket)) throw IllegalStateException("Cannot protect the direct socket from the VPN")
            socket.sendBufferSize = DIRECT_SOCKET_BUFFER_BYTES
            socket.receiveBufferSize = DIRECT_SOCKET_BUFFER_BYTES
            socket.connect(InetSocketAddress(address, port), 12_000)
            socket.tcpNoDelay = false
            socket.soTimeout = 15_000
            inFlightSockets.remove(socket)
            Log.i(
                TAG,
                "Direct connect succeeded peer_id=$peerId family=${familyLabel(family)} " +
                    "socket=${socketToken(socket)} initiated=true local=${socket.localSocketAddress} " +
                    "remote=${socket.remoteSocketAddress}",
            )
            return socket
        } catch (error: Exception) {
            Log.w(
                TAG,
                "Direct connect failed peer_id=$peerId family=${familyLabel(family)} " +
                    "socket=${socketToken(socket)} initiated=true local=${socket.localSocketAddress} " +
                    "remote=${formatEndpoint(address, port)} reason=${error.message}",
                error,
            )
            inFlightSockets.remove(socket)
            socket.close()
            val label = if (family == DirectFamily.IPV6) "TCP6" else "TCP4"
            throw IllegalStateException("$label direct connection failed; no fallback is allowed", error)
        }
    }

    private fun acceptPassiveDirect(family: DirectFamily): Socket {
        val listener = synchronized(directListeners) { directListeners[family] }
            ?: throw IllegalStateException("No passive direct listener is available")
        listener.soTimeout = 1_000
        return listener.accept().also {
            if (!protect(it)) {
                it.close()
                throw IllegalStateException("Cannot protect the accepted direct socket from the VPN")
            }
            it.sendBufferSize = DIRECT_SOCKET_BUFFER_BYTES
            it.receiveBufferSize = DIRECT_SOCKET_BUFFER_BYTES
            it.tcpNoDelay = false
            it.soTimeout = 15_000
            Log.i(
                TAG,
                "Direct accept succeeded peer_id=pending family=${familyLabel(family)} " +
                    "socket=${socketToken(it)} initiated=false local=${it.localSocketAddress} " +
                    "remote=${it.remoteSocketAddress}",
            )
        }
    }

    private fun prepareDirectListener(localPort: Int, family: DirectFamily) {
        synchronized(directListeners) {
            if (directListeners[family]?.isClosed == false) return
            val listener = ServerSocket()
            try {
                listener.reuseAddress = true
                val wildcard = if (family == DirectFamily.IPV6) InetAddress.getByName("::") else InetAddress.getByName("0.0.0.0")
                listener.bind(InetSocketAddress(wildcard, localPort), 32)
                directListeners[family] = listener
                Log.i(
                    TAG,
                    "Direct listener ready family=${familyLabel(family)} " +
                        "socket=${socketToken(listener)} local=${listener.localSocketAddress}",
                )
            } catch (error: Exception) {
                Log.e(TAG, "Cannot listen for passive ${family.name} direct connections on port $localPort", error)
                listener.close()
            }
        }
    }

    private fun closeDirectListeners() {
        synchronized(directListeners) {
            directListeners.values.forEach(::closeQuietly)
            directListeners.clear()
        }
    }

    private fun closeDirectListener(family: DirectFamily) {
        synchronized(directListeners) {
            directListeners.remove(family)?.let { listener ->
                Log.i(
                    TAG,
                    "Direct listener closed family=${familyLabel(family)} " +
                        "socket=${socketToken(listener)} local=${listener.localSocketAddress} " +
                        "reason=simultaneous-open",
                )
                closeQuietly(listener)
            }
        }
    }

    private fun negotiateAddresses(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        config: VpnConfiguration,
    ): Pair<com.tcppeer.android.protocol.DhcpOffer, com.tcppeer.android.protocol.SlaacConfiguration> {
        val transactionId = AddressNegotiation.transactionId()
        TcpPeerProtocol.writeData(output, AddressNegotiation.dhcpDiscover(config.peerId, transactionId))
        output.flush()
        TcpPeerProtocol.writeData(output, AddressNegotiation.routerSolicitation())
        output.flush()
        var offer: com.tcppeer.android.protocol.DhcpOffer? = null
        var acknowledged: com.tcppeer.android.protocol.DhcpOffer? = null
        var slaac: com.tcppeer.android.protocol.SlaacConfiguration? = null
        val deadlineNanos = System.nanoTime() + 15_000_000_000L
        var receivedFrames = 0
        while (System.nanoTime() < deadlineNanos) {
            val packet = TcpPeerProtocol.readData(input, output)
            receivedFrames += 1
            var packetKind = "unrecognized"
            if (offer == null) {
                offer = AddressNegotiation.parseDhcpOffer(packet, transactionId)
                if (offer != null) {
                    packetKind = "DHCP-OFFER"
                    TcpPeerProtocol.writeData(output, AddressNegotiation.dhcpRequest(config.peerId, offer!!))
                    output.flush()
                }
            }
            if (acknowledged == null) {
                acknowledged = AddressNegotiation.parseDhcpAck(packet, transactionId)
                if (acknowledged != null) packetKind = "DHCP-ACK"
            }
            if (slaac == null) {
                slaac = AddressNegotiation.parseRouterAdvertisement(
                    packet, ConfigurationStore(this).slaacInterfaceId(),
                )
                if (slaac != null) packetKind = "RA"
            }
            Log.i(TAG, "Address negotiation received $packetKind (${packet.size} bytes); offer=${offer != null} ack=${acknowledged != null} ra=${slaac != null}")
            if (acknowledged != null && slaac != null) return acknowledged!! to slaac!!
        }
        throw ProtocolException(
            "Address negotiation timed out after $receivedFrames frames " +
                "(offer=${offer != null}, ack=${acknowledged != null}, ra=${slaac != null})",
        )
    }

    private fun establishTunnel(
        config: VpnConfiguration,
        ipv4: com.tcppeer.android.protocol.DhcpOffer,
        ipv6: com.tcppeer.android.protocol.SlaacConfiguration,
    ): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("TCPeer")
            .setMtu(config.mtu)
            .setBlocking(true)
            .addAddress(ipv4.address, ipv4.prefixLength)
            .addAddress(ipv6.address, ipv6.prefixLength)
        if (!config.useExitNode) {
            builder
                .addRoute(networkAddress(ipv4.address, ipv4.prefixLength), ipv4.prefixLength)
                .addRoute(ipv6.prefix, ipv6.prefixLength)
        }
        preserveLocalNetworkAccess(builder, ipv4.address, ipv6.address, config.useExitNode)
        if (config.useExitNode) {
            (ipv4.dns + ipv6.dns).distinctBy { it.hostAddress }.forEach(builder::addDnsServer)
        }
        return builder.establish()
    }

    private fun preserveLocalNetworkAccess(
        builder: Builder,
        tunnelIpv4: InetAddress,
        tunnelIpv6: InetAddress,
        useExitNode: Boolean,
    ) {
        val properties = connectivityManager.activeNetwork?.let(connectivityManager::getLinkProperties)
        val excluded = properties?.linkAddresses.orEmpty()
            .filterNot { it.address.isAnyLocalAddress || it.address.isLoopbackAddress }
            // IpPrefix(InetAddress, int) was added only in API 33. Keep the
            // common representation API-26-safe and construct IpPrefix only
            // inside the guarded Android 13+ branch below.
            .map { RoutePrefix(it.address.address, it.prefixLength) }
            // An overlapping physical prefix cannot be excluded without also
            // bypassing the TCPeer overlay. In that ambiguous case VPN wins.
            .filterNot { contains(it, tunnelIpv4.address) || contains(it, tunnelIpv6.address) }
            .distinctBy { "${InetAddress.getByAddress(it.address).hostAddress}/${it.prefixLength}" }
        if (useExitNode) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                builder.addRoute("0.0.0.0", 0).addRoute("::", 0)
                excluded.forEach {
                    builder.excludeRoute(IpPrefix(InetAddress.getByAddress(it.address), it.prefixLength))
                }
            } else {
                var routes = listOf(
                    RoutePrefix(ByteArray(4), 0),
                    RoutePrefix(ByteArray(16), 0),
                )
                excluded.forEach { prefix ->
                    routes = routes.flatMap { route -> subtractPrefix(route, prefix) }
                }
                routes.forEach { builder.addRoute(InetAddress.getByAddress(it.address), it.prefixLength) }
            }
        }
        if (excluded.isNotEmpty()) {
            Log.i(
                TAG,
                "Keeping directly connected networks outside TCPeer: " + excluded.joinToString {
                    "${InetAddress.getByAddress(it.address).hostAddress}/${it.prefixLength}"
                },
            )
        }
    }


    private fun subtractPrefix(route: RoutePrefix, blocked: RoutePrefix): List<RoutePrefix> {
        if (route.address.size != blocked.address.size || !contains(route, blocked.address)) return listOf(route)
        if (blocked.prefixLength <= route.prefixLength) return emptyList()
        val nextLength = route.prefixLength + 1
        val left = RoutePrefix(route.address.copyOf(), nextLength)
        val rightBytes = route.address.copyOf()
        val byteIndex = route.prefixLength / 8
        val bitMask = 1 shl (7 - route.prefixLength % 8)
        rightBytes[byteIndex] = (rightBytes[byteIndex].toInt() or bitMask).toByte()
        val right = RoutePrefix(rightBytes, nextLength)
        return subtractPrefix(left, blocked) + subtractPrefix(right, blocked)
    }

    private fun contains(prefix: RoutePrefix, address: ByteArray): Boolean {
        if (prefix.address.size != address.size) return false
        val fullBytes = prefix.prefixLength / 8
        for (index in 0 until fullBytes) {
            if (prefix.address[index] != address[index]) return false
        }
        val remaining = prefix.prefixLength % 8
        if (remaining == 0) return true
        val mask = (0xff shl (8 - remaining)) and 0xff
        return (prefix.address[fullBytes].toInt() and mask) == (address[fullBytes].toInt() and mask)
    }

    private suspend fun exchangePackets(
        tunInput: FileInputStream,
        directInput: java.io.InputStream,
        directOutput: java.io.OutputStream,
        overlayIpv6: Inet6Address,
        primaryPeerId: String,
        peerOutputs: ConcurrentHashMap<String, java.io.OutputStream>,
        tunPackets: TunPacketSink,
    ) = coroutineScope {
        val lastDataPlaneRx = AtomicLong(System.nanoTime())
        pendingTppPings.clear()
        val pendingTxBytes = AtomicLong(0)
        val pendingRxBytes = AtomicLong(0)
        val outputPending = AtomicBoolean(false)
        val outputFlusher = launch(Dispatchers.IO) {
            while (true) {
                delay(DIRECT_FLUSH_INTERVAL_MS)
                if (outputPending.getAndSet(false)) {
                    synchronized(directOutput) { directOutput.flush() }
                }
            }
        }
        val statistics = launch {
            while (true) {
                delay(250)
                val tx = pendingTxBytes.getAndSet(0)
                val rx = pendingRxBytes.getAndSet(0)
                if (tx != 0L || rx != 0L) {
                    TcpPeerRuntime.update { it.copy(txBytes = it.txBytes + tx, rxBytes = it.rxBytes + rx) }
                }
            }
        }
        /*
         * Simple RAW-IP TX path.
         *
         * Deliberately mirrors the Python implementation:
         *
         *   TUN read -> buffered TCP write -> next TUN read
         *
         * Adjacent packets are coalesced for at most two milliseconds so the
         * kernel can use larger TCP segments/TSO rather than receiving one
         * small write per VPN packet. No per-packet ByteArray copy or TCPeer
         * framing is introduced; IPv4/IPv6 headers still carry boundaries.
         */
        val tunToPeer = launch(Dispatchers.IO) {
            val buffer = ByteArray(65_535)
            var byteBatch = 0L
            var packetBatch = 0
            val tunPoll = StructPollfd().apply {
                fd = tunInput.fd
                events = OsConstants.POLLIN.toShort()
            }

            while (currentCoroutineContext().isActive) {
                tunPoll.revents = 0
                if (Os.poll(arrayOf(tunPoll), TUN_POLL_MS) == 0) continue
                if (!currentCoroutineContext().isActive) break
                val terminalEvents = OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL
                if ((tunPoll.revents.toInt() and terminalEvents) != 0) break
                if ((tunPoll.revents.toInt() and OsConstants.POLLIN) == 0) continue
                val count = tunInput.read(buffer)

                if (count < 0) break
                if (count == 0) continue

                val destination = packetDestination(buffer, count)
                val directPeer = TcpPeerRuntime.state.value.devices.firstOrNull { device ->
                    device.overlayIpv4 == destination || device.overlayIpv6.substringBefore('%') == destination
                }?.peerId
                val selectedOutput = directPeer?.let(peerOutputs::get)
                if (directPeer != null && selectedOutput == null) {
                    Log.d(TAG, "Dropping packet for $directPeer until its direct connection is ready")
                    continue
                }
                val output = selectedOutput ?: directOutput
                val flushImmediately = output === directOutput && isTcpControlPacket(buffer, count)
                synchronized(output) {
                    // Raw IP uses the packet's own IPv4/IPv6 length as its
                    // boundary in the TCP byte stream.  A TUN read may carry
                    // trailing kernel/offload bytes, so sending `count`
                    // directly can desynchronise every subsequent packet and
                    // leave the receiver blocked while TCP stays ESTABLISHED.
                    TcpPeerProtocol.writeData(output, buffer, 0, count)
                    if (flushImmediately) {
                        output.flush()
                    }
                }
                if (output === directOutput && !flushImmediately) {
                    outputPending.set(true)
                }

                byteBatch += count
                packetBatch++
                if (packetBatch >= DATA_PLANE_ACCOUNTING_BATCH) {
                    pendingTxBytes.addAndGet(byteBatch)
                    byteBatch = 0
                    packetBatch = 0
                }
            }
        }
        val pingRequests = launch(Dispatchers.IO) {
            TcpPeerRuntime.pingTarget.collectLatest { request ->
                pendingTppPings.clear()
                if (request == null) return@collectLatest
                while (true) {
                    val output = peerOutputs[request.peerId]
                    if (output == null) {
                        TcpPeerRuntime.recordPing(request.peerId, null)
                        delay(1_000)
                        continue
                    }
                    val identifier = nextTppPingId.incrementAndGet()
                    val sentAt = System.nanoTime()
                    pendingTppPings[identifier] = request.peerId to sentAt
                    synchronized(output) {
                        TcpPeerProtocol.writeTppControl(output, "TPP-PING", identifier, sentAt)
                    }
                    launch {
                        delay(3_000)
                        pendingTppPings.remove(identifier)?.let { (peerId, _) ->
                            TcpPeerRuntime.recordPing(peerId, null)
                        }
                    }
                    delay(1_000)
                }
            }
        }
        val dataPlaneKeepalive = launch(Dispatchers.IO) {
            while (true) {
                delay(15_000)
                if (System.nanoTime() - lastDataPlaneRx.get() >= 45_000_000_000L) {
                    Log.w(TAG, "Primary data-plane TPCP keepalive timeout; closing direct stream")
                    closeQuietly(directInput)
                    return@launch
                }
                synchronized(directOutput) {
                    TcpPeerProtocol.writeDataPlaneControl(directOutput, "KEEPALIVE")
                }
            }
        }
        val peerToTun = launch(Dispatchers.IO) {
            val packet = ByteArray(65_535)
            var byteBatch = 0L
            var packetBatch = 0
            while (true) {
                val packetLength = TcpPeerProtocol.readDataInto(
                    directInput,
                    packet,
                    directOutput,
                    { lastDataPlaneRx.set(System.nanoTime()) },
                ) { command, identifier, _ ->
                    handleTppControl(primaryPeerId, command, identifier)
                }
                byteBatch += packetLength
                packetBatch++
                if (packetBatch >= DATA_PLANE_ACCOUNTING_BATCH) {
                    pendingRxBytes.addAndGet(byteBatch)
                    lastDataPlaneRx.lazySet(System.nanoTime())
                    byteBatch = 0
                    packetBatch = 0
                }
                val replyBytes = processInboundPacket(
                    packet, "primary", overlayIpv6, directOutput, tunPackets, packetLength,
                )
                if (replyBytes > 0) {
                    outputPending.set(true)
                    pendingTxBytes.addAndGet(replyBytes.toLong())
                }
            }
        }
        try {
            // Either direction ending means this direct session is no longer
            // usable. Waiting for TUN->peer first hid receiver failures because
            // the TUN read normally blocks forever, leaving the UI Connected
            // after peer->TUN and TPP had already died.
            select<Unit> {
                tunToPeer.onJoin { }
                peerToTun.onJoin { }
            }
        } finally {
            tunToPeer.cancel()
            peerToTun.cancel()
            pingRequests.cancel()
            dataPlaneKeepalive.cancel()
            statistics.cancel()
            outputFlusher.cancel()
            pendingTppPings.clear()
            synchronized(directOutput) {
                runCatching { directOutput.flush() }
            }
            closeQuietly(tunInput)
        }
    }

    private fun packetDestination(packet: ByteArray, length: Int): String? {
        if (length < 1) return null
        return runCatching {
            when (packet[0].toInt().ushr(4)) {
                4 -> if (length >= 20) InetAddress.getByAddress(packet.copyOfRange(16, 20)).hostAddress else null
                6 -> if (length >= 40) InetAddress.getByAddress(packet.copyOfRange(24, 40)).hostAddress else null
                else -> null
            }
        }.getOrNull()
    }

    private fun isTcpControlPacket(packet: ByteArray, length: Int): Boolean {
        if (length < 1) return false
        val tcpOffset: Int
        val packetLength: Int
        when (packet[0].toInt().ushr(4)) {
            4 -> {
                if (length < 40 || packet[9].toInt() and 0xff != 6) return false
                tcpOffset = (packet[0].toInt() and 0x0f) * 4
                packetLength = ((packet[2].toInt() and 0xff) shl 8) or
                    (packet[3].toInt() and 0xff)
            }
            6 -> {
                if (length < 60 || packet[6].toInt() and 0xff != 6) return false
                tcpOffset = 40
                packetLength = 40 + (((packet[4].toInt() and 0xff) shl 8) or
                    (packet[5].toInt() and 0xff))
            }
            else -> return false
        }
        if (tcpOffset + 20 > length || packetLength > length) return false
        val tcpHeaderLength = (packet[tcpOffset + 12].toInt().ushr(4) and 0x0f) * 4
        if (tcpHeaderLength < 20 || tcpOffset + tcpHeaderLength > packetLength) return false
        return tcpOffset + tcpHeaderLength == packetLength
    }

    private fun connectionKey(socket: Socket): String = listOf(
        socket.localSocketAddress?.toString().orEmpty(),
        socket.remoteSocketAddress?.toString().orEmpty(),
    ).sorted().joinToString("|")

    private fun formatSocketEndpoint(socket: Socket): String {
        val address = socket.inetAddress ?: return "-"
        return formatEndpoint(address, socket.port)
    }

    private fun socketToken(socket: Any): String =
        "${socket.javaClass.simpleName}@${Integer.toHexString(System.identityHashCode(socket))}"

    private fun socketFamily(socket: Socket): String =
        if (socket.inetAddress is Inet6Address) "TCP6" else "TCP4"

    private fun familyLabel(family: DirectFamily): String =
        if (family == DirectFamily.IPV6) "TCP6" else "TCP4"

    private fun networkAddress(address: Inet4Address, prefixLength: Int): InetAddress {
        val bytes = address.address
        for (index in bytes.indices) {
            val bits = (prefixLength - index * 8).coerceIn(0, 8)
            val mask = if (bits == 0) 0 else (0xFF shl (8 - bits)) and 0xFF
            bytes[index] = (bytes[index].toInt() and mask).toByte()
        }
        return InetAddress.getByAddress(bytes)
    }

    private fun disconnect() {
        disconnectRequested.set(true)
        connectionGeneration.incrementAndGet()
        val stoppedJob = connectionJob
        connectionJob = null
        stoppedJob?.cancel()
        closeResources()
        TcpPeerRuntime.replace(VpnRuntimeState())
        TcpPeerRuntime.setServiceActive(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Disconnect is authoritative. stopSelfResult(startId) can refuse to
        // stop when Android has delivered a newer start request,
        // leaving Android's VPN network and routes registered even though all
        // TCPeer sockets are already closed and the UI says Disconnected.
        stopAfterConnectivityRefresh()
    }

    private fun stopAfterConnectivityRefresh() {
        if (stopRefreshStarted.compareAndSet(false, true)) {
            refreshConnectivityThenStop()
        }
    }

    /**
     * ConnectivityService can retain its system VPN notification after the
     * TUN and routes are already gone. It refreshes as soon as another
     * network-aware app registers an Internet request. Issue that same non-VPN
     * request before stopping the service on every supported Android version.
     */
    private fun refreshConnectivityThenStop() {
        val finished = AtomicBoolean(false)
        lateinit var callback: ConnectivityManager.NetworkCallback

        fun finish() {
            if (!finished.compareAndSet(false, true)) return
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
            stopSelf()
        }

        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = finish()
            override fun onUnavailable() = finish()
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching {
            connectivityManager.requestNetwork(request, callback, NETWORK_REFRESH_TIMEOUT_MS)
        }.onFailure { finish() }
    }

    /**
     * Blocking socket and VPN setup calls do not observe coroutine cancellation
     * until they return. Publish each newly created resource atomically with a
     * generation check so a cancelled API 28 attempt cannot resurrect the VPN
     * after ACTION_DISCONNECT has already closed the previous resources.
     */
    @Synchronized
    private fun publishCoordinatorSocket(generation: Long, socket: Socket): Socket {
        inFlightSockets.remove(socket)
        if (disconnectRequested.get() || connectionGeneration.get() != generation) {
            closeQuietly(socket)
            throw CancellationException("Coordinator socket belongs to a cancelled connection")
        }
        coordinatorSocket = socket
        return socket
    }

    @Synchronized
    private fun publishDirectSocket(generation: Long, socket: Socket): Socket {
        inFlightSockets.remove(socket)
        if (disconnectRequested.get() || connectionGeneration.get() != generation) {
            closeQuietly(socket)
            throw CancellationException("Direct socket belongs to a cancelled connection")
        }
        directSocket = socket
        return socket
    }

    @Synchronized
    private fun publishTunnel(generation: Long, descriptor: ParcelFileDescriptor): TunStreams {
        if (disconnectRequested.get() || connectionGeneration.get() != generation) {
            closeQuietly(descriptor)
            throw CancellationException("VPN interface belongs to a cancelled connection")
        }
        // Give each stream its own duplicated descriptor. Sharing the exact
        // FileDescriptor object lets a stream close invalidate that object
        // before ParcelFileDescriptor.close() reaches the owning TUN fd on
        // Android 9's libcore implementation.
        val inputDescriptor = ParcelFileDescriptor.dup(descriptor.fileDescriptor)
        val outputDescriptor = try {
            ParcelFileDescriptor.dup(descriptor.fileDescriptor)
        } catch (error: Exception) {
            closeQuietly(inputDescriptor)
            closeQuietly(descriptor)
            throw error
        }
        val input = ParcelFileDescriptor.AutoCloseInputStream(inputDescriptor)
        val output = ParcelFileDescriptor.AutoCloseOutputStream(outputDescriptor)
        tunnel = descriptor
        tunnelInput = input
        tunnelOutput = output
        return TunStreams(input, output)
    }

    @Synchronized
    private fun closeResources() {
        TcpPeerRuntime.stopContinuousPing()
        // API 28 keeps the VPN file descriptor referenced by streams created
        // from ParcelFileDescriptor.fileDescriptor. Close those owners first;
        // closing only the ParcelFileDescriptor can leave Samsung's VPN icon
        // and interface alive after TCPeer reports Disconnected.
        closeQuietly(tunnelInput)
        tunnelInput = null
        closeQuietly(tunnelOutput)
        tunnelOutput = null
        closeQuietly(tunnel)
        tunnel = null
        closeQuietly(directSocket)
        directSocket = null
        meshSockets.values.forEach(::closeQuietly)
        meshSockets.clear()
        meshSocketKeys.clear()
        meshEndpoints.clear()
        meshCommitted.clear()
        meshConnecting.clear()
        meshPunchActive.clear()
        meshReadySentAt.clear()
        inFlightSockets.forEach(::closeQuietly)
        inFlightSockets.clear()
        closeDirectListeners()
        closeQuietly(coordinatorSocket)
        coordinatorSocket = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.notification_channel_description) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(status: ConnectionStatus): Notification {
        val activityIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val disconnectIntent = PendingIntent.getService(
            this, 1, Intent(this, TcpPeerVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("TCPeer")
            .setContentText(status.label)
            .setOngoing(status != ConnectionStatus.NO_DIRECT_CONNECTION)
            .setContentIntent(activityIntent)
            .addAction(0, "Disconnect", disconnectIntent)
            .build()
    }

    private fun showForeground(status: ConnectionStatus) {
        val value = notification(status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, value, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, value)
        }
    }

    private fun updateNotification(status: ConnectionStatus) {
        val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (permitted) {
            runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification(status)) }
        }
    }

    private fun formatEndpoint(address: InetAddress, port: Int): String =
        if (address is Inet6Address) "[${address.hostAddress?.substringBefore('%')}]:$port" else "${address.hostAddress}:$port"

    private fun closeQuietly(value: Closeable?) {
        runCatching { value?.close() }
    }

    companion object {
        const val ACTION_CONNECT = "com.tcppeer.android.CONNECT"
        const val ACTION_DISCONNECT = "com.tcppeer.android.DISCONNECT"
        const val ACTION_RENAME_DEVICE = "com.tcppeer.android.RENAME_DEVICE"
        const val EXTRA_DEVICE_NAME = "device_name"
        private const val CHANNEL_ID = "tcppeer_vpn"
        private const val NOTIFICATION_ID = 7443
        private const val COORDINATOR_TIMEOUT_MS = 35_000
        private const val DIRECT_SOCKET_BUFFER_BYTES = 8 * 1024 * 1024
        private const val DIRECT_STREAM_BUFFER_BYTES = 1024 * 1024
        private const val DIRECT_FLUSH_INTERVAL_MS = 2L
        private const val TUN_POLL_MS = 250
        private const val NETWORK_REFRESH_TIMEOUT_MS = 1_000
        private const val DATA_PLANE_ACCOUNTING_BATCH = 64
        private const val DEVICE_REFRESH_INTERVAL_MS = 5_000L
        private const val MESH_PUNCH_RETRY_MS = 5_000L
        private const val TAG = "TCPeerVpnService"
    }
}
