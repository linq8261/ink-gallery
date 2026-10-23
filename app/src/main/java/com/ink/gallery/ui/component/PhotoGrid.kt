package com.ink.gallery.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ink.gallery.data.model.PhotoItem
import kotlinx.coroutines.launch

object PhotoGridSpec {
    const val COLUMNS = 4
    val SPACING = 1.dp
}

/**
 * 媒体网格。数据是仓库内存里的整份列表，直接交给 LazyGrid 自己 diff：
 * 移动/删除一张照片时只会有那一格发生变化，不会整屏重建。
 *
 * [photos] 为 null 表示「首次读取还没完成」，只有这一种情况才显示居中转圈；
 * 下拉刷新期间沿用旧列表，由 PullToRefreshBox 的下拉指示器表达进度。
 *
 * 传入 [selection] 后即支持长按多选与拖动连选；不传则是纯浏览网格。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoGrid(
    photos: List<PhotoItem>?,
    onPhotoClick: (PhotoItem) -> Unit,
    onRefresh: suspend () -> Unit,
    modifier: Modifier = Modifier,
    emptyText: String = "没有找到照片或视频",
    selection: SelectionState<PhotoItem>? = null,
    /** 已确认的实况图 id 集合，用于角标；随探测结果增量变化 */
    liveIds: Set<Long> = emptySet()
) {
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()

    val list = photos
    val selectionActive = selection?.isActive == true
    val dragSelectModifier = if (selection != null && list != null) {
        Modifier.gridDragSelect(
            gridState = gridState,
            items = list,
            keyOf = { it.id },
            selection = selection
        )
    } else {
        Modifier
    }

    PullToRefreshBox(
        isRefreshing = refreshing && !selectionActive,
        onRefresh = {
            // 选择模式下不触发下拉刷新
            if (!selectionActive) {
                scope.launch {
                    refreshing = true
                    try {
                        onRefresh()
                    } finally {
                        refreshing = false
                    }
                }
            }
        },
        modifier = modifier.fillMaxSize()
    ) {
        when {
            list == null -> DelayedProgressIndicator(modifier = Modifier.fillMaxSize())

            list.isEmpty() -> ScrollableFullScreen { EmptyView(text = emptyText) }

            else -> LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(PhotoGridSpec.COLUMNS),
                contentPadding = PaddingValues(PhotoGridSpec.SPACING),
                horizontalArrangement = Arrangement.spacedBy(PhotoGridSpec.SPACING),
                verticalArrangement = Arrangement.spacedBy(PhotoGridSpec.SPACING),
                modifier = Modifier
                    .fillMaxSize()
                    .then(dragSelectModifier)
            ) {
                items(items = list, key = { it.id }) { photo ->
                    PhotoThumbnail(
                        photo = photo,
                        onClick = {
                            if (selectionActive) selection?.toggle(photo) else onPhotoClick(photo)
                        },
                        selectionActive = selectionActive,
                        selected = selection?.isSelected(photo.id) == true,
                        live = photo.id in liveIds
                    )
                }
            }
        }
    }
}

/** 让空态也能滚动，从而支持下拉刷新 */
@Composable
private fun ScrollableFullScreen(content: @Composable () -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Box(modifier = Modifier.fillParentMaxSize()) {
                content()
            }
        }
    }
}
