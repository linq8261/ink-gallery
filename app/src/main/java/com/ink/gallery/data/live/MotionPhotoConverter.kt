package com.ink.gallery.data.live

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.ink.gallery.data.model.PhotoItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

sealed interface StaticExportResult {
    /** [newId] 为 MediaStore 里的新 id；旧系统走文件+扫描时拿不到，为 null */
    data class Success(val displayName: String, val newId: Long?) : StaticExportResult

    /** 原图已经不是实况（缓存过期、offset 校验不过） */
    data object NotLive : StaticExportResult

    data class Failed(val message: String) : StaticExportResult
}

/**
 * 实况图 → 静态图副本。
 *
 * 实况图是「静态图 + 尾部内嵌 MP4」的单文件结构，[LiveInfo.offsetBytes] 之后全是视频。
 * 所以导出静态副本只需要**把前 offsetBytes 个字节复制出来**，不解码、不重编码、不改 EXIF：
 * 副本的字节与原图前缀完全一致，显示效果一模一样，只是后面没有那段视频。
 *
 * 副本里 XMP 仍带着 `MotionPhoto` / `Item:Length` 标记，但
 * [MotionPhotoParser] 会把它判成非实况：要么 `Item:Length` 已经大于副本体积，
 * 要么算出的 offset 落在图片正文里、`ftyp` 校验不通过。所以不需要改动 XMP。
 */
@Singleton
class MotionPhotoConverter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val parser: MotionPhotoParser
) {

    private val resolver get() = context.contentResolver

    /** 在原图同一相册里另存一份去掉视频的副本，原图不动 */
    suspend fun exportStatic(photo: PhotoItem, info: LiveInfo): StaticExportResult =
        withContext(Dispatchers.IO) {
            runCatching { export(photo, info) }
                .getOrElse { error ->
                    Log.w(TAG, "导出静态副本失败：${photo.displayName}", error)
                    StaticExportResult.Failed("导出失败：${error.message ?: "未知错误"}")
                }
        }

    private fun export(photo: PhotoItem, info: LiveInfo): StaticExportResult {
        if (photo.isVideo) return StaticExportResult.NotLive
        val offset = info.offsetBytes
        val size = photo.size.takeIf { it > 0 } ?: parser.fileSize(photo.uri) ?: 0L
        if (offset <= 0L || offset >= size) return StaticExportResult.NotLive
        // 探测结果是缓存，文件可能已经被别处改动过：确认这个位置真的是视频起点
        if (!parser.hasFtypAt(photo.uri, offset)) return StaticExportResult.NotLive

        val relativePath = relativePathOf(photo)
        if (relativePath.isBlank()) {
            return StaticExportResult.Failed("无法确定原图所在的相册目录")
        }
        val displayName = uniqueDisplayName(relativePath, staticNameOf(photo.displayName))
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportToMediaStore(photo, offset, relativePath, displayName)
        } else {
            exportToFile(photo, offset, relativePath, displayName)
        }
    }

    /** Android 10+：先插一条 IS_PENDING 记录再写流，中途失败不会在相册里留下半张图 */
    private fun exportToMediaStore(
        photo: PhotoItem,
        bytes: Long,
        relativePath: String,
        displayName: String
    ): StaticExportResult {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, photo.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val target = runCatching {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return StaticExportResult.Failed("无法在相册里创建新图片")

        val written = runCatching {
            resolver.openOutputStream(target)?.use { output ->
                copyPrefix(photo.uri, bytes, output)
            } ?: throw IllegalStateException("无法写入新图片")
        }.isSuccess

        if (!written) {
            runCatching { resolver.delete(target, null, null) }
            return StaticExportResult.Failed("写入新图片失败")
        }

        runCatching {
            resolver.update(
                target,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
        }.onFailure { Log.w(TAG, "新图片取消 IS_PENDING 失败", it) }

        Log.i(TAG, "静态副本：${photo.displayName} → $displayName（$bytes B）")
        return StaticExportResult.Success(
            displayName = displayName,
            newId = runCatching { ContentUris.parseId(target) }.getOrNull()
        )
    }

    /** Android 9 及以下：没有 RELATIVE_PATH / IS_PENDING，直接落文件再通知媒体库扫描 */
    private fun exportToFile(
        photo: PhotoItem,
        bytes: Long,
        relativePath: String,
        displayName: String
    ): StaticExportResult {
        val dir = File(Environment.getExternalStorageDirectory(), relativePath)
        if (!dir.exists() && !dir.mkdirs()) {
            return StaticExportResult.Failed("没有写入该目录的权限")
        }
        val target = File(dir, displayName)
        val written = runCatching {
            FileOutputStream(target).use { output -> copyPrefix(photo.uri, bytes, output) }
        }.isSuccess

        if (!written) {
            target.delete()
            return StaticExportResult.Failed("写入新图片失败")
        }
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
        Log.i(TAG, "静态副本：${photo.displayName} → $displayName（$bytes B）")
        return StaticExportResult.Success(displayName = displayName, newId = null)
    }

    /** 只复制前 [bytes] 个字节；按 128KB 分块，不把整张图读进内存 */
    private fun copyPrefix(source: Uri, bytes: Long, output: OutputStream) {
        val descriptor = resolver.openFileDescriptor(source, "r")
            ?: throw IllegalStateException("无法打开原图")
        descriptor.use {
            FileInputStream(it.fileDescriptor).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                var remaining = bytes
                while (remaining > 0) {
                    val want = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = input.read(buffer, 0, want)
                    if (read <= 0) throw IllegalStateException("读取原图失败")
                    output.write(buffer, 0, read)
                    remaining -= read
                }
                output.flush()
            }
        }
    }

    /** 原图所在相册的相对路径；拿不到时用绝对路径反推 */
    private fun relativePathOf(photo: PhotoItem): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val queried = runCatching {
                resolver.query(
                    photo.uri,
                    arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
                    null,
                    null,
                    null
                )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else "" }
                    .orEmpty()
            }.getOrDefault("")
            if (queried.isNotBlank()) return queried
        }
        val parent = photo.path.takeIf { it.isNotBlank() }?.let { File(it).parentFile } ?: return ""
        val root = Environment.getExternalStorageDirectory().absolutePath
        val relative = parent.absolutePath.removePrefix(root).trim('/')
        return if (relative.isEmpty()) "" else "$relative/"
    }

    /** `IMG_1234.jpg` → `IMG_1234_static.jpg` */
    private fun staticNameOf(displayName: String): String {
        val dot = displayName.lastIndexOf('.')
        return if (dot > 0) {
            "${displayName.substring(0, dot)}$NAME_SUFFIX${displayName.substring(dot)}"
        } else {
            "$displayName$NAME_SUFFIX"
        }
    }

    /** 同目录已有同名文件时追加 -1、-2（与移动照片的命名规则一致） */
    private fun uniqueDisplayName(relativePath: String, displayName: String): String {
        if (!existsIn(relativePath, displayName)) return displayName
        val dot = displayName.lastIndexOf('.')
        val base = if (dot > 0) displayName.substring(0, dot) else displayName
        val suffix = if (dot > 0) displayName.substring(dot) else ""
        var index = 1
        var candidate = "$base-$index$suffix"
        while (existsIn(relativePath, candidate)) {
            index++
            candidate = "$base-$index$suffix"
        }
        return candidate
    }

    private fun existsIn(relativePath: String, displayName: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val inMediaStore = runCatching {
                resolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND " +
                        "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                    arrayOf(relativePath, displayName),
                    null
                )?.use { it.moveToFirst() }
            }.getOrNull()
            if (inMediaStore == true) return true
        }
        // MediaStore 可能还没扫描到刚放进去的文件，再看一眼磁盘
        return File(File(Environment.getExternalStorageDirectory(), relativePath), displayName).exists()
    }

    private companion object {
        /** 诊断：`adb logcat -s InkLive` */
        const val TAG = "InkLive"

        const val NAME_SUFFIX = "_static"

        const val BUFFER_SIZE = 128 * 1024
    }
}
