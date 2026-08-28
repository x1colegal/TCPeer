package com.tcppeer.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.tcppeer.android.vpn.AppThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF315DA8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E2FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondary = Color(0xFF565E71),
    tertiary = Color(0xFF705574),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAFC6FF),
    onPrimary = Color(0xFF002E69),
    primaryContainer = Color(0xFF164682),
    onPrimaryContainer = Color(0xFFD9E2FF),
    secondary = Color(0xFFBEC6DC),
    tertiary = Color(0xFFDDBBDD),
)

@Composable
fun TcpPeerTheme(
    appTheme: AppThemeMode = AppThemeMode.DARK,
    content: @Composable () -> Unit,
) {
    val colors = if (appTheme == AppThemeMode.DARK) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
