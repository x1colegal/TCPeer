package com.tcppeer.android

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
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
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tcppeer.android.ui.TcpPeerTheme
import com.tcppeer.android.vpn.AppMaterialColor
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
            TcpPeerTheme(
                appTheme = configuration.appTheme,
                materialColor = configuration.materialColor,
            ) {
                ApplySystemBars(window = window, appTheme = configuration.appTheme)
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

private enum class RootTab {
    HOME, PEERS, SETTINGS
}

private enum class SettingsSection {
    NETWORK, APP
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TcpPeerScreen(
    configuration: VpnConfiguration,
    runtime: VpnRuntimeState,
    onConfigurationChange: (VpnConfiguration) -> Unit,
    onConnect: (VpnConfiguration) -> Unit,
    onDisconnect: () -> Unit,
) {
    var selectedTab by remember { mutableStateOf(RootTab.HOME) }
    var selectedSettingsSection by remember { mutableStateOf(SettingsSection.NETWORK) }
    val active = runtime.status != ConnectionStatus.DISCONNECTED && runtime.status != ConnectionStatus.NO_DIRECT_CONNECTION
    val connected = runtime.status == ConnectionStatus.COORDINATOR_ONLY ||
        runtime.status == ConnectionStatus.TCP4_DIRECT ||
        runtime.status == ConnectionStatus.TCP6_DIRECT

    runtime.activePingPeerId?.let { peerId ->
        TppPingDialog(peerId, runtime.pingSamples, TcpPeerRuntime::stopContinuousPing)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("TCPeer", fontWeight = FontWeight.Bold)
                        Text(
                            "PeerNet overlay for your devices",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    Row(modifier = Modifier.padding(end = 8.dp)) {
                        Switch(
                            checked = active,
                            onCheckedChange = { enabled -> if (enabled) onConnect(configuration) else onDisconnect() },
                        )
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == RootTab.HOME,
                    onClick = { selectedTab = RootTab.HOME },
                    icon = { Icon(Icons.Default.Home, null) },
                    label = { Text("Home") },
                )
                NavigationBarItem(
                    selected = selectedTab == RootTab.PEERS,
                    onClick = { selectedTab = RootTab.PEERS },
                    icon = { Icon(Icons.Default.Info, null) },
                    label = { Text("Peers") },
                )
                NavigationBarItem(
                    selected = selectedTab == RootTab.SETTINGS,
                    onClick = { selectedTab = RootTab.SETTINGS },
                    icon = { Icon(Icons.Default.Settings, null) },
                    label = { Text("Settings") },
                )
            }
        },
    ) { innerPadding ->
        when (selectedTab) {
            RootTab.HOME -> HomeTab(
                modifier = Modifier.padding(innerPadding),
                configuration = configuration,
                runtime = runtime,
                active = active,
                connected = connected,
                onConnect = { onConnect(configuration) },
            )
            RootTab.PEERS -> PeersTab(
                modifier = Modifier.padding(innerPadding),
                configuration = configuration,
                runtime = runtime,
            )
            RootTab.SETTINGS -> SettingsTab(
                modifier = Modifier.padding(innerPadding),
                configuration = configuration,
                active = active,
                selectedSection = selectedSettingsSection,
                onSelectSection = { selectedSettingsSection = it },
                onConfigurationChange = onConfigurationChange,
            )
        }
    }
}

@Composable
private fun HomeTab(
    modifier: Modifier = Modifier,
    configuration: VpnConfiguration,
    runtime: VpnRuntimeState,
    active: Boolean,
    connected: Boolean,
    onConnect: () -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            ConnectionHero(
                runtime = runtime,
                active = active,
                connected = connected,
                onConnect = onConnect,
            )
        }
        item {
            InfoCard(
                title = configuration.network,
                body = "This device joins your PeerNet as ${configuration.peerId}. " +
                    "This screen shows the current connection state, short transport logs, and the active overlay addresses.",
            )
        }
        item {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(24.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Connection details", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "These values are updated from the live session when TCPeer is connected.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    DetailRow("Endpoint", runtime.endpoint)
                    DetailRow("PeerNet IPv4", runtime.overlayIpv4)
                    DetailRow("PeerNet IPv6", runtime.overlayIpv6)
                    DetailRow("Traffic", "${formatBytes(runtime.rxBytes)} down / ${formatBytes(runtime.txBytes)} up")
                }
            }
        }
        item {
            InfoCard(
                title = "Security note",
                body = "Only the Secret Key proof is protected today. The direct transport and VPN payload are still not confidential yet.",
            )
        }
    }
}

@Composable
private fun PeersTab(
    modifier: Modifier = Modifier,
    configuration: VpnConfiguration,
    runtime: VpnRuntimeState,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Text("Peers", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        item {
            Text(
                "These are the machines linked to your PeerNet. Each peer card shows whether that device is online, what role it has, which transport it uses, and which public and overlay addresses it currently reports.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        item {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(24.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("${runtime.devices.count { it.online }} online", fontWeight = FontWeight.Bold)
                        Text(
                            "Inside ${configuration.network}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text("${runtime.devices.size} total", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (runtime.devices.isEmpty()) {
            item { EmptyPeers() }
        } else {
            items(runtime.devices) { device ->
                DeviceCard(
                    device = device,
                    isSelf = device.peerId == configuration.peerId,
                ) {
                    TcpPeerRuntime.startContinuousPing(device.peerId, device.overlayIpv6)
                }
            }
        }
    }
}

@Composable
private fun SettingsTab(
    modifier: Modifier = Modifier,
    configuration: VpnConfiguration,
    active: Boolean,
    selectedSection: SettingsSection,
    onSelectSection: (SettingsSection) -> Unit,
    onConfigurationChange: (VpnConfiguration) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        item {
            Text(
                "This area groups all app configuration. Network Settings controls how this device joins your PeerNet, and App Settings controls only the UI style.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        item {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionChip("Network Settings", selectedSection == SettingsSection.NETWORK) {
                    onSelectSection(SettingsSection.NETWORK)
                }
                SectionChip("App Settings", selectedSection == SettingsSection.APP) {
                    onSelectSection(SettingsSection.APP)
                }
            }
        }
        item {
            when (selectedSection) {
                SettingsSection.NETWORK -> NetworkSettingsPanel(configuration, active, onConfigurationChange)
                SettingsSection.APP -> AppSettingsPanel(configuration, active, onConfigurationChange)
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
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(
            modifier = Modifier.size(92.dp).graphicsLayer { scaleX = scale; scaleY = scale },
            shape = CircleShape,
            color = if (connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Box(contentAlignment = Alignment.Center) {
                androidx.compose.foundation.Image(
                    painterResource(R.drawable.ic_launcher_foreground),
                    null,
                    Modifier.size(82.dp),
                )
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
            runtime.detail.toFriendlyRuntimeDetail(),
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
private fun InfoCard(title: String, body: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(24.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun EmptyPeers() {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No peers yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "When the coordinator reports the devices linked to your PeerNet, they will show up here.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SectionChip(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(22.dp),
    ) {
        Text(
            title,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

@Composable
private fun NetworkSettingsPanel(
    configuration: VpnConfiguration,
    active: Boolean,
    onConfigurationChange: (VpnConfiguration) -> Unit,
) {
    var showSecret by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Settings, null)
                Text("Network Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Text(
                "Choose the coordinator address, the identity of this device, the preferred peer, and the basic transport parameters for the PeerNet session.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            SettingsField(configuration.coordinatorAddress, { onConfigurationChange(configuration.copy(coordinatorAddress = it)) }, "Coordinator DNS name or IP", active)
            SettingsField(configuration.coordinatorPort.toString(), { it.toIntOrNull()?.let { value -> onConfigurationChange(configuration.copy(coordinatorPort = value)) } }, "Coordinator TCP port", active, true)
            SettingsField(configuration.network, { onConfigurationChange(configuration.copy(network = it)) }, "PeerNet name", active)
            SettingsField(configuration.peerId, { onConfigurationChange(configuration.copy(peerId = it)) }, "This peer ID", active)
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Use Exit Node", fontWeight = FontWeight.Bold)
                        Text(
                            if (configuration.useExitNode) {
                                "Internet traffic is routed through the selected peer."
                            } else {
                                "Only PeerNet access stays in TCPeer. Regular internet stays on the phone network."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = configuration.useExitNode,
                        onCheckedChange = { onConfigurationChange(configuration.copy(useExitNode = it)) },
                        enabled = !active,
                    )
                }
            }
            SettingsField(
                value = configuration.targetPeerId,
                onValueChange = { onConfigurationChange(configuration.copy(targetPeerId = it)) },
                label = if (configuration.useExitNode) "Exit node peer ID" else "Preferred peer ID",
                active = active,
            )
            OutlinedTextField(
                value = configuration.secret,
                onValueChange = { onConfigurationChange(configuration.copy(secret = it)) },
                label = { Text("Protected Secret Key") },
                supportingText = { Text("Used only for authentication proof during connection.") },
                visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { showSecret = !showSecret }) { Text(if (showSecret) "Hide" else "Show") } },
                singleLine = true,
                enabled = !active,
                modifier = Modifier.fillMaxWidth(),
            )
            SettingsField(configuration.directPort.toString(), { it.toIntOrNull()?.let { value -> onConfigurationChange(configuration.copy(directPort = value)) } }, "Direct transport port", active, true)
            SettingsField(configuration.mtu.toString(), { it.toIntOrNull()?.let { value -> onConfigurationChange(configuration.copy(mtu = value)) } }, "Tunnel MTU", active, true)
        }
    }
}

@Composable
private fun AppSettingsPanel(
    configuration: VpnConfiguration,
    active: Boolean,
    onConfigurationChange: (VpnConfiguration) -> Unit,
) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.PlayArrow, null)
                Text("App Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Text(
                "These options only change the presentation of the app. They do not affect how the PeerNet tunnel works.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            ThemeSectionTitle("Appearance mode")
            ThemeModeOption(
                title = "Dark",
                description = "Dark surfaces with brighter accents.",
                selected = configuration.appTheme == AppThemeMode.DARK,
                enabled = !active,
                icon = { Icon(Icons.Default.Check, null) },
            ) {
                onConfigurationChange(configuration.copy(appTheme = AppThemeMode.DARK))
            }
            ThemeModeOption(
                title = "Pure Black",
                description = "True black surfaces for OLED-style dark mode.",
                selected = configuration.appTheme == AppThemeMode.PURE_BLACK,
                enabled = !active,
                icon = { Icon(Icons.Default.Check, null) },
            ) {
                onConfigurationChange(configuration.copy(appTheme = AppThemeMode.PURE_BLACK))
            }
            ThemeModeOption(
                title = "Light",
                description = "Light surfaces with darker content and status text.",
                selected = configuration.appTheme == AppThemeMode.LIGHT,
                enabled = !active,
                icon = { Icon(Icons.Default.Info, null) },
            ) {
                onConfigurationChange(configuration.copy(appTheme = AppThemeMode.LIGHT))
            }
            ThemeSectionTitle("Material color")
            MaterialColorPicker(
                selected = configuration.materialColor,
                enabled = !active,
            ) { color ->
                onConfigurationChange(configuration.copy(materialColor = color))
            }
        }
    }
}

@Composable
private fun ThemeSectionTitle(title: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(24.dp),
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
            RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        }
    }
}

@Composable
private fun MaterialColorPicker(
    selected: AppMaterialColor,
    enabled: Boolean,
    onSelect: (AppMaterialColor) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AppMaterialColor.entries.forEach { color ->
            val selectedColor = when (color) {
                AppMaterialColor.BLUE -> Color(0xFF315DA8)
                AppMaterialColor.INDIGO -> Color(0xFF4659A8)
                AppMaterialColor.VIOLET -> Color(0xFF6C4AA0)
                AppMaterialColor.PURPLE -> Color(0xFF7A4FA3)
                AppMaterialColor.PINK -> Color(0xFFB12E78)
                AppMaterialColor.GREEN -> Color(0xFF2D6A4F)
                AppMaterialColor.TEAL -> Color(0xFF006A67)
                AppMaterialColor.CYAN -> Color(0xFF006782)
                AppMaterialColor.RED -> Color(0xFFB3261E)
                AppMaterialColor.ORANGE -> Color(0xFF9A4600)
                AppMaterialColor.AMBER -> Color(0xFF7A5700)
                AppMaterialColor.YELLOW -> Color(0xFF6F5D00)
                AppMaterialColor.LIME -> Color(0xFF586500)
                AppMaterialColor.BROWN -> Color(0xFF7A5347)
                AppMaterialColor.GRAY -> Color(0xFF5F5E66)
                AppMaterialColor.ROSE -> Color(0xFF9C405D)
            }
            Surface(
                modifier = Modifier
                    .clickable(enabled = enabled) { onSelect(color) }
                    .border(
                        width = if (selected == color) 2.dp else 1.dp,
                        color = if (selected == color) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(22.dp),
                    ),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(22.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        modifier = Modifier.size(18.dp),
                        shape = CircleShape,
                        color = selectedColor,
                    ) {}
                    Text(color.label, fontWeight = if (selected == color) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun ApplySystemBars(
    window: Window,
    appTheme: AppThemeMode,
) {
    SideEffect {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = appTheme == AppThemeMode.LIGHT
        controller.isAppearanceLightNavigationBars = appTheme == AppThemeMode.LIGHT
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
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
    }

    Surface(
        modifier = Modifier.fillMaxWidth().animateContentSize().clickable { expanded = !expanded },
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
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
                            "${device.role} • ${if (device.online) "Online" else "Offline"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    if (expanded) "Hide" else "Details",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                "Transport: ${device.transport}. PeerNet IPv6: ${device.overlayIpv6}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            AnimatedVisibility(expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text(
                        "This peer card shows the identity, reachability, and addresses currently linked to this machine inside your PeerNet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    DetailRow("Status", if (device.online) "Online" else "Offline")
                    DetailRow("Role", device.role)
                    DetailRow("Platform", device.platform)
                    DetailRow("Transport", device.transport)
                    DetailRow("Public IPv4", device.ipv4)
                    DetailRow("Public IPv6", device.ipv6)
                    DetailRow("PeerNet IPv4", device.overlayIpv4)
                    DetailRow("PeerNet IPv6", device.overlayIpv6)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { copyAddress("PeerNet IPv4", device.overlayIpv4) },
                            enabled = device.overlayIpv4 != "-",
                            modifier = Modifier.weight(1f),
                        ) { Text("Copy IPv4") }
                        Button(
                            onClick = { copyAddress("PeerNet IPv6", device.overlayIpv6) },
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
                        "$peerId • IPv6 Next Header 99 • 1 sample per second",
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
                Text(
                    "This live graph measures latency to the selected PeerNet IPv6 peer.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                TppPingChart(samples)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    PingStatistic("Min", minimum?.let(::formatLatency) ?: "-")
                    PingStatistic("Avg", average?.let(::formatLatency) ?: "-")
                    PingStatistic("Max", maximum?.let(::formatLatency) ?: "-")
                    PingStatistic("Loss", String.format(Locale.US, "%.0f%%", lossPercent))
                }
                Text(
                    "Last ${samples.size} samples from the rolling 60-second window.",
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

private fun String.toFriendlyRuntimeDetail(): String = when {
    isBlank() -> "TCPeer is waiting for the next connection update."
    contains("connectivity, not confidentiality", ignoreCase = true) ->
        "TCPeer is ready to connect. It gives you PeerNet connectivity, but not traffic confidentiality yet."
    contains("coordinator", ignoreCase = true) && contains("auth", ignoreCase = true) ->
        "TCPeer is talking to the coordinator and authenticating this peer before the direct session comes up."
    contains("waiting", ignoreCase = true) ->
        "TCPeer is waiting for the selected peer to become ready for the direct connection."
    contains("retry", ignoreCase = true) && contains("direct", ignoreCase = true) ->
        "The direct path did not open yet, so TCPeer is retrying the peer-to-peer connection."
    contains("lost", ignoreCase = true) && contains("reconnect", ignoreCase = true) ->
        "The active connection was lost, so TCPeer is reconnecting on the current network."
    else -> this
}
