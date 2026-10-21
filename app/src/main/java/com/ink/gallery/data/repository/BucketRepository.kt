package com.ink.gallery.data.repository

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.MediaStore
import com.ink.gallery.data.model.Bucket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BucketRepository @Inject constructor(
    private val contentResolver: ContentResolver
) {

    /**
     * 按「相册内最新一张照片的修改时间」倒序返回所有相册。
     * MediaStore 不支持 GROUP BY，这里一次性遍历并聚合，取每个 bucket 的第一张（最新的）作为封面。
     */
    suspend fun getBuckets(): List<Bucket> = withContext(Dispatchers.IO) {
        val buckets = LinkedHashMap<Long, Bucket>()
        val counts = HashMap<Long, Int>()

        contentResolver.query(
            COLLECTION,
            PROJECTION,
            null,
            null,
            "$DATE_MODIFIED DESC, $ID DESC"
        )?.use { cursor ->
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
                val photoId = cursor.getLong(0)
                val bucketId = cursor.getLong(1)
                val bucketName = cursor.getString(2).orEmpty().ifEmpty { DEFAULT_BUCKET_NAME }

                counts[bucketId] = (counts[bucketId] ?: 0) + 1
                if (!buckets.containsKey(bucketId)) {
                    buckets[bucketId] = Bucket(
                        id = bucketId,
                        name = bucketName,
                        coverUri = ContentUris.withAppendedId(COLLECTION, photoId),
                        count = 0,
                        latestModified = cursor.getLong(3) * 1000L,
                        relativePath = if (relativePathIndex >= 0) {
                            cursor.getString(relativePathIndex).orEmpty()
                        } else {
                            ""
                        }
                    )
                }
            }
        }

        buckets.values.map { bucket -> bucket.copy(count = counts[bucket.id] ?: 0) }
    }

    suspend fun getBucketName(bucketId: Long): String? = withContext(Dispatchers.IO) {
        contentResolver.query(
            COLLECTION,
            arrayOf(BUCKET_DISPLAY_NAME),
            "$BUCKET_ID = ?",
            arrayOf(bucketId.toString()),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private companion object {
        val COLLECTION: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

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
