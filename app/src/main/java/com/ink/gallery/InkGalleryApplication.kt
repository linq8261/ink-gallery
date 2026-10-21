package com.ink.gallery

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.ink.gallery.data.repository.TrashRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class InkGalleryApplication : Application(), ImageLoaderFactory {

    @Inject
    lateinit var trashRepository: TrashRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 回收站保留 30 天：每次启动清一次过期条目（含文件本体）
        appScope.launch {
            runCatching { trashRepository.purgeExpired() }
        }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            // 视频缩略图：解码首帧，走的是同一条 ImageLoader/磁盘缓存
            add(VideoFrameDecoder.Factory())
            // GIF / 动态 WebP：28+ 用系统 ImageDecoder，解码与帧调度在 native 侧，
            // 网格里整屏一起播也不会有逐帧解码的开销；老系统只能用纯 Java 的 GifDecoder，
            // 那种情况网格/封面会强制静态首帧（见 PhotoThumbnail / BucketItem）。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                add(ImageDecoderDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
        }
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizeBytes(256L * 1024 * 1024)
                .build()
        }
        .diskCachePolicy(CachePolicy.ENABLED)
        .crossfade(true)
        .build()
}
