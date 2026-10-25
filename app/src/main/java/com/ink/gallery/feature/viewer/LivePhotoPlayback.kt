package com.ink.gallery.feature.viewer

import android.content.Context
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.ink.gallery.data.live.LiveInfo
import com.ink.gallery.data.model.PhotoItem
import java.io.File

/**
 * 实况图播放层：长按期间把静态图下方的内嵌视频播出来。
 *
 * 关键点是**不落临时文件**——[LiveInfo] 已经是「文件内某段字节」，这里用
 * `ResolvingDataSource` 把播放器要读的每个区间平移到那段 MP4 上：
 * 播放器以为自己在读一个从 0 开始的视频文件，实际读的是图片的尾部。
 * 于是解码、A/V 同步、首帧全部交给 ExoPlayer，也不需要往磁盘写 2~5MB。
 *
 * 有空闲帧预算时会先 `prepare()` 预缓冲（[prewarm]），长按下去基本立刻出声出画；
 * 播放器只在 [playing] 时挂载 `PlayerView`，且首帧渲染前保持透明，
 * 这样缓冲期间看到的仍然是背后的静态图，不会闪一块黑。
 */
@Composable
fun LivePhotoPlayback(
    photo: PhotoItem,
    info: LiveInfo,
    playing: Boolean,
    prewarm: Boolean,
    onEnded: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val ended = rememberUpdatedState(onEnded)

    var player by remember(photo.id) { mutableStateOf<ExoPlayer?>(null) }
    var firstFrameRendered by remember(photo.id) { mutableStateOf(false) }

    // 预缓冲：滑到这张图时就把播放器建好并 prepare，长按不再等
    LaunchedEffect(photo.id, info, prewarm, playing) {
        if (player == null && (prewarm || playing)) {
            player = createPlayer(
                context = context,
                photo = photo,
                info = info,
                onFirstFrame = { firstFrameRendered = true },
                onEnded = { ended.value() }
            )
        }
    }

    DisposableEffect(photo.id) {
        onDispose {
            player?.release()
            player = null
        }
    }

    val current = player
    LaunchedEffect(playing, current) {
        val exoPlayer = current ?: return@LaunchedEffect
        if (playing) {
            firstFrameRendered = false
            exoPlayer.seekTo(0L)
            exoPlayer.play()
        } else {
            exoPlayer.pause()
            exoPlayer.seekTo(0L)
        }
    }

    if (playing && current != null) {
        AndroidView(
            modifier = modifier
                .fillMaxSize()
                .alpha(if (firstFrameRendered) 1f else 0f),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    // 透明快门：首帧到来前露出背后的静态图，而不是黑屏
                    setShutterBackgroundColor(AndroidColor.TRANSPARENT)
                    player = current
                }
            },
            update = { view -> view.player = current }
        )
    }
}

private fun createPlayer(
    context: Context,
    photo: PhotoItem,
    info: LiveInfo,
    onFirstFrame: () -> Unit,
    onEnded: () -> Unit
): ExoPlayer {
    // 有真实路径就直接读文件（最快）；否则退回 ContentResolver 的流
    val localFile = photo.path.takeIf { it.isNotEmpty() }
        ?.let(::File)
        ?.takeIf { it.canRead() }
    val mediaUri: Uri = localFile?.let(Uri::fromFile) ?: photo.uri
    val upstream: DataSource.Factory = if (localFile != null) {
        FileDataSource.Factory()
    } else {
        DefaultDataSource.Factory(context)
    }

    // 播放器请求 [0, 视频长度) 的字节，实际读的是 [offset, offset + 视频长度)
    val rangeFactory = ResolvingDataSource.Factory(upstream) { spec ->
        val start = spec.position.coerceIn(0L, info.lengthBytes)
        val remaining = info.lengthBytes - start
        val length = if (spec.length == C.LENGTH_UNSET.toLong()) {
            remaining
        } else {
            minOf(spec.length, remaining)
        }
        spec.buildUpon()
            .setPosition(info.offsetBytes + start)
            .setLength(length)
            .build()
    }

    val mediaSource = ProgressiveMediaSource.Factory(rangeFactory)
        .createMediaSource(
            MediaItem.Builder()
                .setUri(mediaUri)
                .setMimeType(MIME_TYPE_MP4)
                .build()
        )

    return ExoPlayer.Builder(context).build().apply {
        // 出声：跟随系统媒体音量。注意系统「静音/振动」不会自动压掉这里的声音，
        // 需要连音频一起静音时再按 AudioManager.ringerMode 判断。
        volume = 1f
        repeatMode = Player.REPEAT_MODE_OFF
        setMediaSource(mediaSource)
        prepare()
        addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                onFirstFrame()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    pause()
                    seekTo(0L)
                    onEnded()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // 区间解析出错时不影响静态图，只是长按没有动态效果
                Log.w(TAG, "实况播放失败：${error.errorCodeName}", error)
                onEnded()
            }
        })
    }
}

private const val MIME_TYPE_MP4 = "video/mp4"

/** 实况链路诊断：`adb logcat -s InkLive` */
private const val TAG = "InkLive"
