package com.ink.gallery.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import com.ink.gallery.R

private val DarkColorScheme = darkColorScheme(
    primary = InkBlue80,
    secondary = InkSlate80,
    tertiary = InkClay80
)

private val LightColorScheme = lightColorScheme(
    primary = InkBlue40,
    secondary = InkSlate40,
    tertiary = InkClay40
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
    // 这是「splash 消失的那一瞬不闪一下」的前提。默认 lightColorScheme 的值与本资源一致，
    // 所以这里不改动任何现有视觉。
    val background = colorResource(R.color.ink_background)
    val colorScheme = baseScheme.copy(background = background, surface = background)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
