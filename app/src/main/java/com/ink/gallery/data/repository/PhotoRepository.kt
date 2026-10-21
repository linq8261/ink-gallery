package com.ink.gallery.data.repository

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.ink.gallery.data.model.PhotoItem
import com.ink.gallery.data.paging.PhotoPagingSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.WeakHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 照片仓库。
 *
 * 不能在 ContentResolver.query 的 sortOrder 里拼接 `LIMIT/OFFSET`：新版 MediaProvider
 * 对 sortOrder 做严格 SQL 校验，会直接抛 `IllegalArgumentException: Invalid token LIMIT`。
 *
 * 这里改为「一次读取全量元数据 + 内存分页」：只投影轻量列、不触碰位图，
 * 万张级别的元数据也只有几 MB；[PhotoPagingSource] 在内存列表上切片，
 * 滚动翻页零查询。媒体库变化时由 ContentObserver 兜底失效缓存与分页快照。
 */
@Singleton
class PhotoRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val contentResolver: ContentResolver
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cacheMutex = Mutex()

    @Volatile
    private var cachedPhotos: List<PhotoItem>? = null

    @Volatile
    private var cachedGroups: Map<Long, List<PhotoItem>>? = null

    private val pagingSources = WeakHashMap<PhotoPagingSource, Unit>()
    private var invalidateJob: Job? = null

    /** 媒体库或照片归属变化时发出，供相册页等界面刷新自己的数据 */
    private val _mediaChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val mediaChanges: SharedFlow<Unit> = _mediaChanges.asSharedFlow()

    private val mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            scheduleInvalidate()
        }
    }

    init {
        contentResolver.registerContentObserver(COLLECTION, true, mediaObserver)
    }

    fun pagingSource(bucketId: Long? = null): PhotoPagingSource =
        PhotoPagingSource { loadPhotos(bucketId) }.also { source ->
            synchronized(pagingSources) { pagingSources[source] = Unit }
        }

    suspend fun getPhoto(photoId: Long): PhotoItem? {
        loadPhotos(null).firstOrNull { it.id == photoId }?.let { return it }
        return withContext(Dispatchers.IO) {
            queryPhotos(
                selection = "$ID = ?",
                selectionArgs = arrayOf(photoId.toString())
            ).firstOrNull()
        }
    }

    /**
     * 把照片移动到目标相册（本质是改 MediaStore 记录的 relative_path）。
     *
     * 修改非本 App 创建的照片时系统会拦截：Android 11+ 通过
     * [MediaStore.createWriteRequest] 弹授权框；Android 10 抛
     * RecoverableSecurityException，其 userMessage.actionIntent 就是授权入口。
     * 两者都通过 [MovePhotoResult.NeedPermission] 把授权 intent 交给界面启动，
     * 授权后重试本方法即可成功。
     *
     * 注意根目录相册（MediaStore 里 relative_path 为 `/`，界面上名为「其他」的那类）：
     * 必须原样写入 `/`，写空串会被 MediaProvider 静默改写成 `Pictures/`，
     * 照片根本不会进目标相册（update 却返回 1，看上去像成功了）。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun movePhoto(photo: PhotoItem, targetRelativePath: String): MovePhotoResult =
        withContext(Dispatchers.IO) {
            val directory = targetRelativePath.trim('/').let { if (it.isEmpty()) "/" else "$it/" }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH, directory)
            }
            try {
                val updated = contentResolver.update(photo.uri, values, null, null)
                if (updated <= 0) {
                    MovePhotoResult.Failed("移动失败：未找到该照片")
                } else {
                    // update 成功不代表真的挪过去了，落到哪个目录以回查结果为准
                    val actual = queryRelativePath(photo.uri)
                    if (actual.trim('/') == directory.trim('/')) {
                        invalidate()
                        MovePhotoResult.Moved
                    } else {
                        MovePhotoResult.Failed(
                            "移动未生效：照片被系统放到了「${actual.ifEmpty { "/" }}」"
                        )
                    }
                }
            } catch (e: SecurityException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val request = MediaStore.createWriteRequest(contentResolver, listOf(photo.uri))
                    MovePhotoResult.NeedPermission(request.intentSender)
                } else {
                    // Android 10：用反射取 RecoverableSecurityException.userMessage.actionIntent，
                    // 避免直接引用在某些 SDK 桩中解析不到的成员
                    val sender = runCatching {
                        val userMessage = e.javaClass.getMethod("getUserMessage").invoke(e)
                        val actionIntent = userMessage?.javaClass
                            ?.getMethod("getActionIntent")
                            ?.invoke(userMessage) as? PendingIntent
                        actionIntent?.intentSender
                    }.getOrNull()
                    if (sender != null) {
                        MovePhotoResult.NeedPermission(sender)
                    } else {
                        MovePhotoResult.Failed(e.message ?: "没有修改该照片的权限")
                    }
                }
            } catch (e: Exception) {
                // MediaProvider 对非法的 relative_path 会抛 IllegalArgumentException，
                // 这里兜住，避免整个 ViewModel 协程崩掉而界面没有任何提示
                MovePhotoResult.Failed(e.toMoveFailureMessage())
            }
        }

    private fun queryRelativePath(uri: Uri): String =
        contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
        }.orEmpty()

    /**
     * 完整的照片列表（从新到旧）。返回的是内存缓存的同一份列表引用，
     * 不额外占内存；[bucketId] 为 null 时返回全部照片，否则只返回该相册的照片。
     */
    suspend fun getPhotos(bucketId: Long? = null): List<PhotoItem> = loadPhotos(bucketId)

    /**
     * 读取照片元数据。首次会真正查询 MediaStore，之后命中内存缓存；
     * 传入 bucketId 时返回该相册的子集（同样来自缓存）。
     */
    private suspend fun loadPhotos(bucketId: Long? = null): List<PhotoItem> {
        val all = cachedPhotos ?: cacheMutex.withLock {
            cachedPhotos ?: withContext(Dispatchers.IO) { queryPhotos() }.also { photos ->
                cachedGroups = photos.groupBy { it.bucketId }
                cachedPhotos = photos
            }
        }
        if (bucketId == null) return all
        return cachedGroups?.get(bucketId).orEmpty()
    }

    private fun queryPhotos(
        selection: String? = null,
        selectionArgs: Array<String> = emptyArray()
    ): List<PhotoItem> {
        val photos = ArrayList<PhotoItem>()
        contentResolver.query(
            COLLECTION,
            PROJECTION,
            selection,
            selectionArgs.takeIf { it.isNotEmpty() },
            SORT_ORDER
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                photos += cursor.readPhoto()
            }
        }
        return photos
    }

    private fun Cursor.readPhoto(): PhotoItem {
        val id = getLong(getColumnIndexOrThrow(ID))
        val displayName = getString(getColumnIndexOrThrow(DISPLAY_NAME)).orEmpty()
        return PhotoItem(
            id = id,
            uri = ContentUris.withAppendedId(COLLECTION, id),
            displayName = displayName,
            mimeType = getString(getColumnIndexOrThrow(MIME_TYPE)).orEmpty(),
            dateTaken = getLong(getColumnIndexOrThrow(DATE_TAKEN)),
            dateModified = getLong(getColumnIndexOrThrow(DATE_MODIFIED)) * 1000L,
            size = getLong(getColumnIndexOrThrow(SIZE)),
            width = getInt(getColumnIndexOrThrow(WIDTH)),
            height = getInt(getColumnIndexOrThrow(HEIGHT)),
            bucketId = getLong(getColumnIndexOrThrow(BUCKET_ID)),
            bucketName = getString(getColumnIndexOrThrow(BUCKET_DISPLAY_NAME)).orEmpty(),
            path = readAbsolutePath(displayName)
        )
    }

    /**
     * MediaStore 的 _data 在 Android 10+ 对普通 App 被屏蔽（恒为 null），
     * 因此新系统用 relative_path（如 `Pictures/Screenshots/`）拼出绝对路径；
     * Android 9 及以下直接读 _data。
     */
    @Suppress("DEPRECATION")
    private fun Cursor.readAbsolutePath(displayName: String): String {
        val relativeIndex = getColumnIndex(RELATIVE_PATH)
        if (relativeIndex >= 0) {
            val relativePath = getString(relativeIndex).orEmpty()
            if (displayName.isNotEmpty()) {
                val root = Environment.getExternalStorageDirectory().absolutePath
                val dir = relativePath.trim('/')
                return if (dir.isEmpty()) "$root/$displayName" else "$root/$dir/$displayName"
            }
        }
        val dataIndex = getColumnIndex(DATA_PATH)
        if (dataIndex >= 0) {
            return getString(dataIndex).orEmpty()
        }
        return ""
    }

    /**
     * 手动刷新（下拉刷新）：丢弃内存缓存并通知相册页等界面。
     * 分页列表由调用方通过 `LazyPagingItems.refresh()` 重新读取。
     */
    fun refresh() {
        cachedPhotos = null
        cachedGroups = null
        _mediaChanges.tryEmit(Unit)
    }

    private fun scheduleInvalidate() {
        invalidateJob?.cancel()
        invalidateJob = scope.launch {
            // 媒体库批量变化会连续回调，做一次轻量防抖
            delay(INVALIDATE_DEBOUNCE_MS)
            invalidate()
        }
    }

    private fun invalidate() {
        cachedPhotos = null
        cachedGroups = null
        _mediaChanges.tryEmit(Unit)
        val sources = synchronized(pagingSources) {
            pagingSources.keys.toList().also { pagingSources.clear() }
        }
        sources.forEach { it.invalidate() }
    }

    private companion object {
        const val INVALIDATE_DEBOUNCE_MS = 300L

        val COLLECTION: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        const val ID = MediaStore.Images.Media._ID
        const val DISPLAY_NAME = MediaStore.Images.Media.DISPLAY_NAME
        const val MIME_TYPE = MediaStore.Images.Media.MIME_TYPE
        const val DATE_TAKEN = MediaStore.Images.Media.DATE_TAKEN
        const val DATE_MODIFIED = MediaStore.Images.Media.DATE_MODIFIED
        const val SIZE = MediaStore.Images.Media.SIZE
        const val WIDTH = MediaStore.Images.Media.WIDTH
        const val HEIGHT = MediaStore.Images.Media.HEIGHT
        const val BUCKET_ID = MediaStore.Images.Media.BUCKET_ID
        const val BUCKET_DISPLAY_NAME = MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        const val RELATIVE_PATH = MediaStore.MediaColumns.RELATIVE_PATH
        const val DATA_PATH = MediaStore.MediaColumns.DATA

        val PROJECTION: Array<String> = buildList {
            add(ID)
            add(DISPLAY_NAME)
            add(MIME_TYPE)
            add(DATE_TAKEN)
            add(DATE_MODIFIED)
            add(SIZE)
            add(WIDTH)
            add(HEIGHT)
            add(BUCKET_ID)
            add(BUCKET_DISPLAY_NAME)
            // Android 10+ 用 relative_path 还原路径，旧系统还能读 _data
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(RELATIVE_PATH) else add(DATA_PATH)
        }.toTypedArray()

        const val SORT_ORDER = "$DATE_TAKEN DESC, $ID DESC"
    }
}

sealed interface MovePhotoResult {
    data object Moved : MovePhotoResult
    data class NeedPermission(val intentSender: IntentSender) : MovePhotoResult
    data class Failed(val message: String) : MovePhotoResult
}

/**
 * 把 MediaProvider 的英文异常转成能看懂的提示。
 * 典型例子：`Primary directory 111sync not allowed for content://...;
 * allowed directories are [DCIM, Pictures]`。
 */
private fun Exception.toMoveFailureMessage(): String {
    val raw = message.orEmpty()
    return when {
        raw.contains("Primary directory", ignoreCase = true) ->
            "系统不允许移动到该相册：图片只能移到 DCIM 或 Pictures 下的目录"

        raw.isNotEmpty() -> raw
        else -> "移动失败"
    }
}
