package com.ink.gallery.data.repository

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import com.ink.gallery.data.local.TrashDao
import com.ink.gallery.data.local.TrashEntity
import com.ink.gallery.data.model.PhotoItem
import com.ink.gallery.util.StorageAccess
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 回收站。
 *
 * 删除 = 把文件 `rename` 到回收站目录 + 记一条元数据，**刻意不做 copy 兜底**：
 * 回收站目录总是选在原文件所在存储卷上（内部存储用 App 私有目录，外置卡用卡根隐藏目录），
 * 同卷 rename 只是改目录项，几个 G 的视频也是毫秒级；一旦 rename 失败就直接报错，
 * 避免出现「拷 3G 文件卡半分钟」这种不可控的等待。
 *
 * 批量操作全部在一次 [Mutex] 内串行完成，结束时只做一次性媒体扫描和一次列表刷新，
 * 避免逐条 emit 让网格抖动。
 */
@Singleton
class TrashRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: TrashDao,
    private val photoRepository: PhotoRepository
) {

    private val mutex = Mutex()

    val entries: Flow<List<TrashEntity>> = dao.observeAll()

    val count: Flow<Int> = dao.observeCount()

    val totalSize: Flow<Long> = dao.observeTotalSize()

    /** 单条移入回收站（详情页用） */
    suspend fun moveToTrash(photo: PhotoItem): TrashResult =
        when (val batch = moveToTrash(listOf(photo))) {
            BatchTrashResult.NeedFullAccess -> TrashResult.NeedFullAccess
            is BatchTrashResult.Done -> if (batch.success > 0) {
                TrashResult.Success
            } else {
                TrashResult.Failed(batch.firstError ?: "移入回收站失败")
            }
        }

    /** 批量移入回收站；成功后照片立即从相册消失 */
    suspend fun moveToTrash(photos: List<PhotoItem>): BatchTrashResult = withContext(Dispatchers.IO) {
        if (photos.isEmpty()) return@withContext BatchTrashResult.Done(0, 0, null)
        if (!StorageAccess.hasFullAccess()) return@withContext BatchTrashResult.NeedFullAccess

        mutex.withLock {
            var success = 0
            var failed = 0
            var firstError: String? = null
            val oldPaths = ArrayList<String>(photos.size)
            val removedIds = ArrayList<Long>(photos.size)

            photos.forEach { photo ->
                when (val result = trashOne(photo)) {
                    TrashResult.Success -> {
                        success++
                        oldPaths += photo.path
                        removedIds += photo.id
                    }

                    is TrashResult.Failed -> {
                        failed++
                        if (firstError == null) firstError = result.message
                    }

                    TrashResult.NeedFullAccess -> {
                        failed++
                        if (firstError == null) firstError = "没有「所有文件访问权限」"
                    }
                }
            }

            if (oldPaths.isNotEmpty()) {
                MediaScannerConnection.scanFile(context, oldPaths.toTypedArray(), null, null)
            }
            if (removedIds.isNotEmpty()) {
                photoRepository.removeFromCache(removedIds)
            }
            BatchTrashResult.Done(success, failed, firstError)
        }
    }

    /** 单条恢复 */
    suspend fun restore(entry: TrashEntity): TrashResult =
        when (val batch = restoreAll(listOf(entry))) {
            BatchTrashResult.NeedFullAccess -> TrashResult.NeedFullAccess
            is BatchTrashResult.Done -> if (batch.success > 0) {
                TrashResult.Success
            } else {
                TrashResult.Failed(batch.firstError ?: "恢复失败")
            }
        }

    /** 批量恢复：搬回原路径，原目录不在会重新创建，重名自动加后缀 */
    suspend fun restoreAll(entries: List<TrashEntity>): BatchTrashResult = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext BatchTrashResult.Done(0, 0, null)
        if (!StorageAccess.hasFullAccess()) return@withContext BatchTrashResult.NeedFullAccess

        mutex.withLock {
            var success = 0
            var failed = 0
            var firstError: String? = null
            val restoredPaths = ArrayList<String>(entries.size)

            entries.forEach { entry ->
                when (val result = restoreOne(entry)) {
                    TrashResult.Success -> {
                        success++
                        restoredPaths += entry.originalPath
                    }

                    is TrashResult.Failed -> {
                        failed++
                        if (firstError == null) firstError = result.message
                    }

                    TrashResult.NeedFullAccess -> {
                        failed++
                        if (firstError == null) firstError = "没有「所有文件访问权限」"
                    }
                }
            }

            if (restoredPaths.isNotEmpty()) {
                MediaScannerConnection.scanFile(context, restoredPaths.toTypedArray(), null, null)
            }
            if (success > 0) {
                // 重新读一遍元数据，把照片放回相册
                photoRepository.refresh()
            }
            BatchTrashResult.Done(success, failed, firstError)
        }
    }

    /** 单条彻底删除 */
    suspend fun deleteForever(entry: TrashEntity): TrashResult =
        when (val batch = deleteForeverAll(listOf(entry))) {
            BatchTrashResult.NeedFullAccess -> TrashResult.NeedFullAccess
            is BatchTrashResult.Done -> if (batch.success > 0) {
                TrashResult.Success
            } else {
                TrashResult.Failed(batch.firstError ?: "删除失败")
            }
        }

    /** 批量彻底删除（不可恢复） */
    suspend fun deleteForeverAll(entries: List<TrashEntity>): BatchTrashResult =
        withContext(Dispatchers.IO) {
            if (entries.isEmpty()) return@withContext BatchTrashResult.Done(0, 0, null)

            mutex.withLock {
                var success = 0
                var failed = 0
                var firstError: String? = null
                val removedIds = ArrayList<Long>(entries.size)

                entries.forEach { entry ->
                    val file = File(entry.trashPath)
                    if (file.exists() && !file.delete()) {
                        failed++
                        if (firstError == null) firstError = "删除失败：文件正被其他应用占用"
                    } else {
                        success++
                        removedIds += entry.id
                    }
                }

                if (removedIds.isNotEmpty()) {
                    dao.deleteByIds(removedIds)
                }
                BatchTrashResult.Done(success, failed, firstError)
            }
        }

    /** 清空回收站（不可恢复） */
    suspend fun clearAll(): BatchTrashResult {
        val all = withContext(Dispatchers.IO) { dao.findAll() }
        return deleteForeverAll(all)
    }

    /** 清掉所有过期条目，返回清理条数；启动时跑一次即可 */
    suspend fun purgeExpired(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val expired = dao.findExpired(System.currentTimeMillis())
            var removed = 0
            expired.forEach { entry ->
                val file = File(entry.trashPath)
                if (!file.exists() || file.delete()) {
                    dao.deleteById(entry.id)
                    removed++
                }
            }
            removed
        }
    }

    /** 只做 rename + 落库；扫描与缓存更新由批量外层统一做 */
    private suspend fun trashOne(photo: PhotoItem): TrashResult {
        val source = File(photo.path)
        if (photo.path.isEmpty() || !source.isFile) {
            return TrashResult.Failed("找不到原始文件")
        }
        val directory = trashDirectoryFor(source)
            ?: return TrashResult.Failed("无法创建回收站目录")
        val target = uniqueTarget(directory, source.name)
        if (!source.renameTo(target)) {
            return TrashResult.Failed("移入回收站失败：该存储不支持移动操作")
        }

        val now = System.currentTimeMillis()
        dao.insert(
            TrashEntity(
                originalUri = photo.uri.toString(),
                originalPath = source.absolutePath,
                trashPath = target.absolutePath,
                displayName = photo.displayName,
                mimeType = photo.mimeType,
                size = photo.size,
                width = photo.width,
                height = photo.height,
                durationMs = photo.durationMs,
                isVideo = photo.isVideo,
                bucketId = photo.bucketId,
                bucketName = photo.bucketName,
                deletedAt = now,
                expiresAt = now + RETENTION_MS
            )
        )
        return TrashResult.Success
    }

    private suspend fun restoreOne(entry: TrashEntity): TrashResult {
        val source = File(entry.trashPath)
        if (!source.isFile) {
            dao.deleteById(entry.id)
            return TrashResult.Failed("文件已不存在，已从回收站移除")
        }
        val original = File(entry.originalPath)
        val parent = original.parentFile ?: return TrashResult.Failed("原目录不可用")
        if (!parent.isDirectory && !parent.mkdirs()) {
            return TrashResult.Failed("无法创建原目录")
        }
        val target = uniqueTarget(parent, original.name)
        if (!source.renameTo(target)) {
            return TrashResult.Failed("恢复失败：无法搬回原目录")
        }
        dao.deleteById(entry.id)
        return TrashResult.Success
    }

    /**
     * 回收站目录必须与源文件同卷，rename 才是毫秒级：
     * - 内部存储 → App 私有目录（卸载即清空，也不会被媒体库扫进相册）
     * - 外置卡 → 卡根的隐藏目录
     */
    private fun trashDirectoryFor(source: File): File? {
        val internalRoot = Environment.getExternalStorageDirectory().absolutePath
        val directory = if (source.absolutePath.startsWith("$internalRoot/")) {
            context.getExternalFilesDir(INTERNAL_TRASH_DIR)
        } else {
            File(volumeRootOf(source), EXTERNAL_TRASH_DIR)
        } ?: return null

        if (!directory.isDirectory && !directory.mkdirs()) return null
        // 外置卡的隐藏目录在存储根上，加 .nomedia 防止被扫进相册
        val nomedia = File(directory, ".nomedia")
        if (!nomedia.exists()) runCatching { nomedia.createNewFile() }
        return directory
    }

    /** 从绝对路径里取出存储卷根目录，如 /storage/1A2B-3C4D */
    private fun volumeRootOf(file: File): File {
        val path = file.absolutePath
        if (path.startsWith(STORAGE_PREFIX)) {
            val volume = path.removePrefix(STORAGE_PREFIX).substringBefore('/')
            if (volume.isNotEmpty()) return File(STORAGE_PREFIX, volume)
        }
        return Environment.getExternalStorageDirectory()
    }

    private fun uniqueTarget(dir: File, displayName: String): File {
        var candidate = File(dir, displayName)
        if (!candidate.exists()) return candidate
        val dot = displayName.lastIndexOf('.')
        val base = if (dot > 0) displayName.substring(0, dot) else displayName
        val suffix = if (dot > 0) displayName.substring(dot) else ""
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base-$index$suffix")
            index++
        }
        return candidate
    }

    private companion object {
        /** 回收站保留 30 天 */
        const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000

        /** 内部存储：App 私有目录下的子目录名 */
        const val INTERNAL_TRASH_DIR = "trash"

        /** 外置卡：卡根下的隐藏目录名 */
        const val EXTERNAL_TRASH_DIR = ".ink-gallery-trash"

        const val STORAGE_PREFIX = "/storage/"
    }
}

sealed interface TrashResult {
    data object Success : TrashResult
    data object NeedFullAccess : TrashResult
    data class Failed(val message: String) : TrashResult
}

sealed interface BatchTrashResult {
    data object NeedFullAccess : BatchTrashResult
    data class Done(val success: Int, val failed: Int, val firstError: String?) : BatchTrashResult
}
