package com.ink.gallery.data.live

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.ink.gallery.data.model.PhotoItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 动态照片（Motion Photo / 实况图）解析器。
 *
 * 只用 MediaStore 里已有的元数据 + 直接读文件，不依赖任何厂商 SDK：
 *
 * - 新标准：XMP 的 `Container:Directory` 里 `Item:Semantic="MotionPhoto"` 那一项带
 *   `Item:Length`（视频字节数），视频起点 = 文件大小 − 该值；
 * - 旧标准：`Camera:MicroVideoOffset`，语义相同（从文件末尾回退的字节数）；
 * - 兜底：XMP 被编辑器删掉时，从文件尾部若干 MB 里倒着找 `ftyp` 盒，
 *   视频起点 = `ftyp` 偏移 − 4。
 *
 * 三种来源算出的结果都要用「该位置确实存在 `ftyp`」再校验一次：官方规范明确写了
 * `MotionPhoto=1` 不可信（编辑器可能删了视频却留下 XMP），所以标志位只用来
 * 「决定要不要尝试」，不当作结论。
 */
@Singleton
class MotionPhotoParser @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val resolver: ContentResolver get() = context.contentResolver

    /** 返回内嵌视频的位置；null 表示这张图不是实况图 */
    suspend fun parse(photo: PhotoItem): LiveInfo? = withContext(Dispatchers.IO) {
        if (!photo.mimeType.isMotionPhotoCandidate()) return@withContext null
        val size = photo.size.takeIf { it > 0 } ?: fileSize(photo.uri) ?: return@withContext null
        if (size < MIN_FILE_BYTES) return@withContext null

        val fromXmp = runCatching { parseXmp(photo.uri, size) }.getOrNull()
        val fromTail = if (fromXmp == null) {
            runCatching { scanTailForFtyp(photo.uri, size) }.getOrNull()
        } else {
            null
        }
        val found = fromXmp ?: fromTail ?: run {
            Log.d(TAG, "非实况：${photo.displayName}")
            return@withContext null
        }
        if (!hasFtypAt(photo.uri, found.offsetBytes)) {
            // 标志位说是实况、但尾部没有视频：编辑器删掉了视频却留下了 XMP
            Log.i(TAG, "标志位是实况但未找到视频：${photo.displayName}")
            return@withContext null
        }
        val duration = readDuration(photo.uri, found.offsetBytes, found.lengthBytes)
        Log.i(
            TAG,
            "实况：${photo.displayName} 来源=${if (fromXmp != null) "XMP" else "ftyp"} " +
                "offset=${found.offsetBytes} length=${found.lengthBytes} " +
                "duration=${duration}ms size=$size"
        )
        found.copy(durationMs = duration)
    }

    /**
     * 从文件头部的 XMP 里取视频长度。
     *
     * 只读前 [XMP_HEAD_BYTES] 字节：JPEG 的 XMP 是紧跟在 EXIF 后面的一个 APP1 段，
     * 正常文件几十 KB 以内就能读到；读整张图（几 MB × 上万张）会把首扫 IO 打爆。
     */
    private fun parseXmp(uri: Uri, size: Long): LiveInfo? {
        val head = readAt(uri, 0L, minOf(size, XMP_HEAD_BYTES)) ?: return null
        // 只关心 ASCII 的属性名和数字，按单字节解码可以避免多字节字符被切半
        val xmp = String(head, Charsets.ISO_8859_1)
        if (!xmp.contains("MotionPhoto") && !xmp.contains("MicroVideo")) return null

        val lengths = LENGTH_REGEX.findAll(xmp)
            .mapNotNull { it.groupValues[1].toLongOrNull() }
            .toList()
        // 规范要求视频项位于文件绝对末尾，所以「最后一个非零 Length」就是视频；主图的 Length 写 0
        val videoLength = lengths.lastOrNull { it > 0 }
            ?: MICRO_VIDEO_REGEX.find(xmp)?.groupValues?.get(1)?.toLongOrNull()
            ?: return null
        if (videoLength <= 0 || videoLength >= size) return null

        val timestampUs = PTS_REGEX.find(xmp)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
        return LiveInfo(
            offsetBytes = size - videoLength,
            lengthBytes = videoLength,
            presentationTimestampUs = timestampUs
        )
    }

    /**
     * XMP 缺失时的兜底：从尾部窗口里倒着找 `ftyp`，并校验盒长度与 brand。
     * 命中后视频终点就是文件末尾（规范要求视频之后不得再有数据）。
     */
    private fun scanTailForFtyp(uri: Uri, size: Long): LiveInfo? {
        val window = minOf(size, TAIL_SCAN_BYTES)
        val bytes = readAt(uri, size - window, window) ?: return null
        var index = bytes.size - 8
        while (index >= 4) {
            if (bytes[index] == 'f'.code.toByte() &&
                bytes[index + 1] == 't'.code.toByte() &&
                bytes[index + 2] == 'y'.code.toByte() &&
                bytes[index + 3] == 'p'.code.toByte()
            ) {
                val boxSize = readUInt32(bytes, index - 4)
                val brand = String(bytes, index + 4, 4, Charsets.ISO_8859_1)
                if (boxSize in MIN_FTYP_BOX..MAX_FTYP_BOX && brand.isVideoBrand()) {
                    val offset = size - window + index - 4
                    val length = size - offset
                    if (length >= MIN_VIDEO_BYTES) {
                        return LiveInfo(offsetBytes = offset, lengthBytes = length)
                    }
                }
            }
            index--
        }
        return null
    }

    private fun hasFtypAt(uri: Uri, offset: Long): Boolean {
        if (offset < 0) return false
        val head = readAt(uri, offset, 12L) ?: return false
        if (head.size < 8) return false
        return head[4] == 'f'.code.toByte() &&
            head[5] == 't'.code.toByte() &&
            head[6] == 'y'.code.toByte() &&
            head[7] == 'p'.code.toByte()
    }

    /**
     * 取片段时长。只有确认是实况图的条目才会走这里（数量级在几百以内），
     * 用 fd + 区间让 MediaMetadataRetriever 直接读尾部那段 MP4 的头部即可。
     */
    private fun readDuration(uri: Uri, offset: Long, length: Long): Long {
        val descriptor = runCatching { resolver.openFileDescriptor(uri, "r") }.getOrNull()
            ?: return 0L
        val retriever = MediaMetadataRetriever()
        return try {
            descriptor.use {
                retriever.setDataSource(it.fileDescriptor, offset, length)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取实况时长失败", e)
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun fileSize(uri: Uri): Long? = runCatching {
        resolver.openAssetFileDescriptor(uri, "r")?.use { it.length.takeIf { size -> size > 0 } }
    }.getOrNull()

    /**
     * 读取文件指定区间。走 `openFileDescriptor` + channel 定位：
     * MediaStore 背后是真实文件，支持随机定位，不用把前面几 MB 全读一遍。
     */
    private fun readAt(uri: Uri, offset: Long, length: Long): ByteArray? {
        if (offset < 0 || length <= 0 || length > Int.MAX_VALUE) return null
        val want = length.toInt()
        return runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                val stream = FileInputStream(descriptor.fileDescriptor)
                stream.channel.position(offset)
                val buffer = ByteArray(want)
                var read = 0
                while (read < want) {
                    val count = stream.read(buffer, read, want - read)
                    if (count <= 0) break
                    read += count
                }
                when {
                    read == 0 -> null
                    read == want -> buffer
                    else -> buffer.copyOf(read)
                }
            }
        }.getOrNull()
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 4 > bytes.size) return -1L
        return ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
    }

    private fun String.isVideoBrand(): Boolean = when (this) {
        "isom", "iso2", "iso5", "iso6", "mp41", "mp42", "mmp4", "MSNV", "dash", "qt  ", "M4V ", "avc1" -> true
        else -> false
    }

    private companion object {
        /** 启动诊断：`adb logcat -s InkLive` */
        const val TAG = "InkLive"

        /** 小于这个体积不可能塞下静态图 + 视频 */
        const val MIN_FILE_BYTES = 64L * 1024

        /** XMP 只在文件头部找这么多字节 */
        const val XMP_HEAD_BYTES = 128L * 1024

        /** XMP 缺失时从尾部回扫的窗口大小 */
        const val TAIL_SCAN_BYTES = 4L * 1024 * 1024

        /** 内嵌视频不会小于这个体积，用来排除误命中的 `ftyp` */
        const val MIN_VIDEO_BYTES = 32L * 1024

        /** `ftyp` 盒长度合理区间（通常 16~32 字节） */
        const val MIN_FTYP_BOX = 8L
        const val MAX_FTYP_BOX = 4096L

        val LENGTH_REGEX = Regex("""Item:Length\s*=\s*["'](\d+)["']""")
        val MICRO_VIDEO_REGEX = Regex("""MicroVideoOffset\s*=\s*["'](\d+)["']""")
        val PTS_REGEX = Regex("""MotionPhotoPresentationTimestampUs\s*=\s*["'](-?\d+)["']""")
    }
}

/** 可能内嵌视频的图片类型（实况图只会在这些格式里出现） */
fun String.isMotionPhotoCandidate(): Boolean = when (lowercase()) {
    "image/jpeg", "image/jpg", "image/heic", "image/heif", "image/avif" -> true
    else -> false
}
