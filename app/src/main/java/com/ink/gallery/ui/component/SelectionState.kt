package com.ink.gallery.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * 多选状态。
 *
 * [isActive] 与选中集合是分开的：允许「在选择模式里把最后一项也取消」而不退出模式，
 * 与系统相册一致，只有点 X / 返回键才退出。
 *
 * 用 SnapshotStateMap 存选中项，格子内部只读 [isSelected]，未变化的格子不会重组。
 */
@Stable
class SelectionState<T>(private val keyOf: (T) -> Long) {

    private val items = mutableStateMapOf<Long, T>()

    /** 拖动区间选择前的快照，区间回缩时靠它还原区间外的项 */
    private var dragBase: Map<Long, T>? = null

    var isActive by mutableStateOf(false)
        private set

    val count: Int get() = items.size

    val selected: List<T> get() = items.values.toList()

    fun isSelected(id: Long): Boolean = items.containsKey(id)

    /** 长按进入选择模式，并选中这一张 */
    fun start(item: T) {
        isActive = true
        items[keyOf(item)] = item
    }

    fun toggle(item: T) {
        val key = keyOf(item)
        if (items.containsKey(key)) items.remove(key) else items[key] = item
    }

    fun setSelected(item: T, selected: Boolean) {
        val key = keyOf(item)
        if (selected) items[key] = item else items.remove(key)
    }

    fun clearSelection() {
        items.clear()
    }

    /** 拖动区间选择开始：记下拖动前的选择，作为区间回缩时的基线 */
    fun beginRangeDrag() {
        dragBase = items.toMap()
    }

    fun endRangeDrag() {
        dragBase = null
    }

    /**
     * 由锚点下标与当前下标重算闭区间，区间内全选、区间外恢复到拖动前的状态。
     * 每次滑动都整体重算，因此手指回拖时区间外的项会被撤销。
     */
    fun applyRange(all: List<T>, anchorIndex: Int, currentIndex: Int) {
        if (all.isEmpty()) return
        val base = dragBase ?: return
        val last = all.lastIndex
        val range = rangeBounds(
            anchorIndex.coerceIn(0, last),
            currentIndex.coerceIn(0, last)
        )
        val inRange = HashSet<Long>(range.count())
        for (i in range) inRange.add(keyOf(all[i]))

        items.keys.toList().forEach { key ->
            if (key !in inRange && key !in base) items.remove(key)
        }
        for (i in range) {
            val item = all[i]
            items[keyOf(item)] = item
        }
    }

    /** 全选；[all] 为空时不做任何事 */
    fun selectAll(all: List<T>) {
        if (all.isEmpty()) return
        isActive = true
        all.forEach { items[keyOf(it)] = it }
    }

    fun exit() {
        items.clear()
        isActive = false
    }
}

@Composable
fun <T> rememberSelectionState(keyOf: (T) -> Long): SelectionState<T> =
    remember { SelectionState(keyOf) }
