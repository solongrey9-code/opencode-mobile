package ai.opencode.mobile.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val OcBlue = Color(0xFF3B5CF6)
val OcBlueSoft = Color(0xFFA2BCFF)
val OcPeach = Color(0xFFFAB283)
val OcGreen = Color(0xFF6BD586)
val OcYellow = Color(0xFFF2CF76)
val OcRed = Color(0xFFF17471)

val OcDeepDark = Color(0xFF080808)
val OcBaseDark = Color(0xFF161616)
val OcLayer1Dark = Color(0xFF242424)
val OcLayer2Dark = Color(0xFF2E2E2E)
val OcLayer3Dark = Color(0xFF3A3A3A)
val OcTextDark = Color(0xFFFAFAFA)
val OcMutedDark = Color(0xFFAEAEAE)
val OcFaint = Color(0xFF808080)

val OcDeepLight = Color(0xFFFAFAFA)
val OcBaseLight = Color(0xFFFFFFFF)
val OcLayer1Light = Color(0xFFFAFAFA)
val OcLayer2Light = Color(0xFFF2F2F2)
val OcLayer3Light = Color(0xFFEEEEEE)
val OcTextLight = Color(0xFF161616)
val OcMutedLight = Color(0xFF5C5C5C)

private val DarkScheme = darkColorScheme(
    primary = OcBlueSoft,
    onPrimary = OcDeepDark,
    primaryContainer = OcBlue,
    onPrimaryContainer = Color.White,
    secondary = OcPeach,
    onSecondary = OcDeepDark,
    background = OcDeepDark,
    onBackground = OcTextDark,
    surface = OcBaseDark,
    onSurface = OcTextDark,
    surfaceVariant = OcLayer2Dark,
    onSurfaceVariant = OcMutedDark,
    outline = OcLayer3Dark,
    outlineVariant = OcLayer2Dark,
    error = OcRed,
    onError = OcDeepDark,
)

private val LightScheme = lightColorScheme(
    primary = OcBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE5FF),
    onPrimaryContainer = Color(0xFF11296E),
    secondary = Color(0xFF9A5528),
    onSecondary = Color.White,
    background = OcDeepLight,
    onBackground = OcTextLight,
    surface = OcBaseLight,
    onSurface = OcTextLight,
    surfaceVariant = OcLayer2Light,
    onSurfaceVariant = OcMutedLight,
    outline = Color(0xFFD6D6D6),
    outlineVariant = OcLayer3Light,
    error = Color(0xFFB82D35),
    onError = Color.White,
)

private val OpenCodeTypography
    @Composable get() = MaterialTheme.typography.copy(
        bodyLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 14.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Normal,
        ),
        bodyMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Normal,
        ),
        bodySmall = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.Normal,
        ),
        titleLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 18.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        titleMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 15.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        titleSmall = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        labelLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Medium,
        ),
        labelMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
        ),
    )

@Composable
fun OpenCodeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = OpenCodeTypography,
        content = content,
    )
}
