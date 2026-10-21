package com.ink.gallery.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import com.ink.gallery.data.model.PhotoItem
import kotlinx.coroutines.delay

private const val SPINNER_DELAY_MS = 180L

object PhotoGridSpec {
    const val COLUMNS = 4
    val SPACING = 1.dp
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PagedPhotoGrid(
    photos: LazyPagingItems<PhotoItem>,
    onPhotoClick: (PhotoItem) -> Unit,
    modifier: Modifier = Modifier,
    emptyText: String = "没有找到照片",
    onRefresh: () -> Unit = { photos.refresh() }
) {
    val refreshState = photos.loadState.refresh
    // 首次加载用居中转圈；已有内容时的重新加载走顶部下拉指示器
    val isRefreshing = refreshState is LoadState.Loading && photos.itemCount > 0

    // 移动/删除照片后分页源会失效重载，这一刻 itemCount 会短暂归零；
    // 缓存有效时重载是毫秒级的，加个延迟避免闪一下转圈
    val emptyLoading = refreshState is LoadState.Loading && photos.itemCount == 0
    var showSpinner by remember { mutableStateOf(false) }
    LaunchedEffect(emptyLoading) {
        if (emptyLoading) {
            delay(SPINNER_DELAY_MS)
            showSpinner = true
        } else {
            showSpinner = false
        }
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize()
    ) {
        when {
            // 失败必须显式提示，否则会误导成「相册是空的」
            refreshState is LoadState.Error -> ScrollableFullScreen {
                ErrorView(
                    message = refreshState.error.message ?: "未知错误",
                    onRetry = onRefresh
                )
            }

            showSpinner -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            photos.itemCount == 0 -> ScrollableFullScreen {
                EmptyView(text = emptyText)
            }

            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(PhotoGridSpec.COLUMNS),
                    contentPadding = PaddingValues(PhotoGridSpec.SPACING),
                    horizontalArrangement = Arrangement.spacedBy(PhotoGridSpec.SPACING),
                    verticalArrangement = Arrangement.spacedBy(PhotoGridSpec.SPACING),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        count = photos.itemCount,
                        key = { index -> photos.peek(index)?.id ?: index }
                    ) { index ->
                        val photo = photos[index]
                        if (photo != null) {
                            PhotoThumbnail(photo = photo, onClick = { onPhotoClick(photo) })
                        }
                    }
                }
            }
        }
    }
}

/** 让空态/错误态也能滚动，从而支持下拉刷新 */
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
