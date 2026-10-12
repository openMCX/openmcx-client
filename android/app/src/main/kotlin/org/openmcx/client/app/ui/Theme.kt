//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.app.ui

import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The OpenMCX look. The brand (the logo's orange, warm ivory and ink) frames the screens; the floor and priority
 * colours are kept apart from it and mean the same on every phone and in both themes. Each has a fill (white text
 * on it, except peril) and an `On...` text colour for use on the screen background.
 */
@Immutable
data class McxColors(
    val dark: Boolean,
    val Ivory: Color,
    /** Raised surfaces: the floor card, rows, received message bubbles. */
    val Card: Color,
    val Ink: Color,
    val Muted: Color,
    val Line: Color,
    val Brand: Color,
    /** The top strip and sent message bubbles: a lighter shade of the logo's orange (a deep one in dark mode). */
    val Strip: Color,
    // Floor states: fills.
    val Idle: Color,
    val Requesting: Color,
    val Talking: Color,
    val Taken: Color,
    val Releasing: Color,
    val Stop: Color,
    /** Imminent peril: amber, with dark text on it. */
    val Peril: Color,
    // The same states as text on the background.
    val OnIdle: Color,
    val OnRequesting: Color,
    val OnTalking: Color,
    val OnTaken: Color,
    val OnReleasing: Color,
    val OnStop: Color,
    // Notices: something to know (warn) and all well (ok).
    val WarnBg: Color,
    val WarnInk: Color,
    val OkBg: Color,
    val OkInk: Color,
    val OkDot: Color,
)

val LightMcx = McxColors(
    dark = false,
    Ivory = Color(0xFFFBF6EE), Card = Color(0xFFFFFFFF), Ink = Color(0xFF1C1917), Muted = Color(0xFF57534E),
    Line = Color(0xFFE7DFD3), Brand = Color(0xFFFFCC80), Strip = Color(0xFFFFE6C4),
    Idle = Color(0xFF292524), Requesting = Color(0xFFB45309), Talking = Color(0xFFC2410C), Taken = Color(0xFF1D4ED8),
    Releasing = Color(0xFF57534E), Stop = Color(0xFFB91C1C), Peril = Color(0xFFF59E0B),
    OnIdle = Color(0xFF1C1917), OnRequesting = Color(0xFF92400E), OnTalking = Color(0xFFC2410C), OnTaken = Color(0xFF1D4ED8),
    OnReleasing = Color(0xFF57534E), OnStop = Color(0xFFB91C1C),
    WarnBg = Color(0xFFFEF3C7), WarnInk = Color(0xFF78350F), OkBg = Color(0xFFDCFCE7), OkInk = Color(0xFF14532D), OkDot = Color(0xFF16A34A),
)

val DarkMcx = McxColors(
    dark = true,
    Ivory = Color(0xFF181412), Card = Color(0xFF231E1B), Ink = Color(0xFFF5EFE6), Muted = Color(0xFFB5ADA6),
    Line = Color(0xFF3A322C), Brand = Color(0xFFFFCC80), Strip = Color(0xFF3A2A18),
    // Fills keep their hue; idle and releasing are lifted so the PTT key stands out from the dark background.
    Idle = Color(0xFF4A433E), Requesting = Color(0xFFB45309), Talking = Color(0xFFC2410C), Taken = Color(0xFF1D4ED8),
    Releasing = Color(0xFF6B635D), Stop = Color(0xFFB91C1C), Peril = Color(0xFFF59E0B),
    OnIdle = Color(0xFFF5EFE6), OnRequesting = Color(0xFFFBBF24), OnTalking = Color(0xFFFB923C), OnTaken = Color(0xFF93C5FD),
    OnReleasing = Color(0xFFD6D3D1), OnStop = Color(0xFFF87171),
    WarnBg = Color(0xFF3B2508), WarnInk = Color(0xFFFDE68A), OkBg = Color(0xFF0B2E1A), OkInk = Color(0xFF86EFAC), OkDot = Color(0xFF22C55E),
)

val LocalMcx = staticCompositionLocalOf { LightMcx }

/** The theme's colours, in any composable. */
val Mcx: McxColors
    @Composable @ReadOnlyComposable get() = LocalMcx.current

/**
 * The condensed face (Roboto Condensed on most phones) for what must be read at a glance: who the call is with, the
 * floor state and the PTT key. Body text stays in the phone's normal sans serif.
 */
val Condensed = FontFamily(
    Font(DeviceFontFamilyName("sans-serif-condensed"), FontWeight.Normal),
    Font(DeviceFontFamilyName("sans-serif-condensed"), FontWeight.Medium),
    Font(DeviceFontFamilyName("sans-serif-condensed"), FontWeight.SemiBold),
    Font(DeviceFontFamilyName("sans-serif-condensed"), FontWeight.Bold),
)

/** Figures of equal width, so clocks and timers do not jitter as they count. */
val Tabular = TextStyle(fontFeatureSettings = "tnum")

private val typography = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontFamily = Condensed, fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontFamily = Condensed, fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 0.2.sp),
    )
}

/** No dynamic colour: the brand and the state colours are the same on every phone. */
@Composable
fun OpenMcxTheme(dark: Boolean = false, content: @Composable () -> Unit) {
    val mcx = if (dark) DarkMcx else LightMcx
    val scheme = if (dark) darkColorScheme(
        primary = mcx.Ink, onPrimary = mcx.Ivory, secondary = mcx.Muted, background = mcx.Ivory, onBackground = mcx.Ink,
        surface = mcx.Ivory, onSurface = mcx.Ink, surfaceVariant = mcx.Card, onSurfaceVariant = mcx.Muted,
        surfaceContainer = mcx.Card, surfaceContainerHigh = mcx.Card, surfaceContainerHighest = mcx.Line,
        surfaceContainerLow = mcx.Card, outline = mcx.Muted, outlineVariant = mcx.Line, error = mcx.OnStop,
        secondaryContainer = mcx.Strip, onSecondaryContainer = mcx.Ink,
    ) else lightColorScheme(
        primary = mcx.Ink, onPrimary = Color.White, secondary = mcx.Muted, background = mcx.Ivory, onBackground = mcx.Ink,
        surface = mcx.Ivory, onSurface = mcx.Ink, surfaceVariant = mcx.Card, onSurfaceVariant = mcx.Muted,
        surfaceContainer = mcx.Card, surfaceContainerHigh = mcx.Card, surfaceContainerHighest = mcx.Line,
        surfaceContainerLow = mcx.Card, outline = mcx.Muted, outlineVariant = mcx.Line, error = mcx.Stop,
        secondaryContainer = mcx.Strip, onSecondaryContainer = mcx.Ink,
    )
    CompositionLocalProvider(LocalMcx provides mcx) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

/** The phone's "Remove animations" setting: then the PTT pulse and level bars stand still. */
@Composable
fun reducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}
