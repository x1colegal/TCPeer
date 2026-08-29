package com.tcppeer.android.vpn

import android.content.Context
import androidx.core.content.edit

enum class AppThemeMode(val storageValue: String, val label: String) {
    LIGHT("light", "Light"),
    DARK("dark", "Dark"),
    PURE_BLACK("pure_black", "Pure Black");

    companion object {
        fun fromStorage(value: String?): AppThemeMode = entries.firstOrNull { it.storageValue == value } ?: DARK
    }
}

enum class AppMaterialColor(
    val storageValue: String,
    val label: String,
) {
    BLUE("blue", "Blue"),
    INDIGO("indigo", "Indigo"),
    VIOLET("violet", "Violet"),
    PURPLE("purple", "Purple"),
    PINK("pink", "Pink"),
    GREEN("green", "Green"),
    TEAL("teal", "Teal"),
    CYAN("cyan", "Cyan"),
    RED("red", "Red"),
    ORANGE("orange", "Orange"),
    AMBER("amber", "Amber"),
    YELLOW("yellow", "Yellow"),
    LIME("lime", "Lime"),
    BROWN("brown", "Brown"),
    GRAY("gray", "Gray"),
    ROSE("rose", "Rose");

    companion object {
        fun fromStorage(value: String?): AppMaterialColor =
            entries.firstOrNull { it.storageValue == value } ?: BLUE
    }
}

data class VpnConfiguration(
    val coordinatorAddress: String = "",
    val coordinatorPort: Int = 7443,
    val network: String = "home",
    val peerId: String = "android",
    val secret: String = "",
    val useExitNode: Boolean = true,
    val targetPeerId: String = "main-server",
    val directPort: Int = 7444,
    val mtu: Int = 1400,
    val appTheme: AppThemeMode = AppThemeMode.DARK,
    val materialColor: AppMaterialColor = AppMaterialColor.BLUE,
) {
    fun validate() {
        require(coordinatorAddress.isNotBlank()) { "Coordinator DNS name or IP address is required" }
        require("://" !in coordinatorAddress) { "Coordinator address must not be a URL" }
        require(coordinatorPort in 1..65535) { "Coordinator port must be between 1 and 65535" }
        require(network.isNotBlank() && network.all { it.code in 1..127 }) { "Network must be ASCII" }
        require(peerId.isNotBlank() && peerId.all { it.code in 1..127 }) { "Peer ID must be ASCII" }
        require(secret.all { it.code in 0..127 }) { "Secret must be ASCII" }
        require(targetPeerId.isNotBlank() && targetPeerId.all { it.code in 1..127 }) {
            "Server or exit node peer ID must be ASCII"
        }
        require(directPort in 1..65535) { "Direct port must be between 1 and 65535" }
        require(mtu in 1280..65535) { "Dual-stack MTU must be between 1280 and 65535" }
    }
}

class ConfigurationStore(context: Context) {
    private val preferences = context.getSharedPreferences("tcppeer", Context.MODE_PRIVATE)

    fun load(): VpnConfiguration = VpnConfiguration(
        coordinatorAddress = preferences.getString("coordinator_address", "") ?: "",
        coordinatorPort = preferences.getInt("coordinator_port", 7443),
        network = preferences.getString("network", "home") ?: "home",
        peerId = preferences.getString("peer_id", "android") ?: "android",
        secret = preferences.getString("secret", "") ?: "",
        useExitNode = preferences.getBoolean("use_exit_node", true),
        targetPeerId = preferences.getString("target_peer_id", "main-server") ?: "main-server",
        directPort = preferences.getInt("direct_port", 7444),
        mtu = preferences.getInt("mtu", 1400),
        appTheme = AppThemeMode.fromStorage(preferences.getString("app_theme", AppThemeMode.DARK.storageValue)),
        materialColor = AppMaterialColor.fromStorage(
            preferences.getString("material_color", AppMaterialColor.BLUE.storageValue),
        ),
    )

    fun save(value: VpnConfiguration) {
        value.validate()
        preferences.edit {
            putString("coordinator_address", value.coordinatorAddress)
            putInt("coordinator_port", value.coordinatorPort)
            putString("network", value.network)
            putString("peer_id", value.peerId)
            putString("secret", value.secret)
            putBoolean("use_exit_node", value.useExitNode)
            putString("target_peer_id", value.targetPeerId)
            putInt("direct_port", value.directPort)
            putInt("mtu", value.mtu)
            putBoolean("route_all_traffic", value.useExitNode)
            putString("app_theme", value.appTheme.storageValue)
            putString("material_color", value.materialColor.storageValue)
        }
    }

    fun slaacInterfaceId(): Long {
        val stored = preferences.getLong("slaac_interface_id", 0L)
        if (stored != 0L) return stored
        var generated = kotlin.random.Random.nextLong()
        if (generated == 0L) generated = 1L
        generated = generated and (1L shl 57).inv()
        preferences.edit { putLong("slaac_interface_id", generated) }
        return generated
    }
}
