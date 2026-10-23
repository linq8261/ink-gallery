package com.ink.gallery.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay

/**
 * 延迟出现的加载转圈。
 *
 * 冷启动命中磁盘快照时，列表通常几十毫秒内就位；这时候先画一个转圈、
 * 下一帧再抹掉，视觉上就是「闪一下圈」，比什么都不显示更像卡了。
 * 所以前 [delayMs] 毫秒只留空白背景，超过才算真在等（首次安装、超大图库首次扫描）。
 *
 * 调用方只需在「数据未就绪」分支里放它——分支退出即组件销毁，计时自动取消。
 */
@Composable
fun DelayedProgressIndicator(
    modifier: Modifier = Modifier,
    delayMs: Long = DEFAULT_DELAY_MS
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(delayMs)
        visible = true
    }
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (visible) CircularProgressIndicator()
    }
}

private const val DEFAULT_DELAY_MS = 300L
