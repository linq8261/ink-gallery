package com.ink.gallery.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/*
 * 水墨色板：宣纸作底、浓墨写字、朱砂点睛。
 * 只保留墨色的三个层次（浓墨 / 淡墨 / 墨线）+ 一方朱砂，不做第二种彩色。
 */

/** 宣纸白：页面底色，偏暖米白 */
val InkPaper = Color(0xFFF4F0E4)

/** 淡宣：卡片、缩略图占位等浅面，比纸面沉一档 */
val InkPaperDim = Color(0xFFE7E1D2)

/** 浓墨：正文与主色 */
val InkInk = Color(0xFF1D2126)

/** 淡墨：次级文字与图标 */
val InkInkLight = Color(0xFF545C64)

/** 墨线：分割线与描边 */
val InkHairline = Color(0xFFC3BAA6)

/** 极淡墨线：更轻的分隔 */
val InkHairlineSoft = Color(0xFFD9D2C1)

/** 朱砂：只用于选中/强调，像一方印章 */
val InkCinnabar = Color(0xFF993A2B)

/** 图片浏览页底色：墨黑，比纯黑柔和 */
val InkViewerBlack = Color(0xFF0D1012)

/*
 * 暗色（墨夜）对应的几个值，供 Theme.kt 组装 darkColorScheme。
 */
val InkNightBg = Color(0xFF0F1216)
val InkNightSurfaceDim = Color(0xFF1E2228)
val InkNightInk = Color(0xFFD6DBE0)
val InkNightInkLight = Color(0xFF9AA2AA)
val InkNightHairline = Color(0xFF363C44)
val InkNightCinnabar = Color(0xFFC97A67)

/**
 * 标题栏底色/前景色。浅色下是「宣纸底 + 墨字」，暗色下自动跟着反转，
 * 因此这里用 @Composable getter 而不是常量——调用点全都在 Composable 里。
 */
val InkTitleBar: Color
    @Composable get() = MaterialTheme.colorScheme.surface

val InkTitleBarContent: Color
    @Composable get() = MaterialTheme.colorScheme.onSurface
