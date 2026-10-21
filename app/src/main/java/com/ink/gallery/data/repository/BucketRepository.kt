package com.ink.gallery.data.repository

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import com.ink.gallery.data.model.Bucket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BucketRepository @Inject constructor(
    private val contentResolver: ContentResolver,
    private val photoRepository: PhotoRepository
) {

    /**
     * 返回所有相册，按**相册目录自身的修改时间（mtime）**倒序——与果仁相册等主流相册一致：
     * 目录里有过增删/重命名的相册（比如刚往里搬过照片）会排到最前。
     *
     * 注意根目录（`relativePath` 为 `/`）在 FUSE 上层 mtime 长期不变，
     * 所以「根目录」相册会稳定排在后面。
     *
     * MediaStore 不支持 GROUP BY，这里一次性遍历并聚合，
     * 查询按照片修改时间倒序，取每个 bucket 的第一张（最新的）作为封面。
     * 图片与视频都参与聚合（同一个目录在两张表里的 bucket_id 相同），
     * 封面优先取图片，纯视频目录则用视频首帧当封面。
     */
    suspend fun getBuckets(): List<Bucket> = withContext(Dispatchers.IO) {
        val buckets = LinkedHashMap<Long, Bucket>()
        val counts = HashMap<Long, Int>()

        collectBuckets(IMAGE_COLLECTION, PROJECTION, buckets, counts)
        collectBuckets(VIDEO_COLLECTION, PROJECTION, buckets, counts)

        buckets.values
            .map { bucket -> bucket.copy(count = counts[bucket.id] ?: 0) }
            .sortedByDescending { albumTime(it.relativePath) }
    }

    private fun collectBuckets(
        collection: Uri,
        projection: Array<String>,
        buckets: LinkedHashMap<Long, Bucket>,
        counts: HashMap<Long, Int>
    ) {
        // 只授予图片或只授予视频权限时，查另一个集合会抛 SecurityException，直接跳过
        val cursor = runCatching {
            contentResolver.query(
                collection,
                projection,
                null,
                null,
                "$DATE_MODIFIED DESC, $ID DESC"
            )
        }.getOrNull()
        cursor?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(ID)
            val bucketIdIndex = cursor.getColumnIndexOrThrow(BUCKET_ID)
            val bucketNameIndex = cursor.getColumnIndexOrThrow(BUCKET_DISPLAY_NAME)
            val dateModifiedIndex = cursor.getColumnIndexOrThrow(DATE_MODIFIED)
            val relativePathIndex = cursor.getColumnIndex(RELATIVE_PATH)
            val dataIndex = cursor.getColumnIndex(DATA_PATH)
            while (cursor.moveToNext()) {
                // 跳过指向其它多用户目录（/storage/emulated/995 之类）的幽灵记录，
                // 否则同一目录会因为多出一条记录而在相册页显示成两个同名相册
                if (dataIndex >= 0) {
                    val data = cursor.getString(dataIndex).orEmpty()
                    if (data.startsWith(OTHER_USER_PREFIX) && !data.startsWith(USER_STORAGE_PREFIX)) {
                        continue
                    }
                }
                val mediaId = cursor.getLong(idIndex)
                val bucketId = cursor.getLong(bucketIdIndex)
                val bucketName = cursor.getString(bucketNameIndex).orEmpty().ifEmpty { DEFAULT_BUCKET_NAME }

                counts[bucketId] = (counts[bucketId] ?: 0) + 1
                if (!buckets.containsKey(bucketId)) {
                    buckets[bucketId] = Bucket(
                        id = bucketId,
                        name = bucketName,
                        coverUri = ContentUris.withAppendedId(collection, mediaId),
                        count = 0,
                        latestModified = cursor.getLong(dateModifiedIndex) * 1000L,
                        relativePath = if (relativePathIndex >= 0) {
                            cursor.getString(relativePathIndex).orEmpty()
                        } else {
                            ""
                        }
                    )
                }
            }
        }
    }

    /**
     * 相册的排序时间：目录 mtime 与本进程内搬动过的时间取较大值。
     *
     * 只读 mtime 不够：MediaProvider 改完数据库后真正落盘会滞后，而且新目录的 mtime
     * 还会被 FUSE 属性缓存压住几秒，刚搬完照片的相册就会停在旧位置。
     */
    private fun albumTime(relativePath: String): Long {
        val dir = File(Environment.getExternalStorageDirectory(), relativePath.trim('/'))
        val touched = photoRepository.albumTouchedAt(dir.absolutePath.trimEnd('/'))
        return maxOf(dir.lastModified(), touched)
    }

    /** 相册名优先从图片集合取，纯视频目录再查视频集合 */
    suspend fun getBucketName(bucketId: Long): String? = withContext(Dispatchers.IO) {
        listOf(IMAGE_COLLECTION, VIDEO_COLLECTION).firstNotNullOfOrNull { collection ->
            runCatching {
                contentResolver.query(
                    collection,
                    arrayOf(BUCKET_DISPLAY_NAME),
                    "$BUCKET_ID = ?",
                    arrayOf(bucketId.toString()),
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull()
        }
    }

    private companion object {
        val IMAGE_COLLECTION: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val VIDEO_COLLECTION: Uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

        const val ID = MediaStore.Images.Media._ID
        const val BUCKET_ID = MediaStore.Images.Media.BUCKET_ID
        const val BUCKET_DISPLAY_NAME = MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        const val DATE_MODIFIED = MediaStore.Images.Media.DATE_MODIFIED
        const val RELATIVE_PATH = MediaStore.MediaColumns.RELATIVE_PATH
        const val DATA_PATH = MediaStore.MediaColumns.DATA

        /** 当前用户的共享存储前缀，如 /storage/emulated/0/ */
        val USER_STORAGE_PREFIX: String = "/storage/emulated/${Process.myUid() / 100000}/"

        /** 其它多用户的共享存储前缀，如 /storage/emulated/995/ */
        const val OTHER_USER_PREFIX = "/storage/emulated/"

        /** 存储根目录（/storage/emulated/0/）的 bucket_display_name 为 NULL，这里兜底命名 */
        const val DEFAULT_BUCKET_NAME = "根目录"

        val PROJECTION: Array<String> = buildList {
            add(ID)
            add(BUCKET_ID)
            add(BUCKET_DISPLAY_NAME)
            add(DATE_MODIFIED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(RELATIVE_PATH)
            // _data 只在拿到「所有文件访问权限」时可读，用于识别幽灵记录
            add(DATA_PATH)
        }.toTypedArray()
    }
}
