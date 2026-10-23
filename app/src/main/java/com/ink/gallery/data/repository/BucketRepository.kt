package com.ink.gallery.data.repository

import android.os.Environment
import com.ink.gallery.data.model.Bucket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 相册（文件夹）聚合。
 *
 * 不自己查 MediaStore：相册的 id / 名称 / 封面 / 数量 / 最新时间全都能从
 * [PhotoRepository] 那份照片列表里直接算出来，而照片列表在冷启动时已由磁盘快照预载、
 * 后续由 ContentObserver 维护。同一个媒体库不再被扫两遍——这是启动耗时最大的那块浪费。
 *
 * 唯一还需要落盘读取的是排序依据：相册目录自身的 mtime。
 */
@Singleton
class BucketRepository @Inject constructor(
    private val photoRepository: PhotoRepository
) {

    /** 目录 mtime 的短期缓存，避免每次聚合都对所有相册目录做一遍 stat */
    private val mtimeCache = ConcurrentHashMap<String, CachedMtime>()

    private data class CachedMtime(val readAt: Long, val value: Long)

    /**
     * 返回所有相册，按**相册目录自身的修改时间（mtime）**倒序——与果仁相册等主流相册一致：
     * 目录里有过增删/重命名的相册（比如刚往里搬过照片）会排到最前。
     *
     * 注意根目录（`relativePath` 为 `/`）在 FUSE 上层 mtime 长期不变，
     * 所以「根目录」相册会稳定排在后面。
     *
     * 封面取该相册里最新的一张，优先图片；纯视频目录则用视频首帧当封面。
     * 照片列表本身已按「修改时间新→旧」排好，所以「第一条就是最新的」。
     */
    suspend fun getBuckets(): List<Bucket> = withContext(Dispatchers.IO) {
        val photos = photoRepository.getPhotos()
        if (photos.isEmpty()) return@withContext emptyList()

        photos.groupBy { it.bucketId }
            .map { (bucketId, items) ->
                val latest = items.first()
                val cover = items.firstOrNull { !it.isVideo } ?: latest
                Bucket(
                    id = bucketId,
                    name = latest.bucketName.ifEmpty { DEFAULT_BUCKET_NAME },
                    coverUri = cover.uri,
                    count = items.size,
                    latestModified = latest.dateModified,
                    relativePath = relativePathOf(latest.path)
                )
            }
            .sortedByDescending { albumTime(it.relativePath) }
    }

    /** 相册名同样从照片列表派生，不再单独查一次 MediaStore */
    suspend fun getBucketName(bucketId: Long): String? =
        photoRepository.getPhotos(bucketId)
            .firstOrNull()
            ?.bucketName
            ?.ifEmpty { null }

    /**
     * 由照片的绝对路径反推相册相对目录：
     * `/storage/emulated/0/DCIM/Camera/IMG_1.jpg` → `DCIM/Camera/`；
     * 存储根目录下的照片 → `/`（与 MediaStore 的 relative_path 对根目录的返回值一致）。
     *
     * 拿不到路径（例如 Android 10+ 且还原失败）时返回空串，调用方据此判断「无法移动」。
     */
    private fun relativePathOf(absolutePath: String): String {
        if (absolutePath.isEmpty()) return ""
        val root = Environment.getExternalStorageDirectory().absolutePath
        if (!absolutePath.startsWith("$root/")) return ""
        val relative = absolutePath.substring(root.length + 1)
        val slash = relative.lastIndexOf('/')
        return if (slash < 0) "/" else relative.substring(0, slash + 1)
    }

    /**
     * 相册的排序时间：目录 mtime 与本进程内搬动过的时间取较大值。
     *
     * 只读 mtime 不够：MediaProvider 改完数据库后真正落盘会滞后，而且新目录的 mtime
     * 还会被 FUSE 属性缓存压住几秒，刚搬完照片的相册就会停在旧位置。
     */
    private fun albumTime(relativePath: String): Long {
        val dir = File(Environment.getExternalStorageDirectory(), relativePath.trim('/'))
        val key = dir.absolutePath.trimEnd('/')
        val now = System.currentTimeMillis()
        val cached = mtimeCache[key]
        val mtime = if (cached != null && now - cached.readAt < MTIME_TTL_MS) {
            cached.value
        } else {
            dir.lastModified().also { mtimeCache[key] = CachedMtime(now, it) }
        }
        return maxOf(mtime, photoRepository.albumTouchedAt(key))
    }

    private companion object {
        /** 存储根目录（/storage/emulated/0/）的 bucket_display_name 为 NULL，这里兜底命名 */
        const val DEFAULT_BUCKET_NAME = "根目录"

        /** 目录 mtime 的缓存有效期：够短，不会藏住「刚搬完照片」的变化 */
        const val MTIME_TTL_MS = 2_000L
    }
}
