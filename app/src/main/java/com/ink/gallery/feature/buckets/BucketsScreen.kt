package com.ink.gallery.feature.buckets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.ink.gallery.data.model.Bucket
import com.ink.gallery.ui.component.EmptyView
import com.ink.gallery.util.DateFormatter

private const val BUCKET_COLUMNS = 4

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BucketsScreen(
    onBucketClick: (Long) -> Unit,
    viewModel: BucketsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isRefreshing = uiState.isLoading && uiState.buckets.isNotEmpty()

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = { viewModel.refresh() },
        modifier = Modifier.fillMaxSize()
    ) {
        when {
            // 首次加载用居中转圈；刷新时保留列表，由顶部下拉指示器表示进度
            uiState.isLoading && uiState.buckets.isEmpty() ->
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

            uiState.buckets.isEmpty() -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Box(modifier = Modifier.fillParentMaxSize()) {
                        EmptyView(text = "没有找到相册")
                    }
                }
            }

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(BUCKET_COLUMNS),
                contentPadding = PaddingValues(0.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(uiState.buckets, key = { _, bucket -> bucket.id }) { index, bucket ->
                    BucketItem(
                        bucket = bucket,
                        onClick = { onBucketClick(bucket.id) },
                        drawRightDivider = (index + 1) % BUCKET_COLUMNS != 0,
                        drawBottomDivider = index + BUCKET_COLUMNS < uiState.buckets.size,
                        dividerColor = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun BucketItem(
    bucket: Bucket,
    onClick: () -> Unit,
    drawRightDivider: Boolean,
    drawBottomDivider: Boolean,
    dividerColor: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .drawBehind {
                val stroke = 1.dp.toPx()
                val half = stroke / 2f
                if (drawRightDivider) {
                    drawLine(
                        color = dividerColor,
                        start = Offset(size.width - half, 0f),
                        end = Offset(size.width - half, size.height),
                        strokeWidth = stroke
                    )
                }
                if (drawBottomDivider) {
                    drawLine(
                        color = dividerColor,
                        start = Offset(0f, size.height - half),
                        end = Offset(size.width, size.height - half),
                        strokeWidth = stroke
                    )
                }
            }
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(bucket.coverUri)
                .crossfade(true)
                .build(),
            contentDescription = bucket.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RectangleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Text(
            text = bucket.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            text = DateFormatter.formatCount(bucket.count),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
