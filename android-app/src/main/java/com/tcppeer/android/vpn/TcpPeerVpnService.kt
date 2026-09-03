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
import android.os.Build
import android.os.ParcelFileDescriptor
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
import com.tcppeer.android.protocol.TppProtocol
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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

private data class PublicEndpoint(val address: String, val port: Int)
private data class RoutePrefix(val address: ByteArray, val prefixLength: Int)

class TcpPeerVpnService : VpnService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectionJob: Job? = null
    private var coordinatorSocket: Socket? = null
    private var directSocket: Socket? = null
    private val meshSockets = ConcurrentHashMap<String, Socket>()
    private val meshSocketKeys = ConcurrentHashMap<String, String>()
    private val meshEndpoints = ConcurrentHashMap<String, String>()
    private val meshAdoptionLock = Any()
    private val meshConnecting = ConcurrentHashMap.newKeySet<String>()
    private val meshPunchActive = ConcurrentHashMap.newKeySet<String>()
    private val nextTppPingId = AtomicLong(System.nanoTime())
    private val pendingTppPings = ConcurrentHashMap<Long, Pair<String, Long>>()
    private val directListeners = mutableMapOf<DirectFamily, ServerSocket>()
    private var tunnel: ParcelFileDescriptor? = null
    private val disconnectRequested = AtomicBoolean(false)
    private val restartRequested = AtomicBoolean(false)
    private lateinit var connectivityManager: ConnectivityManager
    @Volatile private var underlyingNetworkSignature: String? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
            val signature = buildString {
                append(network.toString()).append('|')
                linkProperties.linkAddresses.map { it.toString() }.sorted().forEach { append(it).append(',') }
            }
            val previous = underlyingNetworkSignature
            underlyingNetworkSignature = signature
            val established = TcpPeerRuntime.state.value.status in setOf(
                ConnectionStatus.TCP4_DIRECT,
                ConnectionStatus.TCP6_DIRECT,
            )
            if (previous != null && previous != signature && connectionJob?.isActive == true && established) {
                restartForNetworkChange()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> disconnect()
            else -> if (connectionJob?.isActive != true) connect()
        }
        return Service.START_NOT_STICKY
    }

    override fun onRevoke() {
        disconnect()
        super.onRevoke()
    }

    override fun onDestroy() {
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        closeResources()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun connect() {
        disconnectRequested.set(false)
        showForeground(ConnectionStatus.CONNECTING)
        TcpPeerRuntime.replace(VpnRuntimeState(
            status = ConnectionStatus.CONNECTING,
            detail = "Authenticating with the coordinator over cleartext TCP.",
        ))
        connectionJob = serviceScope.launch {
            try {
                val config = ConfigurationStore(this@TcpPeerVpnService).load().also { it.validate() }
                runConnection(config)
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
                closeResources()

                val networkRestart = restartRequested.getAndSet(false)

                if (disconnectRequested.get()) {
                    connectionJob = null
                    TcpPeerRuntime.replace(VpnRuntimeState())
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
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
                TcpPeerProtocol.writeControl(output, ControlMessage("PEER-INFO", linkedMapOf(
                    "Action" to "Client-Error",
                    "Detail" to detail,
                )))
            }
        }
    }

    private suspend fun runConnection(config: VpnConfiguration) = withContext(Dispatchers.IO) {
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
        val activeLinkProperties = physicalNetwork?.let(connectivityManager::getLinkProperties)
        val activeAddresses = activeLinkProperties?.linkAddresses?.map { it.address }
        val (localIpv4, localIpv6) = TransportPolicy.localAddresses(activeAddresses)
        Log.i(
            TAG,
            "Endpoint discovery network=$physicalNetwork IPv4=${localIpv4.joinToString { it.hostAddress.orEmpty() }} " +
                "IPv6=${localIpv6.joinToString { it.hostAddress?.substringBefore('%').orEmpty() }}",
        )
        val coordinator = openCoordinator(config, localIpv6.isNotEmpty()).also { coordinatorSocket = it }
        val controlInput = coordinator.getInputStream()
        val controlOutput = coordinator.getOutputStream()

        updateConnecting("Authenticating with the coordinator over cleartext TCP.")
        TcpPeerProtocol.writeControl(controlOutput, ControlMessage("AUTH", linkedMapOf(
            "Network" to config.network,
            "Peer-ID" to config.peerId,
        )))
        val challenge = TcpPeerProtocol.readControl(controlInput)
        if (challenge.command != "AUTH-CHALLENGE") {
            throw ProtocolException(challenge.field("Reason") ?: "Coordinator did not issue an authentication challenge")
        }
        TcpPeerProtocol.writeControl(controlOutput, ControlMessage("AUTH-PROOF", linkedMapOf(
            "Proof" to AuthProof.create(config.secret, config.network, config.peerId, challenge.field("Nonce").orEmpty()),
        )))
        val authentication = TcpPeerProtocol.readControl(controlInput)
        if (authentication.command != "AUTH-OK") {
            throw ProtocolException(authentication.field("Reason") ?: "Coordinator authentication failed")
        }
        val observed = TcpPeerProtocol.readControl(controlInput)
        if (observed.command != "ENDPOINT-INFO") throw ProtocolException("Coordinator did not report the TCP mapping")

        val endpointIpv4 = if (localIpv4.isNotEmpty()) queryPublicEndpoint(config, DirectFamily.IPV4) else null
        val endpointIpv6 = if (localIpv6.isNotEmpty()) queryPublicEndpoint(config, DirectFamily.IPV6) else null
        val directPublicIpv4 = localIpv4.firstOrNull(TransportPolicy::isPublicIpv4)?.hostAddress
        val directPublicIpv6 = localIpv6.firstOrNull(TransportPolicy::isPublicIpv6)?.hostAddress?.substringBefore('%')
        val advertisedIpv4 = endpointIpv4?.address ?: directPublicIpv4.orEmpty()
        val advertisedIpv6 = endpointIpv6?.address ?: directPublicIpv6.orEmpty()
        val mappedIpv4Port = endpointIpv4?.port ?: if (directPublicIpv4 != null) config.directPort else null
        val mappedIpv6Port = endpointIpv6?.port ?: if (directPublicIpv6 != null) config.directPort else null

        TcpPeerProtocol.writeControl(controlOutput, ControlMessage("REGISTER", linkedMapOf(
            "Peer-ID" to config.peerId,
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
        val registration = TcpPeerProtocol.readControl(controlInput)
        if (registration.command != "ENDPOINT-INFO") throw ProtocolException("Coordinator did not accept registration")
        TcpPeerProtocol.writeControl(controlOutput, ControlMessage("PEER-INFO", mapOf("Action" to "List")))
        readDeviceList(controlInput)
        val targetPeerId = config.targetPeerId
        var family: DirectFamily
        var address: InetAddress
        var peerPort: Int
        var direct: Socket

        while (true) {
            updateConnecting("Waiting for $targetPeerId to become ready.")
            TcpPeerProtocol.writeControl(controlOutput, ControlMessage("PUNCH-READY", mapOf(
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
                direct = openDirect(
                    address,
                    peerPort,
                    config.directPort,
                    family,
                ).also { directSocket = it }

                break
            } catch (error: Exception) {
                directSocket = null

                updateConnecting(
                    "Attempt to direct connection timed out; retrying"
                )

                Log.w(
                    TAG,
                    "Direct connection attempt failed; requesting a new punch",
                    error,
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

        TcpPeerProtocol.writeControl(controlOutput, ControlMessage("PEER-INFO", linkedMapOf(
            "Action" to "Overlay-Update",
            "Overlay-IPv4" to (addresses.first.address.hostAddress ?: ""),
            "Overlay-IPv6" to (addresses.second.address.hostAddress ?: ""),
        )))

        Log.i(TAG, "Overlay-Update sent to coordinator")
        val descriptor = establishTunnel(config, addresses.first, addresses.second)
            ?: throw IllegalStateException("Android refused to establish the VPN interface")
        tunnel = descriptor
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
            val peerOutputs = ConcurrentHashMap<String, java.io.OutputStream>()
            peerOutputs[targetPeerId] = directOutput
            meshSockets[targetPeerId] = direct
            meshSocketKeys[targetPeerId] = connectionKey(direct)
            meshEndpoints[targetPeerId] = formatSocketEndpoint(direct)
            updateConnectedUsing(
                targetPeerId,
                formatSocketEndpoint(direct),
                familyLabel(family),
            )
            val tunOutput = FileOutputStream(descriptor.fileDescriptor)
            prepareDirectListener(config.directPort, family)
            val passiveAcceptJob = launch(Dispatchers.IO) {
                acceptMeshConnections(
                    family,
                    config, advertisedIpv4, advertisedIpv6,
                    addresses.second.address, peerOutputs, tunOutput,
                )
            }
            val coordinatorControlJob = launch(Dispatchers.IO) {
                val devices = mutableListOf<NetworkDevice>()
                var listInProgress = false

                while (true) {
                    if (!listInProgress) {
                        devices.clear()
                        listInProgress = true
                        TcpPeerProtocol.writeControl(
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
                                    online = message.field("Online") == "yes",
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
                                        meshConnecting.add(device.peerId)
                                    ) {
                                        synchronized(controlOutput) {
                                            TcpPeerProtocol.writeControl(
                                                controlOutput,
                                                ControlMessage("PUNCH-READY", mapOf("Peer-ID" to device.peerId)),
                                            )
                                        }
                                    }
                                }

                                "List-End" -> {
                                    TcpPeerRuntime.update { state ->
                                        state.copy(
                                            devices = devices.sortedWith(
                                                compareByDescending<NetworkDevice> { it.online }
                                                    .thenBy { it.peerId },
                                            ),
                                        )
                                    }
                                    listInProgress = false
                                    delay(DEVICE_REFRESH_INTERVAL_MS)
                                }

                                "Punch-Request" -> {
                                    synchronized(controlOutput) {
                                        TcpPeerProtocol.writeControl(
                                            controlOutput,
                                            ControlMessage(
                                                "PUNCH-READY",
                                                mapOf("Peer-ID" to (message.field("Peer-ID") ?: targetPeerId)),
                                            ),
                                        )
                                    }
                                }

                            }

                            "PUNCH-GO" -> {
                                val punchPeer = message.field("Peer-ID")
                                if (punchPeer != null && meshPunchActive.add(punchPeer)) {
                                    launch {
                                        try {
                                            connectMeshPeer(
                                                message, config, advertisedIpv4, advertisedIpv6,
                                                addresses.second.address, peerOutputs, tunOutput,
                                            )
                                        } finally {
                                            meshPunchActive.remove(punchPeer)
                                        }
                                    }
                                }
                            }

                            "PING", "KEEPALIVE" -> {
                                TcpPeerProtocol.writeControl(
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
                                Log.w(TAG, "Mesh request rejected by coordinator: $reason")
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                        // Keep waiting for the current list. Do not start
                        // another List request until List-End arrives.
                    }
                }
            }

            try {
                exchangePackets(
                    descriptor,
                    BufferedInputStream(directInput, DIRECT_STREAM_BUFFER_BYTES),
                    directOutput,
                    addresses.second.address,
                    peerOutputs,
                    tunOutput,
                )
            } finally {
                closeDirectListeners()
                meshSockets.values.forEach(::closeQuietly)
                coordinatorControlJob.cancel()
                passiveAcceptJob.cancel()
                closeQuietly(tunOutput)
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
        tunOutput: FileOutputStream,
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
            // established raw-IP stream.  Leaving 15 seconds here caused every
            // quiet mesh connection to be closed and punched again forever.
            socket.soTimeout = 0
            if (!adoptMeshSocket(peerId, socket, output, peerOutputs, initiated = true)) return
            try {
                while (true) {
                    val packet = TcpPeerProtocol.readData(input)
                    processInboundPacket(packet, peerId, overlayIpv6, output, tunOutput)
                }
            } finally {
                peerOutputs.remove(peerId, output)
                if (meshSockets.remove(peerId, socket)) {
                    meshSocketKeys.remove(peerId, connectionKey(socket))
                    meshEndpoints.remove(peerId)
                    updateConnectedUsing(peerId, "-", null)
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Direct mesh connection to $peerId closed", error)
        } finally {
            meshConnecting.remove(peerId)
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
        tunOutput: FileOutputStream,
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
                    overlayIpv6, peerOutputs, tunOutput,
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
        tunOutput: FileOutputStream,
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
            while (true) {
                val packet = TcpPeerProtocol.readData(input)
                processInboundPacket(packet, peerId, overlayIpv6, output, tunOutput)
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
                updateConnectedUsing(peerId, "-", null)
            }
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
            if (current != null && currentKey != null && key >= currentKey) {
                Log.i(
                    TAG,
                    "Mesh socket rejected peer_id=$peerId family=${socketFamily(socket)} " +
                        "socket=${socketToken(socket)} initiated=$initiated local=${socket.localSocketAddress} " +
                        "remote=${socket.remoteSocketAddress} reason=deterministic-loser " +
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
        return true
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
        tunOutput: FileOutputStream,
    ): Int {
        if (AddressNegotiation.isRouterAdvertisement(packet)) return 0
        val tpp = TppProtocol.parse(packet)
        return when (tpp?.type) {
            TppProtocol.ECHO_REQUEST -> {
                if (tpp.destination == overlayIpv6) {
                    val reply = TppProtocol.reply(tpp)
                    synchronized(output) { TcpPeerProtocol.writeData(output, reply) }
                    Log.d(TAG, "TPP reply sent directly to peer_id=$peerId identifier=${tpp.identifier}")
                    reply.size
                } else {
                    synchronized(tunOutput) { tunOutput.write(packet) }
                    0
                }
            }
            TppProtocol.ECHO_REPLY -> {
                pendingTppPings.remove(tpp.identifier)?.let { (pingPeerId, sentAt) ->
                    val latencyMillis = (System.nanoTime() - sentAt) / 1_000_000.0
                    TcpPeerRuntime.recordPing(pingPeerId, latencyMillis)
                    Log.d(TAG, "TPP reply received directly from peer_id=$peerId identifier=${tpp.identifier}")
                }
                0
            }
            else -> {
                synchronized(tunOutput) { tunOutput.write(packet) }
                0
            }
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
                            TcpPeerProtocol.writeControl(output, ControlMessage("PUNCH-READY", mapOf(
                                "Peer-ID" to targetPeerId,
                            )))
                        } else {
                            Log.i(
                                TAG,
                                "Deferring mesh punch for peer_id=$requestedPeer until the primary tunnel is ready",
                            )
                        }
                    }
                    "PING", "KEEPALIVE" -> TcpPeerProtocol.writeControl(output, ControlMessage("PONG"))
                    "AUTH-ERROR", "DISCONNECT" -> throw ProtocolException(message.field("Reason") ?: "Coordinator disconnected")
                    "ERROR" -> throw ProtocolException(message.field("Reason") ?: "Coordinator rejected the direct connection")
                }
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
                        compareByDescending<NetworkDevice> { it.online }.thenBy { it.peerId },
                    )) }
                    return
                }
                "Device" -> devices += NetworkDevice(
                    peerId = message.field("Peer-ID") ?: "unknown",
                    online = message.field("Online") == "yes",
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

    private fun openCoordinator(config: VpnConfiguration, preferIpv6: Boolean): Socket {
        val addresses = TransportPolicy.resolveTcpAddresses(config.coordinatorAddress).sortedBy {
            if (preferIpv6) if (it is Inet6Address) 0 else 1 else if (it is Inet4Address) 0 else 1
        }
        var lastError: Exception? = null
        addresses.forEach { address ->
            val family = if (address is Inet6Address) DirectFamily.IPV6 else DirectFamily.IPV4
            val socket = Socket()
            try {
                socket.reuseAddress = true
                val wildcard = if (address is Inet6Address) InetAddress.getByName("::") else InetAddress.getByName("0.0.0.0")
                // The coordinator is control-plane traffic. Binding it to the direct
                // port conflicts with the passive TCP listener on Android kernels.
                // The coordinator still observes the public IP; REGISTER explicitly
                // advertises the direct listener port.
                socket.bind(InetSocketAddress(wildcard, 0))
                if (!protect(socket)) throw IllegalStateException("Cannot protect the coordinator socket from the VPN")
                socket.connect(InetSocketAddress(address, config.coordinatorPort), 10_000)
                socket.tcpNoDelay = true
                socket.soTimeout = COORDINATOR_TIMEOUT_MS
                return socket
            } catch (error: Exception) {
                lastError = error
                socket.close()
            }
        }
        throw IllegalStateException("Cannot connect to the coordinator", lastError)
    }

    private fun queryPublicEndpoint(config: VpnConfiguration, family: DirectFamily): PublicEndpoint? {
        val addresses = TransportPolicy.resolveTcpAddresses(config.coordinatorAddress).filter {
            (family == DirectFamily.IPV6 && it is Inet6Address) ||
                (family == DirectFamily.IPV4 && it is Inet4Address)
        }
        addresses.forEach { address ->
            val socket = Socket()
            try {
                socket.reuseAddress = true
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
            } catch (_: Exception) {
                // The other IP family is optional.
            } finally {
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
        try {
            socket.reuseAddress = true
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
            socket.connect(InetSocketAddress(address, port), 12_000)
            socket.tcpNoDelay = true
            socket.soTimeout = 15_000
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
            it.tcpNoDelay = true
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
            val packet = TcpPeerProtocol.readData(input)
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
            .map { IpPrefix(it.address, it.prefixLength) }
            // An overlapping physical prefix cannot be excluded without also
            // bypassing the TCPeer overlay. In that ambiguous case VPN wins.
            .filterNot { it.contains(tunnelIpv4) || it.contains(tunnelIpv6) }
            .distinctBy(IpPrefix::toString)
        if (useExitNode) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                builder.addRoute("0.0.0.0", 0).addRoute("::", 0)
                excluded.forEach(builder::excludeRoute)
            } else {
                var routes = listOf(
                    RoutePrefix(ByteArray(4), 0),
                    RoutePrefix(ByteArray(16), 0),
                )
                excluded.forEach { prefix ->
                    val blocked = RoutePrefix(prefix.address.address, prefix.prefixLength)
                    routes = routes.flatMap { subtractPrefix(it, blocked) }
                }
                routes.forEach { builder.addRoute(InetAddress.getByAddress(it.address), it.prefixLength) }
            }
        }
        if (excluded.isNotEmpty()) {
            Log.i(TAG, "Keeping directly connected networks outside TCPeer: ${excluded.joinToString()}")
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
        descriptor: ParcelFileDescriptor,
        directInput: java.io.InputStream,
        directOutput: java.io.OutputStream,
        overlayIpv6: Inet6Address,
        peerOutputs: ConcurrentHashMap<String, java.io.OutputStream>,
        tunOutput: FileOutputStream,
    ) = coroutineScope {
        val tunInput = FileInputStream(descriptor.fileDescriptor)
        pendingTppPings.clear()
        val pendingTxBytes = AtomicLong(0)
        val pendingRxBytes = AtomicLong(0)
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
         *   TUN read -> TCP write -> next TUN read
         *
         * No Channel, no batching and no per-packet ByteArray copy.
         * Packet boundaries remain encoded by the IPv4/IPv6 headers.
         * TCPeer framing overhead remains ZERO bytes.
         */
        val tunToPeer = launch(Dispatchers.IO) {
            val buffer = ByteArray(65_535)

            while (true) {
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
                synchronized(output) {
                    output.write(
                        buffer,
                        0,
                        count,
                    )
                }

                pendingTxBytes.addAndGet(count.toLong())
            }
        }
        val pingRequests = launch(Dispatchers.IO) {
            TcpPeerRuntime.pingTarget.collectLatest { request ->
                pendingTppPings.clear()
                if (request == null) return@collectLatest
                val destination = runCatching { InetAddress.getByName(request.ipv6) as Inet6Address }.getOrNull()
                if (destination == null) {
                    TcpPeerRuntime.recordPing(request.peerId, null)
                    return@collectLatest
                }
                while (true) {
                    val output = peerOutputs[request.peerId]
                    if (output == null) {
                        TcpPeerRuntime.recordPing(request.peerId, null)
                        delay(1_000)
                        continue
                    }
                    val identifier = nextTppPingId.incrementAndGet()
                    val sentAt = System.nanoTime()
                    val packet = TppProtocol.request(overlayIpv6, destination, identifier, sentAt)
                    pendingTppPings[identifier] = request.peerId to sentAt
                    synchronized(output) {
                        TcpPeerProtocol.writeData(output, packet)
                    }
                    pendingTxBytes.addAndGet(packet.size.toLong())
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
        val peerToTun = launch(Dispatchers.IO) {
            while (true) {
                val packet = TcpPeerProtocol.readData(directInput)
                pendingRxBytes.addAndGet(packet.size.toLong())
                val replyBytes = processInboundPacket(
                    packet, "primary", overlayIpv6, directOutput, tunOutput,
                )
                if (replyBytes > 0) pendingTxBytes.addAndGet(replyBytes.toLong())
            }
        }
        try {
            tunToPeer.join()
            peerToTun.join()
        } finally {
            tunToPeer.cancel()
            peerToTun.cancel()
            pingRequests.cancel()
            statistics.cancel()
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
        connectionJob?.cancel()
        closeResources()
        TcpPeerRuntime.replace(VpnRuntimeState())
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @Synchronized
    private fun closeResources() {
        TcpPeerRuntime.stopContinuousPing()
        closeQuietly(tunnel)
        tunnel = null
        closeQuietly(directSocket)
        directSocket = null
        meshSockets.values.forEach(::closeQuietly)
        meshSockets.clear()
        meshSocketKeys.clear()
        meshEndpoints.clear()
        meshConnecting.clear()
        meshPunchActive.clear()
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
        private const val CHANNEL_ID = "tcppeer_vpn"
        private const val NOTIFICATION_ID = 7443
        private const val COORDINATOR_TIMEOUT_MS = 35_000
        private const val DIRECT_SOCKET_BUFFER_BYTES = 8 * 1024 * 1024
        private const val DIRECT_STREAM_BUFFER_BYTES = 1024 * 1024
        private const val DEVICE_REFRESH_INTERVAL_MS = 5_000L
        private const val TAG = "TCPeerVpnService"
    }
}
