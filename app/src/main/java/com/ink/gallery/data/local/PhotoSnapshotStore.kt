package com.ink.gallery.data.local

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import com.ink.gallery.data.model.MediaType
import com.ink.gallery.data.model.PhotoItem
import com.ink.gallery.util.PermissionUtil
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
 * 照片元数据的磁盘快照。
 *
 * 只解决一件事：冷启动时**先有东西可显示**。进程内缓存（[com.ink.gallery.data.repository.PhotoRepository]
 * 的 `cachedPhotos`）在进程被杀后必然清空，而一次全量 MediaStore 遍历在万张图库上要几百毫秒，
 * 这段时间界面只能转圈。把上一次的元数据按条写进一个二进制文件，启动时顺序读回来只需几十毫秒，
 * 首帧即可把整份列表交给网格——缩略图本身还在 Coil 的磁盘缓存里，于是图也直接出来。
 *
 * 快照**只是渲染用的副本，永远不是真相**：调用方读完它之后仍要在后台跑一次全量查询核对，
 * 结果不一致就以查询结果为准。因此这里不做 MediaStore generation token 之类的精细失效，
 * 只按「格式版本 + 授权指纹」做两道廉价闸门。
 *
 * 格式（小端，`DataOutputStream`）：
 * ```
 * int    magic
 * short  version
 * UTF    authorizationFingerprint
 * int    count
 * count × {
 *   long id, byte mediaType, UTF displayName, UTF mimeType,
 *   long dateTaken, long dateModified, long size,
 *   int width, int height, long bucketId, UTF bucketName, UTF path, long durationMs
 * }
 * ```
 * `uri` 不落盘，由 `id` + `mediaType` 还原，省一次字符串读写。
 */
@Singleton
class PhotoSnapshotStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val snapshotFile = File(context.cacheDir, FILE_NAME)
    private val tempFile = File(context.cacheDir, "$FILE_NAME.tmp")

    /**
     * 读快照。返回 null 表示「没有可用快照」——文件不存在、格式版本不符、
     * 授权范围与写入时不同（用户撤权 / 只授权了部分媒体类型），或文件已损坏。
     * 凡是判为不可用的情况都会顺手删掉文件，避免下次继续白读。
     */
    suspend fun read(): List<PhotoItem>? = withContext(Dispatchers.IO) {
        if (!snapshotFile.exists()) return@withContext null
        val photos = runCatching {
            DataInputStream(
                BufferedInputStream(snapshotFile.inputStream(), BUFFER_SIZE)
            ).use { input ->
                if (input.readInt() != MAGIC) {
                    Log.i(TAG, "快照作废：magic 不符")
                    return@use null
                }
                if (input.readShort() != VERSION) {
                    Log.i(TAG, "快照作废：格式版本不符")
                    return@use null
                }
                if (input.readUTF() != authorizationFingerprint()) {
                    Log.i(TAG, "快照作废：授权范围与写入时不同")
                    return@use null
                }
                val count = input.readInt()
                if (count < 0 || count > MAX_COUNT) {
                    Log.i(TAG, "快照作废：条数异常（$count）")
                    return@use null
                }
                ArrayList<PhotoItem>(count).also { list ->
                    repeat(count) { list += input.readItem() }
                }
            }
        }.onFailure { Log.w(TAG, "读取快照失败", it) }.getOrNull()
        if (photos == null) {
            snapshotFile.delete()
            return@withContext null
        }
        photos
    }

    /** 覆盖写快照。先写临时文件再 rename，避免进程在写一半时被杀留下半截文件。 */
    suspend fun write(photos: List<PhotoItem>) = withContext(Dispatchers.IO) {
        runCatching {
            DataOutputStream(
                BufferedOutputStream(tempFile.outputStream(), BUFFER_SIZE)
            ).use { output ->
                output.writeInt(MAGIC)
                output.writeShort(VERSION.toInt())
                output.writeUTF(authorizationFingerprint())
                output.writeInt(photos.size)
                photos.forEach { output.writeItem(it) }
                output.flush()
            }
            if (!tempFile.renameTo(snapshotFile)) {
                // 部分 ROM 上 rename 覆盖已存在文件会失败，退化为「先删再改名」
                snapshotFile.delete()
                tempFile.renameTo(snapshotFile)
            }
        }.onFailure {
            Log.w(TAG, "写入快照失败", it)
            tempFile.delete()
        }
        Unit
    }

    fun clear() {
        snapshotFile.delete()
        tempFile.delete()
    }

    private fun DataInputStream.readItem(): PhotoItem {
        val id = readLong()
        val mediaType = if (readByte() == MEDIA_TYPE_VIDEO) MediaType.VIDEO else MediaType.IMAGE
        val displayName = readUTF()
        val mimeType = readUTF()
        val dateTaken = readLong()
        val dateModified = readLong()
        val size = readLong()
        val width = readInt()
        val height = readInt()
        val bucketId = readLong()
        val bucketName = readUTF()
        val path = readUTF()
        val durationMs = readLong()
        return PhotoItem(
            id = id,
            uri = ContentUris.withAppendedId(collectionOf(mediaType), id),
            displayName = displayName,
            mimeType = mimeType,
            dateTaken = dateTaken,
            dateModified = dateModified,
            size = size,
            width = width,
            height = height,
            bucketId = bucketId,
            bucketName = bucketName,
            path = path,
            mediaType = mediaType,
            durationMs = durationMs
        )
    }

    private fun DataOutputStream.writeItem(photo: PhotoItem) {
        writeLong(photo.id)
        writeByte((if (photo.mediaType == MediaType.VIDEO) MEDIA_TYPE_VIDEO else MEDIA_TYPE_IMAGE).toInt())
        writeUTF(photo.displayName)
        writeUTF(photo.mimeType)
        writeLong(photo.dateTaken)
        writeLong(photo.dateModified)
        writeLong(photo.size)
        writeInt(photo.width)
        writeInt(photo.height)
        writeLong(photo.bucketId)
        writeUTF(photo.bucketName)
        writeUTF(photo.path)
        writeLong(photo.durationMs)
    }

    private fun collectionOf(mediaType: MediaType): Uri =
        if (mediaType == MediaType.VIDEO) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

    /**
     * 授权指纹：当前媒体权限集合里，每一项是「已授予」还是「未授予」。
     *
     * 用户从「完全访问」降级到「选中的照片」、或单独撤掉视频权限后，
     * 旧快照里会留着读不到的照片（网格上是一堆打不开的死格），
     * 指纹一变就直接作废，退回「先扫描后显示」的老路径。
     */
    private fun authorizationFingerprint(): String =
        PermissionUtil.mediaPermissions().joinToString(separator = "") { permission ->
            val granted = ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) "1" else "0"
        }

    private companion object {
        const val TAG = "InkStartup"

        const val FILE_NAME = "photo_snapshot.bin"

        /** "INK1"：改结构时直接换 magic，老文件自然失效，不必写迁移 */
        const val MAGIC = 0x494E4B31

        const val VERSION: Short = 1

        const val BUFFER_SIZE = 64 * 1024

        const val MAX_COUNT = 500_000

        const val MEDIA_TYPE_IMAGE: Byte = 0
        const val MEDIA_TYPE_VIDEO: Byte = 1
    }
}
