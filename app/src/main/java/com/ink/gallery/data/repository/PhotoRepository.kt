package com.ink.gallery.data.repository

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.database.ContentObserver
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.ink.gallery.data.model.PhotoItem
import com.ink.gallery.data.paging.PhotoPagingSource
import com.ink.gallery.util.StorageAccess
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
import java.io.File
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
    @ApplicationContext private val context: Context,
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
     * 把照片移动到目标相册。
     *
     * 优先改 MediaStore 的 `relative_path`。没有「所有文件访问权限」时，Android 11+
     * 只放行 `DCIM` / `Pictures` 下的目录，其余会抛
     * `IllegalArgumentException: Primary directory ... not allowed`；
     * 拿到该权限后改走文件搬运（rename）兜底，任何目录都能进。
     *
     * 修改非本 App 创建的照片时系统会拦截：Android 11+ 通过
     * [MediaStore.createWriteRequest] 弹授权框；Android 10 抛
     * RecoverableSecurityException，其 userMessage.actionIntent 就是授权入口。
     * 两者都通过 [MovePhotoResult.NeedPermission] 把授权 intent 交给界面启动，
     * 授权后重试本方法即可成功。
     *
     * 注意根目录相册（MediaStore 里 relative_path 为 `/`，界面上名为「根目录」）：
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
                    when {
                        actual.trim('/') == directory.trim('/') -> {
                            invalidate()
                            MovePhotoResult.Moved
                        }

                        StorageAccess.hasFullAccess() -> moveByFile(photo, directory)

                        else -> MovePhotoResult.Failed(
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
                // MediaProvider 对非法的 relative_path 会抛 IllegalArgumentException：
                // 有全盘权限时改用文件搬运兜底，否则把系统报错翻译成人话
                if (e is IllegalArgumentException && StorageAccess.hasFullAccess()) {
                    moveByFile(photo, directory)
                } else {
                    MovePhotoResult.Failed(e.toMoveFailureMessage())
                }
            }
        }

    /**
     * 直接搬文件（需要有「所有文件访问权限」），搬完通知媒体库重新扫描。
     * 用于把照片放进 `DCIM` / `Pictures` 之外的目录——这类目标 MediaStore 不允许改。
     */
    private fun moveByFile(photo: PhotoItem, targetDirectory: String): MovePhotoResult {
        val source = File(photo.path)
        if (photo.path.isEmpty() || !source.exists()) {
            return MovePhotoResult.Failed("移动失败：找不到原始文件")
        }
        val targetDir = File(Environment.getExternalStorageDirectory(), targetDirectory.trim('/'))
        if (!targetDir.isDirectory && !targetDir.mkdirs()) {
            return MovePhotoResult.Failed("移动失败：无法创建目标目录")
        }
        val displayName = photo.displayName.ifEmpty { source.name }
        val target = uniqueTarget(targetDir, displayName)

        val moved = source.renameTo(target) || runCatching {
            // 跨卷或 rename 失败时退回复制 + 删除
            source.copyTo(target, overwrite = false)
            source.delete()
        }.isSuccess
        if (!moved || !target.exists()) {
            return MovePhotoResult.Failed("移动失败：没有写入目标目录的权限")
        }

        MediaScannerConnection.scanFile(
            context,
            arrayOf(source.absolutePath, target.absolutePath),
            null,
            null
        )
        invalidate()
        return MovePhotoResult.Moved
    }

    /** 目标目录里已有同名文件时，追加 -1、-2 后缀 */
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
                cursor.readPhoto()?.let { photos += it }
            }
        }
        return photos
    }

    /**
     * 读取一行；返回 null 表示这条记录指向别的多用户（/storage/emulated/995 之类），
     * 当前用户既读不到文件，也会让同一个目录在相册页里裂成两个同名相册。
     */
    private fun Cursor.readPhoto(): PhotoItem? {
        val dataIndex = getColumnIndex(DATA_PATH)
        if (dataIndex >= 0) {
            val data = getString(dataIndex).orEmpty()
            if (data.startsWith(OTHER_USER_PREFIX) && !data.startsWith(USER_STORAGE_PREFIX)) {
                return null
            }
        }
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
        const         val RELATIVE_PATH = MediaStore.MediaColumns.RELATIVE_PATH
        const val DATA_PATH = MediaStore.MediaColumns.DATA

        /** 当前用户的共享存储前缀，如 /storage/emulated/0/ */
        val USER_STORAGE_PREFIX: String = "/storage/emulated/${Process.myUid() / 100000}/"

        /** 其它多用户的共享存储前缀，如 /storage/emulated/995/ */
        const val OTHER_USER_PREFIX = "/storage/emulated/"

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
            // Android 10+ 用 relative_path 还原路径；_data 仅在拿到「所有文件访问权限」时可读，
            // 用来识别指向其它多用户目录的幽灵记录
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(RELATIVE_PATH)
                add(DATA_PATH)
            } else {
                add(DATA_PATH)
            }
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
