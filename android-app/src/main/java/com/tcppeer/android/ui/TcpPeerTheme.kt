package com.tcppeer.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.tcppeer.android.vpn.AppMaterialColor
import com.tcppeer.android.vpn.AppThemeMode

private data class AccentPalette(
    val lightPrimary: Color,
    val lightPrimaryContainer: Color,
    val lightOnPrimaryContainer: Color,
    val lightSecondary: Color,
    val lightTertiary: Color,
    val darkPrimary: Color,
    val darkOnPrimary: Color,
    val darkPrimaryContainer: Color,
    val darkOnPrimaryContainer: Color,
    val darkSecondary: Color,
    val darkTertiary: Color,
)

private fun palette(color: AppMaterialColor): AccentPalette = when (color) {
    AppMaterialColor.BLUE -> AccentPalette(
        lightPrimary = Color(0xFF315DA8),
        lightPrimaryContainer = Color(0xFFD9E2FF),
        lightOnPrimaryContainer = Color(0xFF001A41),
        lightSecondary = Color(0xFF565E71),
        lightTertiary = Color(0xFF705574),
        darkPrimary = Color(0xFFAFC6FF),
        darkOnPrimary = Color(0xFF002E69),
        darkPrimaryContainer = Color(0xFF164682),
        darkOnPrimaryContainer = Color(0xFFD9E2FF),
        darkSecondary = Color(0xFFBEC6DC),
        darkTertiary = Color(0xFFDDBBDD),
    )
    AppMaterialColor.GREEN -> AccentPalette(
        lightPrimary = Color(0xFF2D6A4F),
        lightPrimaryContainer = Color(0xFFC2F0D2),
        lightOnPrimaryContainer = Color(0xFF002114),
        lightSecondary = Color(0xFF4F6355),
        lightTertiary = Color(0xFF3D6472),
        darkPrimary = Color(0xFFA7DDBA),
        darkOnPrimary = Color(0xFF013823),
        darkPrimaryContainer = Color(0xFF155138),
        darkOnPrimaryContainer = Color(0xFFC2F0D2),
        darkSecondary = Color(0xFFB7CCBB),
        darkTertiary = Color(0xFFA7CDDD),
    )
    AppMaterialColor.ORANGE -> AccentPalette(
        lightPrimary = Color(0xFF9A4600),
        lightPrimaryContainer = Color(0xFFFFDCC7),
        lightOnPrimaryContainer = Color(0xFF331200),
        lightSecondary = Color(0xFF745B4A),
        lightTertiary = Color(0xFF655F31),
        darkPrimary = Color(0xFFFFB787),
        darkOnPrimary = Color(0xFF542100),
        darkPrimaryContainer = Color(0xFF773300),
        darkOnPrimaryContainer = Color(0xFFFFDCC7),
        darkSecondary = Color(0xFFE5C1A9),
        darkTertiary = Color(0xFFE9E294),
    )
    AppMaterialColor.ROSE -> AccentPalette(
        lightPrimary = Color(0xFF9C405D),
        lightPrimaryContainer = Color(0xFFFFD9E2),
        lightOnPrimaryContainer = Color(0xFF3E001B),
        lightSecondary = Color(0xFF74565F),
        lightTertiary = Color(0xFF7C5635),
        darkPrimary = Color(0xFFFFB1C8),
        darkOnPrimary = Color(0xFF5F112F),
        darkPrimaryContainer = Color(0xFF7C2945),
        darkOnPrimaryContainer = Color(0xFFFFD9E2),
        darkSecondary = Color(0xFFE3BDC7),
        darkTertiary = Color(0xFFF0BD95),
    )
}

@Composable
fun TcpPeerTheme(
    appTheme: AppThemeMode = AppThemeMode.DARK,
    materialColor: AppMaterialColor = AppMaterialColor.BLUE,
    content: @Composable () -> Unit,
) {
    val accent = palette(materialColor)
    val colors = if (appTheme == AppThemeMode.DARK) {
        darkColorScheme(
            primary = accent.darkPrimary,
            onPrimary = accent.darkOnPrimary,
            primaryContainer = accent.darkPrimaryContainer,
            onPrimaryContainer = accent.darkOnPrimaryContainer,
            secondary = accent.darkSecondary,
            tertiary = accent.darkTertiary,
        )
    } else {
        lightColorScheme(
            primary = accent.lightPrimary,
            onPrimary = Color.White,
            primaryContainer = accent.lightPrimaryContainer,
            onPrimaryContainer = accent.lightOnPrimaryContainer,
            secondary = accent.lightSecondary,
            tertiary = accent.lightTertiary,
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}
