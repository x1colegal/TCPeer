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
    AppMaterialColor.INDIGO -> AccentPalette(
        lightPrimary = Color(0xFF4659A8),
        lightPrimaryContainer = Color(0xFFDFE1FF),
        lightOnPrimaryContainer = Color(0xFF001257),
        lightSecondary = Color(0xFF5C5F72),
        lightTertiary = Color(0xFF78536A),
        darkPrimary = Color(0xFFBBC3FF),
        darkOnPrimary = Color(0xFF112978),
        darkPrimaryContainer = Color(0xFF2E4190),
        darkOnPrimaryContainer = Color(0xFFDFE1FF),
        darkSecondary = Color(0xFFC3C5DD),
        darkTertiary = Color(0xFFE7B9D3),
    )
    AppMaterialColor.VIOLET -> AccentPalette(
        lightPrimary = Color(0xFF6C4AA0),
        lightPrimaryContainer = Color(0xFFEBDCFF),
        lightOnPrimaryContainer = Color(0xFF250058),
        lightSecondary = Color(0xFF645A70),
        lightTertiary = Color(0xFF805157),
        darkPrimary = Color(0xFFD5BAFF),
        darkOnPrimary = Color(0xFF3C1C70),
        darkPrimaryContainer = Color(0xFF543388),
        darkOnPrimaryContainer = Color(0xFFEBDCFF),
        darkSecondary = Color(0xFFCEC1DB),
        darkTertiary = Color(0xFFF3B7BE),
    )
    AppMaterialColor.PURPLE -> AccentPalette(
        lightPrimary = Color(0xFF7A4FA3),
        lightPrimaryContainer = Color(0xFFF1DBFF),
        lightOnPrimaryContainer = Color(0xFF310A5A),
        lightSecondary = Color(0xFF685B70),
        lightTertiary = Color(0xFF81525C),
        darkPrimary = Color(0xFFE0B7FF),
        darkOnPrimary = Color(0xFF492076),
        darkPrimaryContainer = Color(0xFF61378D),
        darkOnPrimaryContainer = Color(0xFFF1DBFF),
        darkSecondary = Color(0xFFD2C1DC),
        darkTertiary = Color(0xFFF5B7C6),
    )
    AppMaterialColor.PINK -> AccentPalette(
        lightPrimary = Color(0xFFB12E78),
        lightPrimaryContainer = Color(0xFFFFD8E8),
        lightOnPrimaryContainer = Color(0xFF3D0024),
        lightSecondary = Color(0xFF745663),
        lightTertiary = Color(0xFF7D5638),
        darkPrimary = Color(0xFFFFAFD1),
        darkOnPrimary = Color(0xFF661043),
        darkPrimaryContainer = Color(0xFF8C2960),
        darkOnPrimaryContainer = Color(0xFFFFD8E8),
        darkSecondary = Color(0xFFE4BDC9),
        darkTertiary = Color(0xFFF0BF9D),
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
    AppMaterialColor.TEAL -> AccentPalette(
        lightPrimary = Color(0xFF006A67),
        lightPrimaryContainer = Color(0xFF9CF1ED),
        lightOnPrimaryContainer = Color(0xFF00201F),
        lightSecondary = Color(0xFF4A6361),
        lightTertiary = Color(0xFF4D607C),
        darkPrimary = Color(0xFF80D5D1),
        darkOnPrimary = Color(0xFF003735),
        darkPrimaryContainer = Color(0xFF00504E),
        darkOnPrimaryContainer = Color(0xFF9CF1ED),
        darkSecondary = Color(0xFFB1CCC9),
        darkTertiary = Color(0xFFB5C8E9),
    )
    AppMaterialColor.CYAN -> AccentPalette(
        lightPrimary = Color(0xFF006782),
        lightPrimaryContainer = Color(0xFFBCE9FF),
        lightOnPrimaryContainer = Color(0xFF001F29),
        lightSecondary = Color(0xFF4C616B),
        lightTertiary = Color(0xFF5C5A7D),
        darkPrimary = Color(0xFF64D3FF),
        darkOnPrimary = Color(0xFF003546),
        darkPrimaryContainer = Color(0xFF004D63),
        darkOnPrimaryContainer = Color(0xFFBCE9FF),
        darkSecondary = Color(0xFFB4C8D4),
        darkTertiary = Color(0xFFC6C2F0),
    )
    AppMaterialColor.RED -> AccentPalette(
        lightPrimary = Color(0xFFB3261E),
        lightPrimaryContainer = Color(0xFFFFDAD6),
        lightOnPrimaryContainer = Color(0xFF410002),
        lightSecondary = Color(0xFF775653),
        lightTertiary = Color(0xFF705C2E),
        darkPrimary = Color(0xFFFFB4AB),
        darkOnPrimary = Color(0xFF690005),
        darkPrimaryContainer = Color(0xFF93000A),
        darkOnPrimaryContainer = Color(0xFFFFDAD6),
        darkSecondary = Color(0xFFE7BDB8),
        darkTertiary = Color(0xFFDBC48C),
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
    AppMaterialColor.AMBER -> AccentPalette(
        lightPrimary = Color(0xFF7A5700),
        lightPrimaryContainer = Color(0xFFFFDEA7),
        lightOnPrimaryContainer = Color(0xFF261900),
        lightSecondary = Color(0xFF6D5D3F),
        lightTertiary = Color(0xFF516440),
        darkPrimary = Color(0xFFF2C048),
        darkOnPrimary = Color(0xFF402D00),
        darkPrimaryContainer = Color(0xFF5C4100),
        darkOnPrimaryContainer = Color(0xFFFFDEA7),
        darkSecondary = Color(0xFFD8C4A1),
        darkTertiary = Color(0xFFB7CC9F),
    )
    AppMaterialColor.YELLOW -> AccentPalette(
        lightPrimary = Color(0xFF6F5D00),
        lightPrimaryContainer = Color(0xFFFBE287),
        lightOnPrimaryContainer = Color(0xFF221B00),
        lightSecondary = Color(0xFF675E40),
        lightTertiary = Color(0xFF44664B),
        darkPrimary = Color(0xFFDEC64E),
        darkOnPrimary = Color(0xFF393000),
        darkPrimaryContainer = Color(0xFF534600),
        darkOnPrimaryContainer = Color(0xFFFBE287),
        darkSecondary = Color(0xFFD2C6A1),
        darkTertiary = Color(0xFFA9D0B1),
    )
    AppMaterialColor.LIME -> AccentPalette(
        lightPrimary = Color(0xFF586500),
        lightPrimaryContainer = Color(0xFFDCEA86),
        lightOnPrimaryContainer = Color(0xFF181E00),
        lightSecondary = Color(0xFF5E6146),
        lightTertiary = Color(0xFF3D6658),
        darkPrimary = Color(0xFFC0CE6D),
        darkOnPrimary = Color(0xFF2B3400),
        darkPrimaryContainer = Color(0xFF404C00),
        darkOnPrimaryContainer = Color(0xFFDCEA86),
        darkSecondary = Color(0xFFC8C9AF),
        darkTertiary = Color(0xFFA5D1C0),
    )
    AppMaterialColor.BROWN -> AccentPalette(
        lightPrimary = Color(0xFF7A5347),
        lightPrimaryContainer = Color(0xFFFFDBD1),
        lightOnPrimaryContainer = Color(0xFF2E150D),
        lightSecondary = Color(0xFF6D5B55),
        lightTertiary = Color(0xFF5F6236),
        darkPrimary = Color(0xFFEABBB0),
        darkOnPrimary = Color(0xFF45271D),
        darkPrimaryContainer = Color(0xFF603D32),
        darkOnPrimaryContainer = Color(0xFFFFDBD1),
        darkSecondary = Color(0xFFD7C1BA),
        darkTertiary = Color(0xFFC7CA97),
    )
    AppMaterialColor.GRAY -> AccentPalette(
        lightPrimary = Color(0xFF5F5E66),
        lightPrimaryContainer = Color(0xFFE4E1EC),
        lightOnPrimaryContainer = Color(0xFF1B1B21),
        lightSecondary = Color(0xFF5F5E65),
        lightTertiary = Color(0xFF7A5863),
        darkPrimary = Color(0xFFC8C5D0),
        darkOnPrimary = Color(0xFF303036),
        darkPrimaryContainer = Color(0xFF47464D),
        darkOnPrimaryContainer = Color(0xFFE4E1EC),
        darkSecondary = Color(0xFFC8C5CC),
        darkTertiary = Color(0xFFEABCC9),
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
    val colors = if (appTheme == AppThemeMode.DARK || appTheme == AppThemeMode.PURE_BLACK) {
        darkColorScheme(
            primary = accent.darkPrimary,
            onPrimary = accent.darkOnPrimary,
            primaryContainer = accent.darkPrimaryContainer,
            onPrimaryContainer = accent.darkOnPrimaryContainer,
            secondary = accent.darkSecondary,
            tertiary = accent.darkTertiary,
            background = if (appTheme == AppThemeMode.PURE_BLACK) Color(0xFF000000) else Color(0xFF141218),
            surface = if (appTheme == AppThemeMode.PURE_BLACK) Color(0xFF000000) else Color(0xFF141218),
            surfaceContainerLowest = if (appTheme == AppThemeMode.PURE_BLACK) Color(0xFF000000) else Color(0xFF0F0D13),
            surfaceContainerLow = if (appTheme == AppThemeMode.PURE_BLACK) Color(0xFF0A0A0A) else Color(0xFF1D1B20),
            surfaceContainer = if (appTheme == AppThemeMode.PURE_BLACK) Color(0xFF101010) else Color(0xFF211F26),
            surfaceContainerHigh = if (appTheme == AppThemeMode.PURE_BLACK) Color(0xFF141414) else Color(0xFF2B2930),
            surfaceContainerHighest = if (appTheme == AppThemeMode.PURE_BLACK) Color(0xFF191919) else Color(0xFF36343B),
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
