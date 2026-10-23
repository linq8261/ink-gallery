package com.ink.gallery.feature.viewer

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.result.IntentSenderRequest
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ink.gallery.data.live.LiveInfo
import com.ink.gallery.data.model.PhotoItem
import com.ink.gallery.ui.component.rememberHasFullAccess
import com.ink.gallery.util.DateFormatter
import com.ink.gallery.util.StorageAccess
import com.ink.gallery.util.formatDuration
import java.util.Locale

private val DETAIL_DRAG_THRESHOLD = 56.dp

@Composable
fun PhotoViewerScreen(
    onBack: () -> Unit,
    viewModel: PhotoViewerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val movePermission by viewModel.movePermission.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val needFullAccess by viewModel.needFullAccess.collectAsStateWithLifecycle()
    val livePhotos by viewModel.livePhotos.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 删除依赖「所有文件访问权限」，没有时删除按钮置灰并引导
    val hasFullAccess = rememberHasFullAccess()

    val photos = uiState.photos
    var barsVisible by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var detailsVisible by remember { mutableStateOf(false) }
    var moveSheetVisible by remember { mutableStateOf(false) }
    var fullAccessGuideVisible by remember { mutableStateOf(false) }
    /** 正在长按播放实况的那张图，null 表示没有在播 */
    var livePressing by remember { mutableStateOf<Long?>(null) }
    val detailsVisibleState = rememberUpdatedState(detailsVisible)

    // 长按起播后手指抬起就复位：正常结束由播放器的 STATE_ENDED 回到这里
    val stopLive = { livePressing = null }

    BackHandler(enabled = detailsVisible) { detailsVisible = false }

    // 照片被移走后列表可能变空，此时直接退回上一页
    LaunchedEffect(uiState.isLoading, photos.isEmpty()) {
        if (!uiState.isLoading && photos.isEmpty()) onBack()
    }

    val moveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onMovePermissionResult(result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(movePermission) {
        movePermission?.let { sender ->
            moveLauncher.launch(IntentSenderRequest.Builder(sender).build())
        }
    }

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    LaunchedEffect(needFullAccess) {
        if (needFullAccess) {
            fullAccessGuideVisible = true
            viewModel.consumeNeedFullAccess()
        }
    }

    if (uiState.isLoading || photos.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(color = Color.White)
            }
        }
        return
    }

    val pagerState = rememberPagerState(
        initialPage = uiState.startIndex.coerceIn(0, photos.lastIndex),
        pageCount = { photos.size }
    )

    val currentPhoto = photos.getOrNull(pagerState.currentPage)

    // 翻到新的一页时按需探测一次实况（命中缓存立即返回）
    LaunchedEffect(currentPhoto?.id) {
        currentPhoto?.let { viewModel.ensureLive(it) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // 上滑唤出详情：放大或正在播实况时禁用，避免与拖拽冲突
            .pointerInput(zoomed, livePressing) {
                if (zoomed || livePressing != null) return@pointerInput
                val threshold = DETAIL_DRAG_THRESHOLD.toPx()
                var accumulated = 0f
                detectVerticalDragGestures(
                    onDragStart = { accumulated = 0f },
                    onVerticalDrag = { _, dragAmount ->
                        if (!detailsVisibleState.value) {
                            accumulated += dragAmount
                            if (accumulated < -threshold) {
                                detailsVisible = true
                                accumulated = 0f
                            }
                        }
                    }
                )
            }
    ) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !zoomed && livePressing == null,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val photo = photos.getOrNull(page)
            if (photo != null) {
                if (photo.isVideo) {
                    VideoPreviewPage(
                        photo = photo,
                        onTap = { barsVisible = !barsVisible },
                        onPlay = { playVideo(context, photo) }
                    )
                } else {
                    ZoomableImage(
                        photo = photo,
                        onTap = { barsVisible = !barsVisible },
                        onZoomChanged = { zoomed = it },
                        liveAvailable = livePhotos.containsKey(photo.id),
                        onLivePressStart = { livePressing = photo.id },
                        onLivePressEnd = stopLive
                    )
                }
            }
        }

        // 实况播放层：盖在静态图上，只在这一页确实是实况时才挂
        val liveInfo = currentPhoto?.let { livePhotos[it.id] }
        if (currentPhoto != null && liveInfo != null) {
            LivePhotoPlayback(
                photo = currentPhoto,
                info = liveInfo,
                playing = livePressing == currentPhoto.id,
                // 停稳后先 prepare，长按下去基本零等待；快速翻页时不建播放器
                prewarm = !pagerState.isScrollInProgress,
                onEnded = stopLive
            )
        }

        if (barsVisible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "${pagerState.currentPage + 1} / ${photos.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.width(48.dp))
            }

            PhotoViewerToolbar(
                modifier = Modifier.align(Alignment.BottomCenter),
                onEdit = { currentPhoto?.let { launchEdit(context, it) } },
                onShare = { currentPhoto?.let { launchShare(context, it) } },
                onMove = {
                    viewModel.loadMoveTargets()
                    moveSheetVisible = true
                },
                onDelete = {
                    // 有回收站兜底，不再二次确认
                    if (hasFullAccess) {
                        currentPhoto?.let { viewModel.deletePhoto(it) }
                    } else {
                        fullAccessGuideVisible = true
                    }
                },
                deleteEnabled = hasFullAccess
            )
        }

        AnimatedVisibility(
            visible = detailsVisible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(
                        interactionSource = null,
                        indication = null
                    ) { detailsVisible = false }
            )
        }

        AnimatedVisibility(
            visible = detailsVisible,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            currentPhoto?.let { photo ->
                PhotoDetailsSheet(
                    photo = photo,
                    live = livePhotos[photo.id],
                    onDismiss = { detailsVisible = false }
                )
            }
        }

        if (moveSheetVisible) {
            MoveToBucketSheet(
                buckets = uiState.buckets,
                isLoading = uiState.isBucketsLoading,
                currentBucketId = currentPhoto?.bucketId,
                onDismiss = { moveSheetVisible = false },
                onSelect = { bucket ->
                    moveSheetVisible = false
                    currentPhoto?.let { viewModel.movePhoto(it, bucket) }
                }
            )
        }

        if (fullAccessGuideVisible) {
            AlertDialog(
                onDismissRequest = { fullAccessGuideVisible = false },
                title = { Text("需要「所有文件访问权限」") },
                text = {
                    Text(
                        "删除会把照片/视频移入回收站，需要该权限才能搬动文件。" +
                            "请先到系统设置里为本应用开启。"
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            fullAccessGuideVisible = false
                            StorageAccess.openSettings(context)
                        }
                    ) {
                        Text("去开启")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { fullAccessGuideVisible = false }) { Text("取消") }
                }
            )
        }
    }
}

@Composable
private fun PhotoDetailsSheet(
    photo: PhotoItem,
    live: LiveInfo?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                val threshold = DETAIL_DRAG_THRESHOLD.toPx()
                var accumulated = 0f
                detectVerticalDragGestures(
                    onDragStart = { accumulated = 0f },
                    onVerticalDrag = { _, dragAmount ->
                        accumulated += dragAmount
                        if (accumulated > threshold) {
                            onDismiss()
                            accumulated = 0f
                        }
                    }
                )
            },
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        tonalElevation = 3.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(36.dp)
                    .height(4.dp)
                    .background(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        shape = CircleShape
                    )
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = photo.displayName.ifEmpty { "未命名" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(6.dp))
            DetailRow(label = "相册", value = photo.bucketName.ifEmpty { "根目录" })
            DetailRow(
                label = "拍摄时间",
                value = DateFormatter.formatTimestamp(photo.dateTaken).ifEmpty { "未知" }
            )
            DetailRow(
                label = "修改时间",
                value = DateFormatter.formatTimestamp(photo.dateModified).ifEmpty { "未知" }
            )
            DetailRow(
                label = "分辨率",
                value = if (photo.width > 0 && photo.height > 0) {
                    "${photo.width} × ${photo.height}"
                } else {
                    "未知"
                }
            )
            if (live != null) {
                DetailRow(
                    label = "实况",
                    value = if (live.durationMs > 0) {
                        "内嵌视频 ${formatDuration(live.durationMs)}（长按播放）"
                    } else {
                        "内嵌视频（长按播放）"
                    }
                )
            }
            if (photo.isVideo) {
                DetailRow(label = "时长", value = formatDuration(photo.durationMs))
            }
            DetailRow(label = "文件大小", value = formatFileSize(photo.size))
            DetailRow(label = "类型", value = photo.mimeType.ifEmpty { "未知" })
            DetailRow(label = "路径", value = photo.path.ifEmpty { "未知" })
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun launchEdit(context: Context, photo: PhotoItem) {
    val intent = Intent(Intent.ACTION_EDIT).apply {
        setDataAndType(photo.uri, photo.mimeType.ifEmpty { photo.wildcardMime() })
        // 只能转授读权限：本 App 对相册里的照片没有写权限，
        // 附带 FLAG_GRANT_WRITE_URI_PERMISSION 会让系统校验失败并抛 SecurityException
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "没有可用的编辑应用", Toast.LENGTH_SHORT).show()
    } catch (e: SecurityException) {
        Toast.makeText(context, "没有权限打开编辑应用", Toast.LENGTH_SHORT).show()
    }
}

private fun launchShare(context: Context, photo: PhotoItem) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = photo.mimeType.ifEmpty { photo.wildcardMime() }
        putExtra(Intent.EXTRA_STREAM, photo.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val title = if (photo.isVideo) "分享视频" else "分享照片"
    context.startActivity(Intent.createChooser(intent, title))
}

private fun PhotoItem.wildcardMime(): String = if (isVideo) "video/*" else "image/*"

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "未知"
    val kb = bytes / 1024.0
    return when {
        kb < 1 -> "$bytes B"
        kb < 1024 -> String.format(Locale.getDefault(), "%.0f KB", kb)
        kb < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", kb / 1024)
        else -> String.format(Locale.getDefault(), "%.2f GB", kb / 1024 / 1024)
    }
}
