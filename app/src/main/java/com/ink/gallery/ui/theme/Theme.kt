package com.ink.gallery.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import com.ink.gallery.R

/**
 * 圆角一律收到很小：水墨风的容器应该像纸边、印石那样方方正正，
 * Material 默认的胶囊按钮（Button 用的 extraLarge）在这里会显得很「App 味」。
 */
private val InkShapes = Shapes(
    extraSmall = RoundedCornerShape(1.dp),
    small = RoundedCornerShape(2.dp),
    medium = RoundedCornerShape(3.dp),
    large = RoundedCornerShape(4.dp),
    extraLarge = RoundedCornerShape(6.dp)
)

private val DarkColorScheme = darkColorScheme(
    primary = InkNightInk,
    onPrimary = InkNightBg,
    primaryContainer = InkNightSurfaceDim,
    onPrimaryContainer = InkNightInk,
    secondary = InkNightInkLight,
    onSecondary = InkNightBg,
    secondaryContainer = InkNightSurfaceDim,
    onSecondaryContainer = InkNightInk,
    tertiary = InkNightCinnabar,
    onTertiary = InkNightBg,
    background = InkNightBg,
    onBackground = InkNightInk,
    surface = InkNightBg,
    onSurface = InkNightInk,
    surfaceVariant = InkNightSurfaceDim,
    onSurfaceVariant = InkNightInkLight,
    surfaceContainerLowest = Color(0xFF0F1114),
    surfaceContainerLow = Color(0xFF181B1F),
    surfaceContainer = Color(0xFF1B1E22),
    surfaceContainerHigh = Color(0xFF23262B),
    surfaceContainerHighest = Color(0xFF2B2F34),
    outline = Color(0xFF565B62),
    outlineVariant = InkNightHairline
)

private val LightColorScheme = lightColorScheme(
    primary = InkInk,
    onPrimary = InkPaper,
    primaryContainer = InkPaperDim,
    onPrimaryContainer = InkInk,
    secondary = InkInkLight,
    onSecondary = InkPaper,
    secondaryContainer = InkPaperDim,
    onSecondaryContainer = InkInk,
    tertiary = InkCinnabar,
    onTertiary = InkPaper,
    tertiaryContainer = Color(0xFFEEDCD6),
    onTertiaryContainer = Color(0xFF52221A),
    background = InkPaper,
    onBackground = InkInk,
    surface = InkPaper,
    onSurface = InkInk,
    surfaceVariant = InkPaperDim,
    onSurfaceVariant = InkInkLight,
    surfaceContainerLowest = Color(0xFFFBF8EF),
    surfaceContainerLow = InkPaper,
    surfaceContainer = InkPaperDim,
    surfaceContainerHigh = Color(0xFFE0D9C7),
    surfaceContainerHighest = Color(0xFFD5CDB9),
    outline = Color(0xFFB0A793),
    outlineVariant = InkHairline
)

@Composable
fun InkGalleryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val baseScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    // 背景/表面色与启动窗口、窗口底色取同一个资源（values/colors.xml 的 ink_background），
    // 这是「splash 消失的那一瞬不闪一下」的前提。
    val background = colorResource(R.color.ink_background)
    val colorScheme = baseScheme.copy(background = background, surface = background)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = InkShapes,
        content = content
    )
}
