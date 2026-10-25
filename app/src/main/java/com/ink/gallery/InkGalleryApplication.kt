package com.ink.gallery

import android.app.Application
import android.os.Build
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.ink.gallery.data.repository.PhotoRepository
import com.ink.gallery.data.repository.TrashRepository
import com.tencent.bugly.crashreport.CrashReport
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

    /**
     * 提前注入：让照片仓库在 [onCreate] 阶段就完成构造。
     * 构造时会立刻启动磁盘快照的读取，比等首页 ViewModel 创建再开始早一个 Compose 首帧的量级。
     */
    @Inject
    lateinit var photoRepository: PhotoRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private companion object {
        const val LOG_TAG = "InkGallery"
    }

    override fun onCreate() {
        super.onCreate()
        // 只在 release 上报：debug 期间的崩溃会混进正式数据，拉偏崩溃率。
        // 放最前面是因为初始化之前发生的崩溃捕获不到。
        if (!BuildConfig.DEBUG) initCrashReporting()
        photoRepository.warmUp()
        // 回收站保留 30 天：每次启动清一次过期条目（含文件本体）
        appScope.launch {
            runCatching { trashRepository.purgeExpired() }
        }
    }

    /**
     * Bugly 崩溃上报（免费版，只报崩溃/ANR，不采集照片等业务数据）。
     *
     * AppID 由 local.properties（本地）/ 环境变量 BUGLY_APP_ID（CI）注入到 BuildConfig；
     * 没配置就整体跳过，避免拿空 AppID 去做无效上报。
     * debugMode 固定 false：调用点已限定 release，批量上报即可，不必每条崩溃一次请求。
     */
    private fun initCrashReporting() {
        val appId = BuildConfig.BUGLY_APP_ID
        if (appId.isEmpty()) {
            Log.w(LOG_TAG, "未配置 bugly.appId，崩溃上报已跳过")
            return
        }
        CrashReport.initCrashReport(applicationContext, appId, false)
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
