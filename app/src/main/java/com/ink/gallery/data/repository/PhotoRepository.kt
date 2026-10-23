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
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import com.ink.gallery.data.local.PhotoSnapshotStore
import com.ink.gallery.data.model.MediaType
import com.ink.gallery.data.model.PhotoItem
import com.ink.gallery.util.PermissionUtil
import com.ink.gallery.util.StorageAccess
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 照片仓库。
 *
 * 不能在 ContentResolver.query 的 sortOrder 里拼接 `LIMIT/OFFSET`：新版 MediaProvider
 * 对 sortOrder 做严格 SQL 校验，会直接抛 `IllegalArgumentException: Invalid token LIMIT`。
 *
 * 这里改为「一次读取全量元数据 + 内存持有」：只投影轻量列、不触碰位图，
 * 万张级别的元数据也只有几 MB，界面直接拿整份列表交给 LazyGrid 渲染，
 * 滚动时没有分页查询、也没有分页源失效导致的重建闪烁。
 * 媒体库变化时由 ContentObserver 兜底失效缓存，并通过 [mediaChanges] 通知界面重读。
 */
@Singleton
class PhotoRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contentResolver: ContentResolver,
    private val snapshotStore: PhotoSnapshotStore
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cacheMutex = Mutex()

    @Volatile
    private var cachedPhotos: List<PhotoItem>? = null

    @Volatile
    private var cachedGroups: Map<Long, List<PhotoItem>>? = null

    /** 磁盘快照读取完成（无论有没有读到）后完成；[observePhotos] 的首帧等它 */
    private val snapshotLoaded = CompletableDeferred<Unit>()

    /**
     * 首次全量读取还没落地时收到过媒体库变化。
     * 此时不清缓存（正在跑的就是最新查询），改为在首次读取结束后补一次后台核对。
     */
    @Volatile
    private var pendingVerify = false

    private var invalidateJob: Job? = null
    private var snapshotJob: Job? = null

    /**
     * 本进程内被移动过的相册目录（绝对路径 → 时间戳）。
     *
     * 相册排序读的是目录 mtime，但 MediaProvider 改完数据库后真正落盘有延迟，
     * 而且新目录的 mtime 还会被 FUSE 属性缓存压住几秒，导致刚搬完照片的相册
     * 仍排在旧位置。这里记下自己搬动过的目录，排序时与 mtime 取较大值。
     */
    private val albumTouchedAt = ConcurrentHashMap<String, Long>()

    fun albumTouchedAt(absoluteDirectory: String): Long = albumTouchedAt[absoluteDirectory] ?: 0L

    /** 媒体库或照片归属变化时发出，供相册页等界面刷新自己的数据 */
    private val _mediaChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val mediaChanges: SharedFlow<Unit> = _mediaChanges.asSharedFlow()

    private val mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            scheduleInvalidate()
        }
    }

    init {
        contentResolver.registerContentObserver(IMAGE_COLLECTION, true, mediaObserver)
        contentResolver.registerContentObserver(VIDEO_COLLECTION, true, mediaObserver)
        // 冷启动先读磁盘快照：只为了让首帧立刻有列表可渲染，随后由后台全量核对覆盖。
        // 读快照期间不阻塞任何调用方——observePhotos 的 onStart 会 await 这个信号。
        scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            val snapshot = runCatching { snapshotStore.read() }.getOrNull()
            if (snapshot != null && snapshot.isNotEmpty() && cachedPhotos == null) {
                cachedGroups = snapshot.groupBy { it.bucketId }
                cachedPhotos = snapshot
                Log.i(
                    TAG,
                    "snapshot hit: ${snapshot.size} 条 / ${SystemClock.elapsedRealtime() - startedAt}ms" +
                        "（进程启动后 ${SystemClock.elapsedRealtime()}ms 就绪）"
                )
                // 快照只负责快、不负责准：立刻在后台跑一次全量查询核对
                scope.launch { verifyWithMediaStore() }
            } else {
                Log.i(
                    TAG,
                    "snapshot miss（读到 ${snapshot?.size ?: -1} 条）/ " +
                        "${SystemClock.elapsedRealtime() - startedAt}ms，将走全量扫描"
                )
            }
            snapshotLoaded.complete(Unit)
        }
    }

    /**
     * 进程启动阶段预热：让 Hilt 提前构造本仓库，
     * 把「读快照」这件事从「ViewModel 创建时」提到「Application.onCreate 时」。
     * 构造本身已在 init 中启动快照读取，这里只是让调用点显式、可读。
     */
    fun warmUp() = Unit

    /**
     * 照片列表的变化流：订阅时先给一份当前数据（命中内存或磁盘快照就是纯 IO 读，不打圈），
     * 之后媒体库变化、自己移动照片时再重读一次。
     *
     * 界面只需要 collect 这份列表，不做「整屏重建」——缓存被就地更新后，
     * 列表差异由 LazyGrid 自己 diff，不会出现加载圈。
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observePhotos(bucketId: Long? = null): Flow<List<PhotoItem>> =
        mediaChanges
            .onStart {
                // 等快照读完再发首帧：读一个 1MB 级顺序文件，比全量扫 MediaStore 快一个数量级
                snapshotLoaded.await()
                emit(Unit)
            }
            // 连续变化时只保留最新一次读取，避免旧结果后到覆盖新结果
            .mapLatest { loadPhotos(bucketId) }

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
                            markAlbumTouched(photo, directory)
                            applyMovedPhotoToCache(photo.id)
                            _mediaChanges.tryEmit(Unit)
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
     * 批量移动（多选用）。
     *
     * 逐条改 MediaStore 的 `relative_path`；被系统拦下的条目收集起来，
     * Android 11+ 一次性弹授权框（[BatchMoveResult.NeedPermission] 带回剩余项与已成功数），
     * 用户确认后由调用方用 remaining 重试即可，已成功的不会重复处理。
     *
     * 全部结束后只调一次 [refresh]，避免几十次列表重排。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun movePhotos(
        photos: List<PhotoItem>,
        targetRelativePath: String
    ): BatchMoveResult = withContext(Dispatchers.IO) {
        if (photos.isEmpty()) return@withContext BatchMoveResult.Done(0, 0, null)

        val directory = targetRelativePath.trim('/').let { if (it.isEmpty()) "/" else "$it/" }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.RELATIVE_PATH, directory)
        }

        var success = 0
        var failed = 0
        var firstError: String? = null
        val denied = ArrayList<PhotoItem>()

        photos.forEach { photo ->
            try {
                val updated = contentResolver.update(photo.uri, values, null, null)
                if (updated > 0) {
                    success++
                } else {
                    failed++
                    if (firstError == null) firstError = "移动失败：未找到该照片"
                }
            } catch (e: SecurityException) {
                denied += photo
            } catch (e: Exception) {
                failed++
                if (firstError == null) firstError = e.toMoveFailureMessage()
            }
        }

        when {
            denied.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                val request = MediaStore.createWriteRequest(contentResolver, denied.map { it.uri })
                BatchMoveResult.NeedPermission(request.intentSender, denied, success)
            }

            denied.isNotEmpty() -> {
                if (success > 0) refresh()
                BatchMoveResult.Done(
                    success = success,
                    failed = failed + denied.size,
                    firstError = firstError ?: "没有修改这些照片的权限"
                )
            }

            else -> {
                if (success > 0) refresh()
                BatchMoveResult.Done(success, failed, firstError)
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
        markAlbumTouched(source.parent, targetDir.absolutePath)
        invalidate()
        return MovePhotoResult.Moved
    }

    /** 记下这次移动涉及的源/目标目录，供相册排序使用 */
    private fun markAlbumTouched(photo: PhotoItem, targetDirectory: String) {
        val root = Environment.getExternalStorageDirectory()
        markAlbumTouched(File(photo.path).parent, File(root, targetDirectory.trim('/')).absolutePath)
    }

    private fun markAlbumTouched(vararg directories: String?) {
        val now = System.currentTimeMillis()
        directories.filterNotNull().forEach { path ->
            albumTouchedAt[File(path).absolutePath.trimEnd('/')] = now
        }
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
     *
     * 冷启动时若磁盘快照可用，[cachedPhotos] 在 init 阶段就已填好，
     * 这里直接返回、不产生任何查询。
     */
    private suspend fun loadPhotos(bucketId: Long? = null): List<PhotoItem> {
        val all = cachedPhotos ?: cacheMutex.withLock {
            cachedPhotos ?: run {
                val startedAt = SystemClock.elapsedRealtime()
                val photos = withContext(Dispatchers.IO) { queryPhotos() }
                Log.i(
                    TAG,
                    "全量扫描: ${photos.size} 条 / ${SystemClock.elapsedRealtime() - startedAt}ms" +
                        "（进程启动后 ${SystemClock.elapsedRealtime()}ms）"
                )
                cachedGroups = photos.groupBy { it.bucketId }
                cachedPhotos = photos
                persistSnapshot()
                // 首次查询期间媒体库有过变化：查询可能已错过那次变化，补一次后台核对
                if (pendingVerify) {
                    pendingVerify = false
                    scope.launch { verifyWithMediaStore() }
                }
                photos
            }
        }
        if (bucketId == null) return all
        return cachedGroups?.get(bucketId).orEmpty()
    }

    /**
     * 用一次全量查询核对当前缓存（通常就是刚读出来的磁盘快照）。
     *
     * 内容一致时**什么都不做**——不更新 Flow、不触发重组，界面连一帧抖动都不会有；
     * 不一致才替换缓存并通知界面重读。无论哪种情况都顺手把最新结果写回快照。
     */
    private suspend fun verifyWithMediaStore() {
        // 没有媒体权限时查询只会得到空列表，不能拿它当真相把快照清空
        if (!PermissionUtil.hasMediaPermission(context)) return
        val startedAt = SystemClock.elapsedRealtime()
        val fresh = runCatching { withContext(Dispatchers.IO) { queryPhotos() } }.getOrNull()
            ?: return
        val current = cachedPhotos
        val identical = current != null && sameContent(current, fresh)
        Log.i(
            TAG,
            "后台核对: ${fresh.size} 条 / ${SystemClock.elapsedRealtime() - startedAt}ms，" +
                if (identical) "与快照一致，不刷新界面" else "有差异，替换缓存"
        )
        if (identical) return
        cachedGroups = fresh.groupBy { it.bucketId }
        cachedPhotos = fresh
        persistSnapshot()
        _mediaChanges.tryEmit(Unit)
    }

    /**
     * 把当前缓存写回磁盘快照。防抖 500ms：批量移动/删除会连着改好几次缓存，
     * 没必要每次都落一遍 1MB 级的文件。
     */
    private fun persistSnapshot() {
        // 无权限时缓存只是个空壳，落盘只会污染下次启动
        if (!PermissionUtil.hasMediaPermission(context)) return
        val photos = cachedPhotos ?: return
        snapshotJob?.cancel()
        snapshotJob = scope.launch {
            delay(SNAPSHOT_WRITE_DEBOUNCE_MS)
            runCatching { snapshotStore.write(photos) }
                .onFailure { Log.w(TAG, "快照写入失败", it) }
        }
    }

    /**
     * 两份列表是否完全一致（data class 逐字段比较，含顺序）。
     *
     * 不挑字段省时间——漏掉任何一个字段都会变成「媒体库变了但界面不刷」的隐蔽 bug，
     * 而万条级别的比较也就十几毫秒，还发生在后台线程。
     */
    private fun sameContent(a: List<PhotoItem>, b: List<PhotoItem>): Boolean = a == b

    private fun queryPhotos(
        selection: String? = null,
        selectionArgs: Array<String> = emptyArray()
    ): List<PhotoItem> {
        val args = selectionArgs.takeIf { it.isNotEmpty() }
        val photos = ArrayList<PhotoItem>()
        photos += queryCollection(IMAGE_COLLECTION, IMAGE_PROJECTION, MediaType.IMAGE, selection, args)
        photos += queryCollection(VIDEO_COLLECTION, VIDEO_PROJECTION, MediaType.VIDEO, selection, args)
        // 两个集合各自有序，合并后必须整体重排
        return photos.sortedWith(PHOTO_ORDER)
    }

    private fun queryCollection(
        collection: Uri,
        projection: Array<String>,
        mediaType: MediaType,
        selection: String?,
        selectionArgs: Array<String>?
    ): List<PhotoItem> {
        val result = ArrayList<PhotoItem>()
        // 用户可能只授予了图片或只授予了视频权限（Android 13+ 两个权限分开），
        // 查未授权的集合会抛 SecurityException，这里降级成「该集合为空」
        runCatching {
            contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                SORT_ORDER
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    cursor.readPhoto(collection, mediaType)?.let { result += it }
                }
            }
        }
        return result
    }

    /**
     * 读取一行；返回 null 表示这条记录指向别的多用户（/storage/emulated/995 之类），
     * 当前用户既读不到文件，也会让同一个目录在相册页里裂成两个同名相册。
     */
    private fun Cursor.readPhoto(collection: Uri, mediaType: MediaType): PhotoItem? {
        val dataIndex = getColumnIndex(DATA_PATH)
        if (dataIndex >= 0) {
            val data = getString(dataIndex).orEmpty()
            if (data.startsWith(OTHER_USER_PREFIX) && !data.startsWith(USER_STORAGE_PREFIX)) {
                return null
            }
        }
        val id = getLong(getColumnIndexOrThrow(ID))
        val displayName = getString(getColumnIndexOrThrow(DISPLAY_NAME)).orEmpty()
        val durationIndex = getColumnIndex(DURATION)
        return PhotoItem(
            id = id,
            uri = ContentUris.withAppendedId(collection, id),
            displayName = displayName,
            mimeType = getString(getColumnIndexOrThrow(MIME_TYPE)).orEmpty(),
            dateTaken = getLong(getColumnIndexOrThrow(DATE_TAKEN)),
            dateModified = getLong(getColumnIndexOrThrow(DATE_MODIFIED)) * 1000L,
            size = getLong(getColumnIndexOrThrow(SIZE)),
            width = getInt(getColumnIndexOrThrow(WIDTH)),
            height = getInt(getColumnIndexOrThrow(HEIGHT)),
            bucketId = getLong(getColumnIndexOrThrow(BUCKET_ID)),
            bucketName = getString(getColumnIndexOrThrow(BUCKET_DISPLAY_NAME)).orEmpty(),
            path = readAbsolutePath(displayName),
            mediaType = mediaType,
            durationMs = if (durationIndex >= 0) getLong(durationIndex) else 0L
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
     * 手动刷新（下拉刷新）：丢弃内存缓存并立刻重查一次，
     * 函数返回时数据已就绪，界面据此收起下拉指示器即可，不会出现二次加载。
     */
    suspend fun refresh() {
        cachedPhotos = null
        cachedGroups = null
        withContext(Dispatchers.IO) { loadPhotos(null) }
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
        if (cachedPhotos == null) {
            // 首次读取还没落地：正在跑的那次查询读到的就是最新状态，
            // 此时清缓存只会让 mapLatest 取消重跑一遍，白白多扫一次全量。
            // 先记下这次变化，等首次读取结束后补一次后台核对。
            pendingVerify = true
            return
        }
        cachedPhotos = null
        cachedGroups = null
        _mediaChanges.tryEmit(Unit)
    }

    /** 删除（移入回收站）后把这些条目从内存列表里摘掉，界面立即少几格 */
    fun removeFromCache(photoIds: Collection<Long>) {
        if (photoIds.isEmpty()) return
        val current = cachedPhotos ?: return
        val ids = photoIds.toHashSet()
        val next = current.filterNot { it.id in ids }
        cachedPhotos = next
        cachedGroups = next.groupBy { it.bucketId }
        persistSnapshot()
        _mediaChanges.tryEmit(Unit)
    }

    fun removeFromCache(photoId: Long) = removeFromCache(listOf(photoId))

    /**
     * 自己移动成功后只把这一行在缓存里就地更新，不整表重查，
     * 相册详情页重新加载时就是内存切片，不会再出现整屏转圈。
     */
    private fun applyMovedPhotoToCache(photoId: Long) {
        val current = cachedPhotos ?: return
        val updated = queryPhotos(
            selection = "$ID = ?",
            selectionArgs = arrayOf(photoId.toString())
        ).firstOrNull() ?: return
        // MediaProvider 的 update 一般会刷新 date_modified；文件搬运 + 重扫的路径可能滞后，
        // 这里直接抬到当前时间，保证「刚移动的照片排最前」立刻生效
        val moved = updated.copy(
            dateModified = maxOf(updated.dateModified, System.currentTimeMillis())
        )
        val next = (current.filterNot { it.id == photoId } + moved).sortedWith(PHOTO_ORDER)
        cachedPhotos = next
        cachedGroups = next.groupBy { it.bucketId }
        persistSnapshot()
    }

    private companion object {
        /** 启动耗时诊断：`adb logcat -s InkStartup` */
        const val TAG = "InkStartup"

        const val INVALIDATE_DEBOUNCE_MS = 300L

        /** 缓存变更后写回磁盘快照的防抖，批量操作会连着改好几次缓存 */
        const val SNAPSHOT_WRITE_DEBOUNCE_MS = 500L

        val IMAGE_COLLECTION: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val VIDEO_COLLECTION: Uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

        // 列名全部取自 Images 常量：图片与视频的列名（_id / bucket_id / ...）完全一致，
        // 而 MediaStore.MediaColumns 的部分常量是 API 29 才加的，minSdk 24 下引用会触发 lint。
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
        const val DURATION = MediaStore.Video.VideoColumns.DURATION
        const         val RELATIVE_PATH = MediaStore.MediaColumns.RELATIVE_PATH
        const val DATA_PATH = MediaStore.MediaColumns.DATA

        /** 当前用户的共享存储前缀，如 /storage/emulated/0/ */
        val USER_STORAGE_PREFIX: String = "/storage/emulated/${Process.myUid() / 100000}/"

        /** 其它多用户的共享存储前缀，如 /storage/emulated/995/ */
        const val OTHER_USER_PREFIX = "/storage/emulated/"

        /** 图片与视频共有的列 */
        private fun MutableList<String>.addCommonColumns() {
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
        }

        val IMAGE_PROJECTION: Array<String> = buildList { addCommonColumns() }.toTypedArray()

        // DURATION 只在视频集合里查，图片集合投影它会被 MediaProvider 拒绝
        val VIDEO_PROJECTION: Array<String> = buildList {
            addCommonColumns()
            add(DURATION)
        }.toTypedArray()

        // 按修改时间排序：刚拍的照片 mtime≈拍摄时间，刚移动/重命名的照片 mtime 会刷新，
        // 于是「刚动过的排最前」。date_taken 只在拍摄时写一次，移动后不会变。
        const val SORT_ORDER = "$DATE_MODIFIED DESC, $ID DESC"

        /** 与 [SORT_ORDER] 一致的内存排序（从新到旧） */
        val PHOTO_ORDER: Comparator<PhotoItem> =
            compareByDescending<PhotoItem> { it.dateModified }.thenByDescending { it.id }
    }
}

sealed interface MovePhotoResult {
    data object Moved : MovePhotoResult
    data class NeedPermission(val intentSender: IntentSender) : MovePhotoResult
    data class Failed(val message: String) : MovePhotoResult
}

/** 批量移动结果 */
sealed interface BatchMoveResult {
    data class Done(
        val success: Int,
        val failed: Int,
        val firstError: String?
    ) : BatchMoveResult

    /**
     * 部分条目需要用户授权；[remaining] 是还没搬成功的那些，
     * [movedSoFar] 是本次之前已经搬成功的数量，重试后要累加。
     */
    data class NeedPermission(
        val intentSender: IntentSender,
        val remaining: List<PhotoItem>,
        val movedSoFar: Int
    ) : BatchMoveResult
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
