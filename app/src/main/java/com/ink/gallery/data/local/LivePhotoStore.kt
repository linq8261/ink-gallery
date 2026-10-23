package com.ink.gallery.data.local

import android.content.Context
import android.util.Log
import com.ink.gallery.data.live.LiveInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 实况图探测结果的磁盘缓存。
 *
 * 探测一张图要读它的文件头，万张图库的首扫成本不低，所以结果必须落盘：
 * 冷启动读一个几百 KB 的顺序文件就能恢复全部 LIVE 角标，后台不再重复探测。
 *
 * 用普通二进制文件而不是 Room，是因为这份数据**纯粹是缓存**——
 * 丢了最多重新探测一遍，不值得为它升数据库版本、写迁移。
 *
 * 格式（小端，`DataOutputStream`）：
 * ```
 * int    magic
 * short  version
 * int    count
 * count × { long photoId, byte hasVideo, long offset, long length, long durationMs, long timestampUs }
 * ```
 * `hasVideo = 0` 表示「探测过但不是实况」，这条负结果同样要记下来，
 * 否则每次启动都会把同一批普通照片重读一遍。
 */
@Singleton
class LivePhotoStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val file = File(context.cacheDir, FILE_NAME)
    private val tempFile = File(context.cacheDir, "$FILE_NAME.tmp")

    /** 读缓存；null 表示没有可用缓存（不存在／版本不符／已损坏），会顺手删掉坏文件 */
    suspend fun read(): Map<Long, LiveInfo?>? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        val entries = runCatching {
            DataInputStream(
                BufferedInputStream(file.inputStream(), BUFFER_SIZE)
            ).use { input ->
                if (input.readInt() != MAGIC) return@use null
                if (input.readShort() != VERSION) return@use null
                val count = input.readInt()
                if (count < 0 || count > MAX_COUNT) return@use null
                HashMap<Long, LiveInfo?>(count).also { map ->
                    repeat(count) {
                        val photoId = input.readLong()
                        val hasVideo = input.readByte().toInt() == 1
                        val offset = input.readLong()
                        val length = input.readLong()
                        val duration = input.readLong()
                        val timestamp = input.readLong()
                        map[photoId] = if (hasVideo && length > 0) {
                            LiveInfo(offset, length, duration, timestamp)
                        } else {
                            null
                        }
                    }
                }
            }
        }.onFailure { Log.w(TAG, "读取实况缓存失败", it) }.getOrNull()

        if (entries == null) file.delete()
        entries
    }

    /** 覆盖写缓存。先写临时文件再 rename，避免进程被杀留下半截文件 */
    suspend fun write(entries: Map<Long, LiveInfo?>) = withContext(Dispatchers.IO) {
        runCatching {
            DataOutputStream(
                BufferedOutputStream(tempFile.outputStream(), BUFFER_SIZE)
            ).use { output ->
                output.writeInt(MAGIC)
                output.writeShort(VERSION.toInt())
                output.writeInt(entries.size)
                entries.forEach { (photoId, info) ->
                    output.writeLong(photoId)
                    output.writeByte(if (info != null) 1 else 0)
                    output.writeLong(info?.offsetBytes ?: 0L)
                    output.writeLong(info?.lengthBytes ?: 0L)
                    output.writeLong(info?.durationMs ?: 0L)
                    output.writeLong(info?.presentationTimestampUs ?: -1L)
                }
                output.flush()
            }
            if (!tempFile.renameTo(file)) {
                file.delete()
                tempFile.renameTo(file)
            }
        }.onFailure {
            Log.w(TAG, "写入实况缓存失败", it)
            tempFile.delete()
        }
        Unit
    }

    private companion object {
        const val TAG = "InkLive"

        const val FILE_NAME = "live_photo.bin"

        /** "INKL"：改结构时换 magic，老文件自然失效 */
        const val MAGIC = 0x494E4B4C

        const val VERSION: Short = 1

        const val BUFFER_SIZE = 32 * 1024

        const val MAX_COUNT = 500_000
    }
}
