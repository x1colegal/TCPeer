package com.tcppeer.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tcppeer.android.ui.TcpPeerTheme
import com.tcppeer.android.vpn.AppThemeMode
import com.tcppeer.android.vpn.ConfigurationStore
import com.tcppeer.android.vpn.ConnectionStatus
import com.tcppeer.android.vpn.NetworkDevice
import com.tcppeer.android.vpn.TcpPeerRuntime
import com.tcppeer.android.vpn.TcpPeerVpnService
import com.tcppeer.android.vpn.TppPingSample
import com.tcppeer.android.vpn.VpnConfiguration
import com.tcppeer.android.vpn.VpnRuntimeState
import java.util.Locale
import kotlin.math.max

class MainActivity : ComponentActivity() {
    private var pendingConfiguration: VpnConfiguration? = null

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) startVpn()
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val store = remember { ConfigurationStore(this) }
            var configuration by remember { mutableStateOf(store.load()) }
            TcpPeerTheme(appTheme = configuration.appTheme) {
                val runtime by TcpPeerRuntime.state.collectAsStateWithLifecycle()
                TcpPeerScreen(
                    configuration = configuration,
                    runtime = runtime,
                    onConfigurationChange = { updated ->
                        configuration = updated
                        runCatching { store.save(updated) }
                    },
                    onConnect = ::requestVpn,
                    onDisconnect = ::stopVpn,
                )
            }
        }
    }

    private fun requestVpn(configuration: VpnConfiguration) {
        try {
            configuration.validate()
            ConfigurationStore(this).save(configuration)
            pendingConfiguration = configuration
            val intent = VpnService.prepare(this)
            if (intent == null) startVpn() else vpnPermission.launch(intent)
        } catch (error: IllegalArgumentException) {
            TcpPeerRuntime.update {
                it.copy(status = ConnectionStatus.NO_DIRECT_CONNECTION, detail = error.message ?: "Invalid configuration")
            }
        }
    }

    private fun startVpn() {
        pendingConfiguration?.let { ConfigurationStore(this).save(it) }
        ContextCompat.startForegroundService(
            this,
            Intent(this, TcpPeerVpnService::class.java).setAction(TcpPeerVpnService.ACTION_CONNECT),
        )
    }

    private fun stopVpn() {
        startService(Intent(this, TcpPeerVpnService::class.java).setAction(TcpPeerVpnService.ACTION_DISCONNECT))
    }
}

@Composable
private fun TcpPeerScreen(
    configuration: VpnConfiguration,
    runtime: VpnRuntimeState,
    onConfigurationChange: (VpnConfiguration) -> Unit,
    onConnect: (VpnConfiguration) -> Unit,
    onDisconnect: () -> Unit,
) {
    var showNetworkSettings by remember { mutableStateOf(false) }
    var showAppSettings by remember { mutableStateOf(false) }
    val active = runtime.status in setOf(ConnectionStatus.CONNECTING, ConnectionStatus.TCP4_DIRECT, ConnectionStatus.TCP6_DIRECT)
    val connected = runtime.status in setOf(ConnectionStatus.TCP4_DIRECT, ConnectionStatus.TCP6_DIRECT)

    runtime.activePingPeerId?.let { peerId ->
        TppPingDialog(peerId, runtime.pingSamples, TcpPeerRuntime::stopContinuousPing)
    }
    if (showNetworkSettings) {
        NetworkSettingsDialog(
            configuration = configuration,
            active = active,
            onConfigurationChange = onConfigurationChange,
            onDismiss = { showNetworkSettings = false },
        )
    }
    if (showAppSettings) {
        AppSettingsDialog(
            configuration = configuration,
            active = active,
            onConfigurationChange = onConfigurationChange,
            onDismiss = { showAppSettings = false },
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
      LazyColumn(
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 48.dp, bottom = 40.dp),
          verticalArrangement = Arrangement.spacedBy(22.dp),
      ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = active,
                    onCheckedChange = { enabled -> if (enabled) onConnect(configuration) else onDisconnect() },
                )
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(configuration.network, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    AnimatedContent(runtime.status, label = "connection-status") { status ->
                        Text(status.label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
                    }
                }
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Image(
                        painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = "TCPeer logo",
                        modifier = Modifier.size(54.dp).padding(5.dp),
                    )
                }
            }
        }

        item {
            ConnectionHero(
                runtime = runtime,
                active = active,
                connected = connected,
                onConnect = { onConnect(configuration) },
            )
        }

        if (connected) {
            item {
                Column {
                    Text("Machines", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "${runtime.devices.count { it.online }} online in ${configuration.network}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (runtime.devices.isEmpty()) {
                item { EmptyMachines() }
            } else {
                items(runtime.devices.size) { index ->
                    val device = runtime.devices[index]
                    DeviceCard(
                        device = device,
                        isSelf = device.peerId == configuration.peerId,
                    ) {
                        TcpPeerRuntime.startContinuousPing(device.peerId, device.overlayIpv6)
                    }
                }
            }
            item {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("Connection details", fontWeight = FontWeight.Bold)
                        DetailRow("Endpoint", runtime.endpoint)
                        DetailRow("TCPeer IPv4", runtime.overlayIpv4)
                        DetailRow("TCPeer IPv6", runtime.overlayIpv6)
                        DetailRow("Traffic", "${formatBytes(runtime.rxBytes)} down / ${formatBytes(runtime.txBytes)} up")
                    }
                }
            }
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { showNetworkSettings = true },
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(20.dp),
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Settings, contentDescription = null)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Network settings", fontWeight = FontWeight.Bold)
                        Text("Coordinator, identity, exit node and transport", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(">", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { showAppSettings = true },
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(20.dp),
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("App settings", fontWeight = FontWeight.Bold)
                        Text("Theme and interface presentation", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(">", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text(
                    "Only the Secret Key proof is protected. Endpoints and VPN traffic remain cleartext.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
      }
    }
}

@Composable
private fun ConnectionHero(
    runtime: VpnRuntimeState,
    active: Boolean,
    connected: Boolean,
    onConnect: () -> Unit,
) {
    val scale by animateFloatAsState(if (active) 1.0f else 0.92f, tween(500), label = "hero-scale")
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(
            modifier = Modifier.size(86.dp).graphicsLayer { scaleX = scale; scaleY = scale },
            shape = CircleShape,
            color = if (connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(78.dp))
            }
        }
        Text(
            when {
                connected -> "Connected"
                runtime.status == ConnectionStatus.CONNECTING -> "Connecting..."
                runtime.status == ConnectionStatus.NO_DIRECT_CONNECTION -> "Connection failed"
                else -> "Not connected"
            },
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            if (connected) runtime.status.label else runtime.detail,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
        AnimatedVisibility(!active) {
            Button(
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth().height(58.dp),
                colors = ButtonDefaults.buttonColors(),
            ) {
                Text("Connect", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun EmptyMachines() {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No machines yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("The coordinator directory will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NetworkSettingsDialog(
    configuration: VpnConfiguration,
    active: Boolean,
    onConfigurationChange: (VpnConfiguration) -> Unit,
    onDismiss: () -> Unit,
) {
    var showSecret by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Network settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close settings") }
                    }
                }
                item { SettingsField(configuration.coordinatorAddress, { onConfigurationChange(configuration.copy(coordinatorAddress = it)) }, "Coordinator DNS name or IP", active) }
                item { SettingsField(configuration.coordinatorPort.toString(), { it.toIntOrNull()?.let { value -> onConfigurationChange(configuration.copy(coordinatorPort = value)) } }, "Coordinator TCP port", active, true) }
                item { SettingsField(configuration.network, { onConfigurationChange(configuration.copy(network = it)) }, "Network", active) }
                item { SettingsField(configuration.peerId, { onConfigurationChange(configuration.copy(peerId = it)) }, "Peer ID", active) }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Use Exit Node", fontWeight = FontWeight.Bold)
                            Text(
                                if (configuration.useExitNode) "Route internet through a selected peer"
                                else "Keep the tunnel without forcing an exit node",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = configuration.useExitNode,
                            onCheckedChange = {
                            onConfigurationChange(
                                configuration.copy(
                                    useExitNode = it,
                                ),
                            )
                            },
                            enabled = !active,
                        )
                    }
                }
                item {
                    SettingsField(
                        value = configuration.targetPeerId,
                        onValueChange = { onConfigurationChange(configuration.copy(targetPeerId = it)) },
                        label = "Exit node peer ID",
                        active = active || !configuration.useExitNode,
                    )
                }
                item {
                    OutlinedTextField(
                        value = configuration.secret,
                        onValueChange = { onConfigurationChange(configuration.copy(secret = it)) },
                        label = { Text("Protected Secret Key") },
                        supportingText = { Text("Sent only as an HMAC-SHA256 proof") },
                        visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { TextButton(onClick = { showSecret = !showSecret }) { Text(if (showSecret) "Hide" else "Show") } },
                        singleLine = true,
                        enabled = !active,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item { SettingsField(configuration.directPort.toString(), { it.toIntOrNull()?.let { value -> onConfigurationChange(configuration.copy(directPort = value)) } }, "Direct TCP port", active, true) }
                item { SettingsField(configuration.mtu.toString(), { it.toIntOrNull()?.let { value -> onConfigurationChange(configuration.copy(mtu = value)) } }, "MTU", active, true) }
                item { Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Done") } }
            }
        }
    }
}

@Composable
private fun AppSettingsDialog(
    configuration: VpnConfiguration,
    active: Boolean,
    onConfigurationChange: (VpnConfiguration) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("App settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close app settings") }
                    }
                }
                item {
                    Text("App theme", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                item {
                    ThemeModeOption(
                        title = "Dark",
                        description = "Dark TCPeer theme.",
                        selected = configuration.appTheme == AppThemeMode.DARK,
                        enabled = !active,
                        icon = { Icon(Icons.Default.Check, contentDescription = null) },
                    ) {
                        onConfigurationChange(configuration.copy(appTheme = AppThemeMode.DARK))
                    }
                }
                item {
                    ThemeModeOption(
                        title = "Light",
                        description = "Light TCPeer theme.",
                        selected = configuration.appTheme == AppThemeMode.LIGHT,
                        enabled = !active,
                        icon = { Icon(Icons.Default.Info, contentDescription = null) },
                    ) {
                        onConfigurationChange(configuration.copy(appTheme = AppThemeMode.LIGHT))
                    }
                }
                item { Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Done") } }
            }
        }
    }
}

@Composable
private fun ThemeModeOption(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = shape,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            icon()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onClick, enabled = enabled) {
                Text(if (selected) "Selected" else "Use")
            }
        }
    }
}

@Composable
private fun SettingsField(value: String, onValueChange: (String) -> Unit, label: String, active: Boolean, numeric: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
        singleLine = true,
        enabled = !active,
        modifier = Modifier.fillMaxWidth(),
    )
}


@Composable
private fun DeviceCard(device: NetworkDevice, isSelf: Boolean, onPing: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    fun copyAddress(label: String, value: String) {
        if (value == "-") return
        val clipboard =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
    }
    Surface(
        modifier = Modifier.fillMaxWidth().animateContentSize().clickable { expanded = !expanded },
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                            Text(device.platform.take(1).uppercase(), fontWeight = FontWeight.Bold)
                            Box(
                                Modifier.align(Alignment.BottomEnd).size(11.dp).background(
                                    if (device.online) Color(0xFF42C767) else MaterialTheme.colorScheme.outline,
                                    CircleShape,
                                ),
                            )
                        }
                    }
                    Spacer(Modifier.size(13.dp))
                    Column {
                        Text(device.peerId, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            device.role,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Text(
                    if (expanded) "^" else ">",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            AnimatedVisibility(expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    DetailRow("Status", if (device.online) "Online" else "Offline")
                    DetailRow("Role", device.role)
                    DetailRow("Platform", device.platform)
                    DetailRow("Transport", device.transport)
                    DetailRow("Public IPv4", device.ipv4)
                    DetailRow("Public IPv6", device.ipv6)
                    DetailRow("TCPeer IPv4", device.overlayIpv4)
                    DetailRow("TCPeer IPv6", device.overlayIpv6)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { copyAddress("TCPeer IPv4", device.overlayIpv4) },
                            enabled = device.overlayIpv4 != "-",
                            modifier = Modifier.weight(1f),
                        ) { Text("Copy IPv4") }

                        Button(
                            onClick = { copyAddress("TCPeer IPv6", device.overlayIpv6) },
                            enabled = device.overlayIpv6 != "-",
                            modifier = Modifier.weight(1f),
                        ) { Text("Copy IPv6") }
                    }

                    if (!isSelf) {
                        Button(
                            onClick = onPing,
                            enabled = device.online && device.overlayIpv6 != "-",
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Open continuous TPP ping") }
                    }
                }
            }
        }
    }
}

@Composable
private fun TppPingDialog(peerId: String, samples: List<TppPingSample>, onClose: () -> Unit) {
    val successful = samples.mapNotNull { it.latencyMillis }
    val latest = samples.lastOrNull()?.latencyMillis
    val minimum = successful.minOrNull()
    val average = successful.takeIf { it.isNotEmpty() }?.average()
    val maximum = successful.maxOrNull()
    val lossPercent = if (samples.isEmpty()) 0.0 else (samples.count { it.latencyMillis == null } * 100.0 / samples.size)

    Dialog(onDismissRequest = onClose) {
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column {
                    Text("TPP continuous ping", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        "$peerId - IPv6 Next Header 99 - every 1 second",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    latest?.let { String.format(Locale.US, "%.1f ms", it) }
                        ?: if (samples.isEmpty()) "Waiting for first reply..." else "Timed out",
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (latest == null && samples.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
                TppPingChart(samples)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    PingStatistic("Min", minimum?.let(::formatLatency) ?: "-")
                    PingStatistic("Avg", average?.let(::formatLatency) ?: "-")
                    PingStatistic("Max", maximum?.let(::formatLatency) ?: "-")
                    PingStatistic("Loss", String.format(Locale.US, "%.0f%%", lossPercent))
                }
                Text(
                    "Last ${samples.size} samples (60-second window)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Stop ping") }
            }
        }
    }
}

@Composable
private fun TppPingChart(samples: List<TppPingSample>) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val lossColor = MaterialTheme.colorScheme.error
    val chartBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    val chartMaximum = max(10.0, (samples.mapNotNull { it.latencyMillis }.maxOrNull() ?: 10.0) * 1.15)
    Canvas(
        Modifier.fillMaxWidth().height(210.dp).background(chartBackground, MaterialTheme.shapes.medium),
    ) {
        val inset = 14.dp.toPx()
        val left = inset
        val top = inset
        val right = size.width - inset
        val bottom = size.height - inset
        repeat(5) { index ->
            val y = top + (bottom - top) * index / 4f
            drawLine(gridColor, start = androidx.compose.ui.geometry.Offset(left, y), end = androidx.compose.ui.geometry.Offset(right, y), strokeWidth = 1.dp.toPx())
        }
        if (samples.isEmpty()) return@Canvas
        val path = Path()
        var segmentOpen = false
        samples.forEachIndexed { index, sample ->
            val x = if (samples.size == 1) right else left + (right - left) * index / (samples.size - 1f)
            val latency = sample.latencyMillis
            if (latency == null) {
                segmentOpen = false
                drawCircle(lossColor, radius = 3.dp.toPx(), center = androidx.compose.ui.geometry.Offset(x, bottom))
            } else {
                val y = bottom - ((latency / chartMaximum).coerceIn(0.0, 1.0).toFloat() * (bottom - top))
                if (segmentOpen) path.lineTo(x, y) else path.moveTo(x, y)
                segmentOpen = true
            }
        }
        drawPath(path, lineColor, style = Stroke(width = 3.dp.toPx()))
    }
}

@Composable
private fun PingStatistic(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatLatency(value: Double): String = String.format(Locale.US, "%.1f ms", value)

@Composable
private fun StatusCard(runtime: VpnRuntimeState) {
    val statusColor = when (runtime.status) {
        ConnectionStatus.TCP4_DIRECT, ConnectionStatus.TCP6_DIRECT -> Color(0xFF2E7D32)
        ConnectionStatus.CONNECTING -> MaterialTheme.colorScheme.tertiary
        ConnectionStatus.NO_DIRECT_CONNECTION -> MaterialTheme.colorScheme.error
        ConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.outline
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(statusColor, CircleShape))
                Spacer(Modifier.size(10.dp))
                Text(runtime.status.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
            Text(runtime.detail, style = MaterialTheme.typography.bodyMedium)
            if (runtime.status == ConnectionStatus.TCP4_DIRECT || runtime.status == ConnectionStatus.TCP6_DIRECT) {
                HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f))
                DetailRow("Endpoint", runtime.endpoint)
                DetailRow("Overlay IPv4", runtime.overlayIpv4)
                DetailRow("Overlay IPv6", runtime.overlayIpv6)
                DetailRow("Traffic", "${formatBytes(runtime.rxBytes)} received / ${formatBytes(runtime.txBytes)} sent")
                val uptime = runtime.connectedAtMillis?.let { (System.currentTimeMillis() - it) / 1000 } ?: 0
                DetailRow("Uptime", formatDuration(uptime))
            }
        }
    }
}

@Composable
private fun WarningCard() {
    FilledTonalButton(onClick = {}, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
        Icon(Icons.Default.Info, contentDescription = null)
        Spacer(Modifier.size(10.dp))
        Column(horizontalAlignment = Alignment.Start) {
            Text("No confidentiality", fontWeight = FontWeight.SemiBold)
            Text("Only the Secret Key proof is protected. VPN traffic remains cleartext.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(0.34f))
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.66f),
        )
    }
}

private fun formatBytes(value: Long): String {
    if (value < 1024) return "$value B"
    val units = arrayOf("KiB", "MiB", "GiB")
    var amount = value.toDouble()
    var unit = -1
    while (amount >= 1024 && unit < units.lastIndex) {
        amount /= 1024
        unit++
    }
    return String.format(Locale.US, "%.1f %s", amount, units[unit])
}

private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remaining = seconds % 60
    return if (hours > 0) "%dh %02dm".format(hours, minutes) else "%dm %02ds".format(minutes, remaining)
}
