package com.tcppeer.android.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ConnectionStatus(val label: String) {
    DISCONNECTED("Disconnected"),
    CONNECTING("Connecting"),
    TCP6_DIRECT("TCP6 Direct"),
    TCP4_DIRECT("TCP4 Direct"),
    NO_DIRECT_CONNECTION("No Direct Connection"),
}

data class NetworkDevice(
    val peerId: String,
    val online: Boolean,
    val role: String,
    val platform: String,
    val transport: String,
    val ipv4: String,
    val ipv6: String,
    val overlayIpv4: String,
    val overlayIpv6: String,
)

data class TppPingRequest(val peerId: String, val ipv6: String)

data class TppPingSample(val timestampMillis: Long, val latencyMillis: Double?)

data class VpnRuntimeState(
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val endpoint: String = "-",
    val overlayIpv4: String = "-",
    val overlayIpv6: String = "-",
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
    val connectedAtMillis: Long? = null,
    val detail: String = "TCPeer provides connectivity, not confidentiality.",
    val devices: List<NetworkDevice> = emptyList(),
    val activePingPeerId: String? = null,
    val pingSamples: List<TppPingSample> = emptyList(),
)

object TcpPeerRuntime {
    private val mutableState = MutableStateFlow(VpnRuntimeState())
    val state: StateFlow<VpnRuntimeState> = mutableState.asStateFlow()
    private val mutablePingTarget = MutableStateFlow<TppPingRequest?>(null)
    val pingTarget: StateFlow<TppPingRequest?> = mutablePingTarget.asStateFlow()

    fun update(transform: (VpnRuntimeState) -> VpnRuntimeState) {
        mutableState.value = transform(mutableState.value)
    }

    fun replace(value: VpnRuntimeState) {
        mutableState.value = value
    }

    fun startContinuousPing(peerId: String, ipv6: String) {
        update { it.copy(activePingPeerId = peerId, pingSamples = emptyList()) }
        mutablePingTarget.value = TppPingRequest(peerId, ipv6)
    }

    fun stopContinuousPing() {
        mutablePingTarget.value = null
        update { it.copy(activePingPeerId = null, pingSamples = emptyList()) }
    }

    fun recordPing(peerId: String, latencyMillis: Double?) {
        update { state ->
            if (state.activePingPeerId != peerId) state else state.copy(
                pingSamples = (state.pingSamples + TppPingSample(
                    System.currentTimeMillis(), latencyMillis,
                )).takeLast(60),
            )
        }
    }
}
