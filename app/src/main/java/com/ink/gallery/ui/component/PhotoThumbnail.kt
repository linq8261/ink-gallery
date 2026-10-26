package com.ink.gallery.ui.component

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.decode.BitmapFactoryDecoder
import coil.request.ImageRequest
import com.ink.gallery.data.model.PhotoItem
import com.ink.gallery.ui.theme.InkCinnabar
import com.ink.gallery.ui.theme.InkInk
import com.ink.gallery.ui.theme.InkPaper
import com.ink.gallery.ui.theme.InkViewerBlack
import com.ink.gallery.util.formatDuration

@Composable
fun PhotoThumbnail(
    photo: PhotoItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 选择模式：显示左上角选择标记与选中遮罩；点击行为由调用方决定 */
    selectionActive: Boolean = false,
    selected: Boolean = false,
    /** 实况图：左下角显示 LIVE 角标（探测结果来自 LivePhotoRepository） */
    live: Boolean = false
) {
    val isGif = photo.mimeType.equals("image/gif", ignoreCase = true)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RectangleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
    ) {
        // 视频由 ImageLoader 里的 VideoFrameDecoder 解首帧，缓存/裁剪逻辑与图片完全一致
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(photo.uri)
                .crossfade(true)
                .apply {
                    // Android 9 以下动图是 App 内逐帧解码，网格里同时播十几个会明显掉帧，
                    // 这里强制走静态解码器只出首帧；详情页仍然播放。
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                        decoderFactory(BitmapFactoryDecoder.Factory())
                    }
                }
                .build(),
            contentDescription = photo.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        if (selectionActive) {
            if (selected) {
                // 选中遮罩：淡墨半透明覆盖 + 朱砂细描边，像在照片上盖了一方印
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(InkInk.copy(alpha = 0.34f))
                        .border(2.dp, InkCinnabar, RectangleShape)
                )
            }
            SelectionBadge(
                selected = selected,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
            )
        }

        if (photo.isVideo) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(InkViewerBlack.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = InkPaper,
                    modifier = Modifier.size(11.dp)
                )
                Text(
                    text = formatDuration(photo.durationMs),
                    color = InkPaper,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        } else if (isGif) {
            Text(
                text = "GIF",
                color = InkPaper,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(
                        color = InkViewerBlack.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }

        // 实况图角标放左下：右下已被视频时长 / GIF 占用
        if (live) {
            Text(
                text = "LIVE",
                color = InkPaper,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .background(
                        color = InkViewerBlack.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
    }
}

@Composable
private fun SelectionBadge(
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(
                if (selected) {
                    // 选中用朱砂，整个 App 里唯一的彩色就是这里和选中描边
                    InkCinnabar
                } else {
                    InkInk.copy(alpha = 0.3f)
                }
            )
            .border(1.5.dp, InkPaper.copy(alpha = 0.9f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = InkPaper,
                modifier = Modifier.size(13.dp)
            )
        }
    }
}
