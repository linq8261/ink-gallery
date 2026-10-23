package com.ink.gallery.ui.component

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.math.min

/**
 * 网格「长按进入多选 + 按住拖动区间选择」。
 *
 * 两个关键点：
 * 1. 长按判定完成前**不消费**任何事件，网格滚动、格子点击、下拉刷新全部照常；
 * 2. 长按成立后切到 [PointerEventPass.Initial] 消费事件——既不滚网格，也不让格子的
 *    clickable 收到抬起而触发点击（否则会出现「刚选中又被 toggle 掉」）。
 *
 * 拖动语义：长按命中的格子是锚点，下标 i1 固定不变；手指当前格子下标 i2 随滑动变化，
 * 每次都从锚点重新算出闭区间 [min(i1,i2), max(i1,i2)]，选中区间内的所有项。
 * 手指回拖使区间缩短时，超出新区间的项会被撤销；拖动前已有的其它选择保持不变。
 */
@Composable
fun <T> Modifier.gridDragSelect(
    gridState: LazyGridState,
    items: List<T>,
    keyOf: (T) -> Long,
    selection: SelectionState<T>
): Modifier {
    val currentItems by rememberUpdatedState(items)
    val currentKeyOf by rememberUpdatedState(keyOf)

    return pointerInput(gridState, selection) {
        val longPressTimeout = viewConfiguration.longPressTimeoutMillis
        val touchSlop = viewConfiguration.touchSlop

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val downPosition = down.position

            var aborted = false
            withTimeoutOrNull(longPressTimeout) {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null) {
                        aborted = true
                        break
                    }
                    if (!change.pressed ||
                        change.isConsumed ||
                        (change.position - downPosition).getDistance() > touchSlop
                    ) {
                        aborted = true
                        break
                    }
                }
            }
            // 抬起 / 被其他手势消费 / 滑出 slop：交给滚动与点击
            if (aborted) return@awaitEachGesture

            val anchorIndex = hitIndex(gridState, downPosition) ?: return@awaitEachGesture
            val anchor = currentItems.getOrNull(anchorIndex) ?: return@awaitEachGesture

            if (selection.isActive) {
                selection.setSelected(anchor, true)
            } else {
                selection.start(anchor)
            }

            selection.beginRangeDrag()
            try {
                var lastIndex = anchorIndex
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    var anyPressed = false
                    event.changes.forEach { change ->
                        if (change.pressed) anyPressed = true
                        change.consume()
                    }
                    val change = event.changes.firstOrNull { it.id == down.id }
                        ?: event.changes.firstOrNull()
                    if (change != null) {
                        nearestIndex(gridState, change.position)?.let { lastIndex = it }
                        selection.applyRange(currentItems, anchorIndex, lastIndex)
                    }
                    if (!anyPressed) break
                }
            } finally {
                selection.endRangeDrag()
            }
        }
    }
}

/** 触点命中的可见格子索引（只遍历屏幕内的十来个 item，开销可忽略） */
private fun hitIndex(gridState: LazyGridState, position: Offset): Int? {
    val visible = gridState.layoutInfo.visibleItemsInfo
    if (visible.isEmpty()) return null
    val hit = visible.firstOrNull { info ->
        position.x >= info.offset.x &&
            position.x < info.offset.x + info.size.width &&
            position.y >= info.offset.y &&
            position.y < info.offset.y + info.size.height
    } ?: return null
    // 加了分组头部等情况时 index 与列表下标不一定相同，这里用 key 反查不到就直接用 index
    return hit.index
}

/** 离触点最近的可见格子索引；手指落在格子缝隙或图片外时用它兜底 */
private fun nearestIndex(gridState: LazyGridState, position: Offset): Int? {
    val visible = gridState.layoutInfo.visibleItemsInfo
    if (visible.isEmpty()) return null
    var best: LazyGridItemInfo? = null
    var bestDistance = Float.MAX_VALUE
    visible.forEach { info ->
        val left = info.offset.x.toFloat()
        val top = info.offset.y.toFloat()
        val right = left + info.size.width
        val bottom = top + info.size.height
        val dx = when {
            position.x < left -> left - position.x
            position.x > right -> position.x - right
            else -> 0f
        }
        val dy = when {
            position.y < top -> top - position.y
            position.y > bottom -> position.y - bottom
            else -> 0f
        }
        val distance = dx * dx + dy * dy
        if (distance < bestDistance) {
            bestDistance = distance
            best = info
        }
    }
    return best?.index
}

/** 闭区间下标范围，供 [SelectionState.applyRange] 使用 */
internal fun rangeBounds(a: Int, b: Int): IntRange = min(a, b)..max(a, b)
