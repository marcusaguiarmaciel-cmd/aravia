package app.aravia.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.aravia.R

/** Barlow, the same type as the iOS app. */
val Barlow = FontFamily(
    Font(R.font.barlow_regular, FontWeight.Normal),
    Font(R.font.barlow_medium, FontWeight.Medium),
    Font(R.font.barlow_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_bold, FontWeight.Bold),
)

/** Aravia's palette for the screens before the app (night sky, straw, annatto), as on iOS. */
object Look {
    val night = Color(0xFF16202B)
    val nightDeep = Color(0xFF0E151C)
    val straw = Color(0xFFE9D9B8)
    val urucum = Color(0xFFE05A3A)
    val ochre = Color(0xFFC98A1F)
    val field = Color(0xFF24303D)
    val fieldLine = Color(0x2EE9D9B8)
}

/** The app's colours by role, resolved for light or dark from the shared tokens. */
@Immutable
data class AraviaColors(
    val canvas: Color, val surface: Color, val raised: Color,
    val textPrimary: Color, val textSecondary: Color,
    val action: Color, val onAction: Color,
    val good: Color, val fair: Color, val low: Color,
    val activity: Color, val heart: Color,
    val dark: Boolean,
)

private fun colors(dark: Boolean) = if (dark) AraviaColors(
    Tokens.canvasDark, Tokens.surfaceDark, Tokens.raisedDark, Tokens.textPrimaryDark, Tokens.textSecondaryDark,
    Tokens.actionDark, Tokens.onActionDark, Tokens.goodDark, Tokens.fairDark, Tokens.lowDark,
    Tokens.activityDark, Tokens.heartDark, true,
) else AraviaColors(
    Tokens.canvasLight, Tokens.surfaceLight, Tokens.raisedLight, Tokens.textPrimaryLight, Tokens.textSecondaryLight,
    Tokens.actionLight, Tokens.onActionLight, Tokens.goodLight, Tokens.fairLight, Tokens.lowLight,
    Tokens.activityLight, Tokens.heartLight, false,
)

val LocalAravia = staticCompositionLocalOf { colors(false) }

object Aravia {
    val colors: AraviaColors @Composable get() = LocalAravia.current
}

@Composable
fun AraviaTheme(content: @Composable () -> Unit) {
    val mode by Appearance.mode.collectAsState()
    val dark = when (mode) {
        Appearance.Mode.SYSTEM -> isSystemInDarkTheme()
        Appearance.Mode.LIGHT -> false
        Appearance.Mode.DARK -> true
    }
    val c = colors(dark)
    val scheme = if (dark) darkColorScheme(
        primary = c.action, onPrimary = c.onAction, background = c.canvas, surface = c.surface,
        onBackground = c.textPrimary, onSurface = c.textPrimary, secondary = c.activity,
    ) else lightColorScheme(
        primary = c.action, onPrimary = c.onAction, background = c.canvas, surface = c.surface,
        onBackground = c.textPrimary, onSurface = c.textPrimary, secondary = c.activity,
    )
    val base = Typography()
    fun TextStyle.barlow() = copy(fontFamily = Barlow)
    val type = Typography(
        displayLarge = base.displayLarge.barlow(), displayMedium = base.displayMedium.barlow(),
        headlineLarge = base.headlineLarge.barlow().copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.barlow().copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.barlow().copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.barlow().copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.barlow().copy(fontSize = 17.sp), bodyMedium = base.bodyMedium.barlow(),
        bodySmall = base.bodySmall.barlow(), labelLarge = base.labelLarge.barlow().copy(fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.barlow(), labelSmall = base.labelSmall.barlow(),
    )
    CompositionLocalProvider(LocalAravia provides c) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
