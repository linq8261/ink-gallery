package com.ink.gallery.feature.viewer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.ink.gallery.data.model.PhotoItem
import kotlinx.coroutines.withTimeoutOrNull

private const val MAX_SCALE = 6f
private const val DOUBLE_TAP_SCALE = 2.5f

@Composable
fun ZoomableImage(
    photo: PhotoItem,
    onTap: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** 这张图是不是实况图（长按可播放） */
    liveAvailable: Boolean = false,
    /** 长按达到阈值：开始播放实况片段 */
    onLivePressStart: () -> Unit = {},
    /** 松手或被滑动打断：停止播放并回到静态图 */
    onLivePressEnd: () -> Unit = {}
) {
    var scale by remember(photo.id) { mutableFloatStateOf(1f) }
    var offset by remember(photo.id) { mutableStateOf(Offset.Zero) }

    LaunchedEffect(scale) {
        onZoomChanged(scale > 1.01f)
    }

    // 长按回调在 pointerInput 里持有，用 State 包一层避免闭包捕获旧的 lambda
    val liveStart = rememberUpdatedState(onLivePressStart)
    val liveEnd = rememberUpdatedState(onLivePressEnd)

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(photo.uri)
                .build(),
            contentDescription = photo.displayName,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
                .pointerInput(photo.id) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            // 未放大时不消费事件，交给外层 Pager 做翻页
                            if (scale > 1f || zoomChange > 1.01f) {
                                val newScale = (scale * zoomChange).coerceIn(1f, MAX_SCALE)
                                scale = newScale
                                offset = if (newScale <= 1.001f) Offset.Zero else offset + panChange
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(photo.id, liveAvailable) {
                    val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                    // detectTapGestures 在没提供 onLongPress 时，长按松手仍会补发一次 onTap，
                    // 「长按播放实况」就会顺带把工具栏切一次。这里用「本次手势是长按」的标记
                    // 把那次 onTap 吞掉；标记在下一次手指按下的瞬间复位，
                    // 所以按住多久都有效，也不影响之后的单击。
                    var wasLongPress = false
                    detectTapGestures(
                        onTap = {
                            if (wasLongPress) {
                                wasLongPress = false
                            } else {
                                onTap()
                            }
                        },
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = DOUBLE_TAP_SCALE
                            }
                        },
                        // 长按播放实况：按住满 longPressTimeout 起播，松手（或被滑动打断）立即停。
                        // tryAwaitRelease 会一直挂到手指抬起，超时即说明「按住不放」。
                        onPress = {
                            // 每次按下都会走到这里，新的一次手势先把标记清掉
                            wasLongPress = false
                            val releasedInTime = withTimeoutOrNull(longPressTimeout) {
                                tryAwaitRelease()
                            }
                            if (releasedInTime == null) {
                                wasLongPress = true
                                if (liveAvailable) {
                                    liveStart.value()
                                    try {
                                        tryAwaitRelease()
                                    } finally {
                                        liveEnd.value()
                                    }
                                }
                            }
                        }
                    )
                }
        )
    }
}
