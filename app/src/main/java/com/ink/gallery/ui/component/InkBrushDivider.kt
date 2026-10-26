package com.ink.gallery.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ink.gallery.ui.theme.InkInk

/**
 * 一笔墨线：横向渐变的浓淡变化，模拟毛笔落纸时起笔轻、行笔重、收笔淡的压感。
 *
 * 比均匀的 1dp 实线更像手写，也比它更有「水墨」的存在感，
 * 用作标题栏、底部操作栏的分隔。
 */
@Composable
fun InkBrushDivider(
    modifier: Modifier = Modifier,
    thickness: Dp = 1.5.dp,
    color: Color = InkInk
) {
    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(thickness)
            .background(
                Brush.horizontalGradient(
                    0f to color.copy(alpha = 0.20f),
                    0.12f to color.copy(alpha = 0.60f),
                    0.38f to color.copy(alpha = 0.34f),
                    0.62f to color.copy(alpha = 0.66f),
                    0.86f to color.copy(alpha = 0.30f),
                    1f to color.copy(alpha = 0.16f)
                )
            )
    )
}
