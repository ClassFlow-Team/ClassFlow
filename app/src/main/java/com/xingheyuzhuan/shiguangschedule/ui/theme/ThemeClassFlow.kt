package com.xingheyuzhuan.shiguangschedule.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.lerp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.graphics.drawable.toDrawable
import kotlinx.coroutines.delay
import java.time.LocalTime

// ── Sakura Pink (默认主题) ──
// Light
val SakuraPrimary = Color(0xFFC2185B)
val SakuraOnPrimary = Color(0xFFFFFFFF)
val SakuraPrimaryContainer = Color(0xFFFFD9E2)
val SakuraOnPrimaryContainer = Color(0xFF3E001D)
val SakuraSecondary = Color(0xFF9C4164)
val SakuraOnSecondary = Color(0xFFFFFFFF)
val SakuraSecondaryContainer = Color(0xFFFFD9E2)
val SakuraOnSecondaryContainer = Color(0xFF3E001D)
val SakuraTertiary = Color(0xFF8B5065)
val SakuraOnTertiary = Color(0xFFFFFFFF)
val SakuraTertiaryContainer = Color(0xFFFFD9E2)
val SakuraOnTertiaryContainer = Color(0xFF3A0722)
val SakuraBackground = Color(0xFFFFF0F3)
val SakuraOnBackground = Color(0xFF2B1520)
val SakuraSurface = Color(0xFFFFF5F7)
val SakuraOnSurface = Color(0xFF2B1520)
val SakuraSurfaceVariant = Color(0xFFF4D8DF)
val SakuraOnSurfaceVariant = Color(0xFF5C3A47)
val SakuraOutline = Color(0xFF9B7585)
val SakuraError = Color(0xFFBA1A1A)
val SakuraOnError = Color(0xFFFFFFFF)
val SakuraErrorContainer = Color(0xFFFFDAD6)
val SakuraOnErrorContainer = Color(0xFF410002)

// Dark
val SakuraPrimaryDark = Color(0xFFFFB1C8)
val SakuraOnPrimaryDark = Color(0xFF650033)
val SakuraPrimaryContainerDark = Color(0xFF8C0D48)
val SakuraOnPrimaryContainerDark = Color(0xFFFFD9E2)
val SakuraSecondaryDark = Color(0xFFEBB1C7)
val SakuraOnSecondaryDark = Color(0xFF4A1531)
val SakuraSecondaryContainerDark = Color(0xFF652B48)
val SakuraOnSecondaryContainerDark = Color(0xFFFFD9E2)
val SakuraTertiaryDark = Color(0xFFE8B0C6)
val SakuraOnTertiaryDark = Color(0xFF45192C)
val SakuraTertiaryContainerDark = Color(0xFF5D3049)
val SakuraOnTertiaryContainerDark = Color(0xFFFFD9E2)
val SakuraBackgroundDark = Color(0xFF221218)
val SakuraOnBackgroundDark = Color(0xFFF2DEE4)
val SakuraSurfaceDark = Color(0xFF28141C)
val SakuraOnSurfaceDark = Color(0xFFF2DEE4)
val SakuraSurfaceVariantDark = Color(0xFF5C3A47)
val SakuraOnSurfaceVariantDark = Color(0xFFDFBDC8)
val SakuraOutlineDark = Color(0xFFB08E9A)
val SakuraErrorDark = Color(0xFFFFB4AB)
val SakuraOnErrorDark = Color(0xFF690005)
val SakuraErrorContainerDark = Color(0xFF93000A)
val SakuraOnErrorContainerDark = Color(0xFFFFDAD6)

// ── Afternoon Blue-Gray (12:00-18:00) ──
// Light
val AfternoonPrimary = Color(0xFF5C7A9E)
val AfternoonOnPrimary = Color(0xFFFFFFFF)
val AfternoonPrimaryContainer = Color(0xFFD8E6F7)
val AfternoonOnPrimaryContainer = Color(0xFF1A2E42)
val AfternoonSecondary = Color(0xFF6B8AA8)
val AfternoonOnSecondary = Color(0xFFFFFFFF)
val AfternoonSecondaryContainer = Color(0xFFD8E6F7)
val AfternoonOnSecondaryContainer = Color(0xFF1A2E42)
val AfternoonTertiary = Color(0xFF7A8FA5)
val AfternoonOnTertiary = Color(0xFFFFFFFF)
val AfternoonTertiaryContainer = Color(0xFFD8E6F7)
val AfternoonOnTertiaryContainer = Color(0xFF1F3448)
val AfternoonBackground = Color(0xFFF0F5FA)
val AfternoonOnBackground = Color(0xFF1A2530)
val AfternoonSurface = Color(0xFFF5F8FC)
val AfternoonOnSurface = Color(0xFF1A2530)
val AfternoonSurfaceVariant = Color(0xFFDDE5EF)
val AfternoonOnSurfaceVariant = Color(0xFF42505E)
val AfternoonOutline = Color(0xFF7A8896)
val AfternoonError = Color(0xFFBA1A1A)
val AfternoonOnError = Color(0xFFFFFFFF)
val AfternoonErrorContainer = Color(0xFFFFDAD6)
val AfternoonOnErrorContainer = Color(0xFF410002)

// Dark
val AfternoonPrimaryDark = Color(0xFFACC7E8)
val AfternoonOnPrimaryDark = Color(0xFF1A3A5C)
val AfternoonPrimaryContainerDark = Color(0xFF3D5A7E)
val AfternoonOnPrimaryContainerDark = Color(0xFFD8E6F7)
val AfternoonSecondaryDark = Color(0xFFB8D0EA)
val AfternoonOnSecondaryDark = Color(0xFF253E58)
val AfternoonSecondaryContainerDark = Color(0xFF3D5570)
val AfternoonOnSecondaryContainerDark = Color(0xFFD8E6F7)
val AfternoonTertiaryDark = Color(0xFFC2D5E8)
val AfternoonOnTertiaryDark = Color(0xFF2A4258)
val AfternoonTertiaryContainerDark = Color(0xFF425970)
val AfternoonOnTertiaryContainerDark = Color(0xFFD8E6F7)
val AfternoonBackgroundDark = Color(0xFF121A24)
val AfternoonOnBackgroundDark = Color(0xFFE2E8F0)
val AfternoonSurfaceDark = Color(0xFF1A2332)
val AfternoonOnSurfaceDark = Color(0xFFE2E8F0)
val AfternoonSurfaceVariantDark = Color(0xFF42505E)
val AfternoonOnSurfaceVariantDark = Color(0xFFC2CDD8)
val AfternoonOutlineDark = Color(0xFF8C98A6)
val AfternoonErrorDark = Color(0xFFFFB4AB)
val AfternoonOnErrorDark = Color(0xFF690005)
val AfternoonErrorContainerDark = Color(0xFF93000A)
val AfternoonOnErrorContainerDark = Color(0xFFFFDAD6)

// ── Evening Lavender (18:00-6:00) ──
// Light
val EveningPrimary = Color(0xFF9B7EBD)
val EveningOnPrimary = Color(0xFFFFFFFF)
val EveningPrimaryContainer = Color(0xFFE8DFF5)
val EveningOnPrimaryContainer = Color(0xFF2E1A42)
val EveningSecondary = Color(0xFFAA8DC4)
val EveningOnSecondary = Color(0xFFFFFFFF)
val EveningSecondaryContainer = Color(0xFFE8DFF5)
val EveningOnSecondaryContainer = Color(0xFF2E1A42)
val EveningTertiary = Color(0xFFB598C8)
val EveningOnTertiary = Color(0xFFFFFFFF)
val EveningTertiaryContainer = Color(0xFFE8DFF5)
val EveningOnTertiaryContainer = Color(0xFF331F45)
val EveningBackground = Color(0xFFF5F0FA)
val EveningOnBackground = Color(0xFF281A35)
val EveningSurface = Color(0xFFF8F5FC)
val EveningOnSurface = Color(0xFF281A35)
val EveningSurfaceVariant = Color(0xFFE5DDEF)
val EveningOnSurfaceVariant = Color(0xFF4A3E5C)
val EveningOutline = Color(0xFF8A7A9B)
val EveningError = Color(0xFFBA1A1A)
val EveningOnError = Color(0xFFFFFFFF)
val EveningErrorContainer = Color(0xFFFFDAD6)
val EveningOnErrorContainer = Color(0xFF410002)

// Dark
val EveningPrimaryDark = Color(0xFFD0B8E8)
val EveningOnPrimaryDark = Color(0xFF3A1A5C)
val EveningPrimaryContainerDark = Color(0xFF5D3E7E)
val EveningOnPrimaryContainerDark = Color(0xFFE8DFF5)
val EveningSecondaryDark = Color(0xFFDCC5EA)
val EveningOnSecondaryDark = Color(0xFF402558)
val EveningSecondaryContainerDark = Color(0xFF583D70)
val EveningOnSecondaryContainerDark = Color(0xFFE8DFF5)
val EveningTertiaryDark = Color(0xFFE2CCE8)
val EveningOnTertiaryDark = Color(0xFF452A58)
val EveningTertiaryContainerDark = Color(0xFF5D4270)
val EveningOnTertiaryContainerDark = Color(0xFFE8DFF5)
val EveningBackgroundDark = Color(0xFF1A1224)
val EveningOnBackgroundDark = Color(0xFFECE2F0)
val EveningSurfaceDark = Color(0xFF231A32)
val EveningOnSurfaceDark = Color(0xFFECE2F0)
val EveningSurfaceVariantDark = Color(0xFF4A3E5C)
val EveningOnSurfaceVariantDark = Color(0xFFCEC2D8)
val EveningOutlineDark = Color(0xFF9E8CA6)
val EveningErrorDark = Color(0xFFFFB4AB)
val EveningOnErrorDark = Color(0xFF690005)
val EveningErrorContainerDark = Color(0xFF93000A)
val EveningOnErrorContainerDark = Color(0xFFFFDAD6)

private val DarkColorScheme = darkColorScheme(
    primary = SakuraPrimaryDark,
    onPrimary = SakuraOnPrimaryDark,
    primaryContainer = SakuraPrimaryContainerDark,
    onPrimaryContainer = SakuraOnPrimaryContainerDark,
    secondary = SakuraSecondaryDark,
    onSecondary = SakuraOnSecondaryDark,
    secondaryContainer = SakuraSecondaryContainerDark,
    onSecondaryContainer = SakuraOnSecondaryContainerDark,
    tertiary = SakuraTertiaryDark,
    onTertiary = SakuraOnTertiaryDark,
    tertiaryContainer = SakuraTertiaryContainerDark,
    onTertiaryContainer = SakuraOnTertiaryContainerDark,
    error = SakuraErrorDark,
    onError = SakuraOnErrorDark,
    errorContainer = SakuraErrorContainerDark,
    onErrorContainer = SakuraOnErrorContainerDark,
    background = SakuraBackgroundDark,
    onBackground = SakuraOnBackgroundDark,
    surface = SakuraSurfaceDark,
    onSurface = SakuraOnSurfaceDark,
    surfaceVariant = SakuraSurfaceVariantDark,
    onSurfaceVariant = SakuraOnSurfaceVariantDark,
    outline = SakuraOutlineDark
)

private val LightColorScheme = lightColorScheme(
    primary = SakuraPrimary,
    onPrimary = SakuraOnPrimary,
    primaryContainer = SakuraPrimaryContainer,
    onPrimaryContainer = SakuraOnPrimaryContainer,
    secondary = SakuraSecondary,
    onSecondary = SakuraOnSecondary,
    secondaryContainer = SakuraSecondaryContainer,
    onSecondaryContainer = SakuraOnSecondaryContainer,
    tertiary = SakuraTertiary,
    onTertiary = SakuraOnTertiary,
    tertiaryContainer = SakuraTertiaryContainer,
    onTertiaryContainer = SakuraOnTertiaryContainer,
    error = SakuraError,
    onError = SakuraOnError,
    errorContainer = SakuraErrorContainer,
    onErrorContainer = SakuraOnErrorContainer,
    background = SakuraBackground,
    onBackground = SakuraOnBackground,
    surface = SakuraSurface,
    onSurface = SakuraOnSurface,
    surfaceVariant = SakuraSurfaceVariant,
    onSurfaceVariant = SakuraOnSurfaceVariant,
    outline = SakuraOutline
)

private val AfternoonLightColorScheme = lightColorScheme(
    primary = AfternoonPrimary,
    onPrimary = AfternoonOnPrimary,
    primaryContainer = AfternoonPrimaryContainer,
    onPrimaryContainer = AfternoonOnPrimaryContainer,
    secondary = AfternoonSecondary,
    onSecondary = AfternoonOnSecondary,
    secondaryContainer = AfternoonSecondaryContainer,
    onSecondaryContainer = AfternoonOnSecondaryContainer,
    tertiary = AfternoonTertiary,
    onTertiary = AfternoonOnTertiary,
    tertiaryContainer = AfternoonTertiaryContainer,
    onTertiaryContainer = AfternoonOnTertiaryContainer,
    error = AfternoonError,
    onError = AfternoonOnError,
    errorContainer = AfternoonErrorContainer,
    onErrorContainer = AfternoonOnErrorContainer,
    background = AfternoonBackground,
    onBackground = AfternoonOnBackground,
    surface = AfternoonSurface,
    onSurface = AfternoonOnSurface,
    surfaceVariant = AfternoonSurfaceVariant,
    onSurfaceVariant = AfternoonOnSurfaceVariant,
    outline = AfternoonOutline
)

private val AfternoonDarkColorScheme = darkColorScheme(
    primary = AfternoonPrimaryDark,
    onPrimary = AfternoonOnPrimaryDark,
    primaryContainer = AfternoonPrimaryContainerDark,
    onPrimaryContainer = AfternoonOnPrimaryContainerDark,
    secondary = AfternoonSecondaryDark,
    onSecondary = AfternoonOnSecondaryDark,
    secondaryContainer = AfternoonSecondaryContainerDark,
    onSecondaryContainer = AfternoonOnSecondaryContainerDark,
    tertiary = AfternoonTertiaryDark,
    onTertiary = AfternoonOnTertiaryDark,
    tertiaryContainer = AfternoonTertiaryContainerDark,
    onTertiaryContainer = AfternoonOnTertiaryContainerDark,
    error = AfternoonErrorDark,
    onError = AfternoonOnErrorDark,
    errorContainer = AfternoonErrorContainerDark,
    onErrorContainer = AfternoonOnErrorContainerDark,
    background = AfternoonBackgroundDark,
    onBackground = AfternoonOnBackgroundDark,
    surface = AfternoonSurfaceDark,
    onSurface = AfternoonOnSurfaceDark,
    surfaceVariant = AfternoonSurfaceVariantDark,
    onSurfaceVariant = AfternoonOnSurfaceVariantDark,
    outline = AfternoonOutlineDark
)

private val EveningLightColorScheme = lightColorScheme(
    primary = EveningPrimary,
    onPrimary = EveningOnPrimary,
    primaryContainer = EveningPrimaryContainer,
    onPrimaryContainer = EveningOnPrimaryContainer,
    secondary = EveningSecondary,
    onSecondary = EveningOnSecondary,
    secondaryContainer = EveningSecondaryContainer,
    onSecondaryContainer = EveningOnSecondaryContainer,
    tertiary = EveningTertiary,
    onTertiary = EveningOnTertiary,
    tertiaryContainer = EveningTertiaryContainer,
    onTertiaryContainer = EveningOnTertiaryContainer,
    error = EveningError,
    onError = EveningOnError,
    errorContainer = EveningErrorContainer,
    onErrorContainer = EveningOnErrorContainer,
    background = EveningBackground,
    onBackground = EveningOnBackground,
    surface = EveningSurface,
    onSurface = EveningOnSurface,
    surfaceVariant = EveningSurfaceVariant,
    onSurfaceVariant = EveningOnSurfaceVariant,
    outline = EveningOutline
)

private val EveningDarkColorScheme = darkColorScheme(
    primary = EveningPrimaryDark,
    onPrimary = EveningOnPrimaryDark,
    primaryContainer = EveningPrimaryContainerDark,
    onPrimaryContainer = EveningOnPrimaryContainerDark,
    secondary = EveningSecondaryDark,
    onSecondary = EveningOnSecondaryDark,
    secondaryContainer = EveningSecondaryContainerDark,
    onSecondaryContainer = EveningOnSecondaryContainerDark,
    tertiary = EveningTertiaryDark,
    onTertiary = EveningOnTertiaryDark,
    tertiaryContainer = EveningTertiaryContainerDark,
    onTertiaryContainer = EveningOnTertiaryContainerDark,
    error = EveningErrorDark,
    onError = EveningOnErrorDark,
    errorContainer = EveningErrorContainerDark,
    onErrorContainer = EveningOnErrorContainerDark,
    background = EveningBackgroundDark,
    onBackground = EveningOnBackgroundDark,
    surface = EveningSurfaceDark,
    onSurface = EveningOnSurfaceDark,
    surfaceVariant = EveningSurfaceVariantDark,
    onSurfaceVariant = EveningOnSurfaceVariantDark,
    outline = EveningOutlineDark
)

/**
 * 定义一个用于全局同步深色模式状态的 Local 变量
 */

// ── 从种子色派生完整 Material 配色（避免仅覆盖 primary 导致 secondaryContainer 等残留紫色调） ──

private fun Color.lighten(fraction: Float): Color = lerp(this, Color.White, fraction)

private fun Color.darken(fraction: Float): Color = lerp(this, Color.Black, fraction)

/** 浅色模式：由种子色派生强调色；文字/背景使用 M3 中性色（避免文字偏紫，与上游默认一致） */
fun seedToLightColorScheme(seed: Color): ColorScheme = lightColorScheme(
    primary = seed,
    onPrimary = Color.White,
    primaryContainer = seed.lighten(0.78f),
    onPrimaryContainer = seed.darken(0.22f),
    secondary = seed.darken(0.18f),
    onSecondary = Color.White,
    secondaryContainer = seed.lighten(0.84f),
    onSecondaryContainer = seed.darken(0.28f),
    tertiary = seed.darken(0.32f),
    onTertiary = Color.White,
    tertiaryContainer = seed.lighten(0.72f),
    onTertiaryContainer = seed.darken(0.38f),
    // M3 baseline 中性色（浅色）
    background = Color(0xFFFFFBFE),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFFFBFE),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF79747E)
)

/** 深色模式：由种子色派生强调色；文字/背景使用 M3 中性色 */
fun seedToDarkColorScheme(seed: Color): ColorScheme = darkColorScheme(
    primary = seed.lighten(0.25f),
    onPrimary = seed.darken(0.55f),
    primaryContainer = seed.darken(0.45f),
    onPrimaryContainer = seed.lighten(0.62f),
    secondary = seed.lighten(0.55f),
    onSecondary = seed.darken(0.55f),
    secondaryContainer = seed.darken(0.38f),
    onSecondaryContainer = seed.lighten(0.70f),
    tertiary = seed.lighten(0.45f),
    onTertiary = seed.darken(0.55f),
    tertiaryContainer = seed.darken(0.30f),
    onTertiaryContainer = seed.lighten(0.62f),
    // M3 baseline 中性色（深色）
    background = Color(0xFF141218),
    onBackground = Color(0xFFE6E0E9),
    surface = Color(0xFF141218),
    onSurface = Color(0xFFE6E0E9),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF938F99)
)

@Composable
fun ClassFlowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    timeBasedTheme: Boolean = true,
    customLightPrimary: Color = Purple40,
    customDarkPrimary: Color = Purple80,
    content: @Composable () -> Unit
) {
    // Track current time period with state
    var currentTimePeriod by remember {
        mutableStateOf(TimePeriod.fromHour(LocalTime.now().hour))
    }

    // Update time period when crossing boundaries
    LaunchedEffect(Unit) {
        while (true) {
            val now = LocalTime.now()
            val newPeriod = TimePeriod.fromHour(now.hour)
            if (newPeriod != currentTimePeriod) {
                currentTimePeriod = newPeriod
            }

            // Smart delay: Check every minute near boundaries, every 15 minutes otherwise
            val minutesUntilNextHour = 60 - now.minute
            val delayMinutes = if (minutesUntilNextHour <= 5) 1 else 15
            delay(delayMinutes * 60 * 1000L)
        }
    }

    val colorScheme = when {
        // ClassFlow 特色优先：Sakura 时间色板（早/中/晚）不依赖动态取色
        timeBasedTheme -> {
            if (darkTheme) {
                when (currentTimePeriod) {
                    TimePeriod.MORNING -> DarkColorScheme
                    TimePeriod.AFTERNOON -> AfternoonDarkColorScheme
                    TimePeriod.EVENING -> EveningDarkColorScheme
                }
            } else {
                when (currentTimePeriod) {
                    TimePeriod.MORNING -> LightColorScheme
                    TimePeriod.AFTERNOON -> AfternoonLightColorScheme
                    TimePeriod.EVENING -> EveningLightColorScheme
                }
            }
        }
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            // 上游样式：Android 12+ 动态取色 (Material You)
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> {
            // 上游样式：由自定义种子色派生完整配色（所有容器色跟随种子色，不残留紫色调）
            if (darkTheme) seedToDarkColorScheme(customDarkPrimary)
            else seedToLightColorScheme(customLightPrimary)
        }
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window

            // Fully transparent window background for edge-to-edge glass effects
            window.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())

            @Suppress("DEPRECATION")
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

