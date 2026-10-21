package com.ink.gallery.feature.viewer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.ink.gallery.data.model.PhotoItem
import com.ink.gallery.util.formatDuration

/**
 * 视频页：只显示首帧和播放按钮，点播放按钮才交给系统播放器。
 *
 * 本 App 不集成播放器，所以这里没有任何解码/播放逻辑，
 * 首帧由 ImageLoader 的 VideoFrameDecoder 提供（与网格缩略图共用缓存）。
 */
@Composable
fun VideoPreviewPage(
    photo: PhotoItem,
    onTap: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onTap
            ),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(photo.uri)
                .build(),
            contentDescription = photo.displayName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                onClick = onPlay,
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.45f),
                modifier = Modifier.size(76.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "播放",
                        tint = Color.White,
                        modifier = Modifier.size(44.dp)
                    )
                }
            }
            if (photo.durationMs > 0) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = formatDuration(photo.durationMs),
                    color = Color.White.copy(alpha = 0.9f)
                )
            }
        }
    }
}

/** 调起系统播放器（不集成播放器，交给用户设备上已有的应用） */
fun playVideo(context: Context, photo: PhotoItem) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(photo.uri, photo.mimeType.ifEmpty { "video/*" })
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "没有可用的视频播放器", Toast.LENGTH_SHORT).show()
    } catch (e: SecurityException) {
        Toast.makeText(context, "没有权限打开视频", Toast.LENGTH_SHORT).show()
    }
}
