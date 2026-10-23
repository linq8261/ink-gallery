package com.ink.gallery.data.live

import android.util.Log
import com.ink.gallery.data.local.LivePhotoStore
import com.ink.gallery.data.model.PhotoItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 实况图（动态照片）探测与缓存。
 *
 * 刻意**不往 [PhotoItem] 里加字段**：照片列表是 `data class` 全字段比较、
 * 还整份落磁盘快照，一旦每张图多几个"稍后才有值"的字段，
 * 媒体库核对、快照失效、移动/删除的 copy 路径全都要跟着改。
 * 这里改用一条独立的 StateFlow，界面按 id 查表，探测结果随时增量刷新，
 * 照片列表本身的语义完全不动。
 *
 * 探测成本是「每张图读 128KB 文件头」，所以：
 * - 只排 `image/jpeg|heic|avif` 这类候选，视频和 PNG 直接跳过；
 * - 结果（包括「不是实况」的负结果）写进 [LivePhotoStore]，只探测一次；
 * - 后台 2 个 worker 串行消费，每张之间让出 10ms，避免首扫把 IO 打满。
 */
@Singleton
class LivePhotoRepository @Inject constructor(
    private val parser: MotionPhotoParser,
    private val store: LivePhotoStore
) {

    private val _livePhotos = MutableStateFlow<Map<Long, LiveInfo>>(emptyMap())

    /** 已确认的实况图：photoId → 内嵌视频位置 */
    val livePhotos: StateFlow<Map<Long, LiveInfo>> = _livePhotos.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 正结果（实况图） */
    private val detected = ConcurrentHashMap<Long, LiveInfo>()

    /** 负结果：探测过、确认不是实况，不再重复读文件 */
    private val notLive: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /** 已入队待探测的 id，避免列表每刷新一次就重复排队 */
    private val pending: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    private val queue = Channel<PhotoItem>(Channel.UNLIMITED)

    /** 缓存读完之后才允许入队／同步解析，否则会把已缓存的条目再探测一遍 */
    private val restored = CompletableDeferred<Unit>()

    private var persistJob: Job? = null

    init {
        scope.launch {
            restore()
            startWorkers()
        }
    }

    /**
     * 列表刷新时调用：把还没探测过的图片排进后台队列。
     * 已经探测过（无论结果）的直接跳过，所以这里可以随列表随便调用。
     */
    suspend fun prefetch(photos: List<PhotoItem>) {
        restored.await()
        var enqueued = 0
        photos.forEach { photo ->
            if (photo.isVideo) return@forEach
            if (!photo.mimeType.isMotionPhotoCandidate()) return@forEach
            val id = photo.id
            if (detected.containsKey(id) || id in notLive) return@forEach
            if (!pending.add(id)) return@forEach
            queue.trySend(photo)
            enqueued++
        }
        if (enqueued > 0) Log.d(TAG, "实况探测入队 $enqueued 张")
    }

    /**
     * 需要立刻知道结果时（进入详情页）走这条：单张解析，几十毫秒内返回。
     * 已经探测过的直接命中缓存，不碰文件。
     */
    suspend fun resolveNow(photo: PhotoItem): LiveInfo? {
        restored.await()
        detected[photo.id]?.let { return it }
        if (photo.id in notLive) return null
        if (photo.isVideo || !photo.mimeType.isMotionPhotoCandidate()) {
            notLive.add(photo.id)
            return null
        }
        pending.remove(photo.id)
        return parseAndRecord(photo)
    }

    private suspend fun parseAndRecord(photo: PhotoItem): LiveInfo? {
        val info = runCatching { parser.parse(photo) }.getOrNull()
        pending.remove(photo.id)
        if (info != null) {
            detected[photo.id] = info
            _livePhotos.value = detected.toMap()
        } else {
            notLive.add(photo.id)
        }
        schedulePersist()
        return info
    }

    private suspend fun restore() {
        val cached = runCatching { store.read() }.getOrNull()
        if (cached != null) {
            cached.forEach { (id, info) ->
                if (info != null) detected[id] = info else notLive.add(id)
            }
            if (detected.isNotEmpty()) _livePhotos.value = detected.toMap()
            Log.i(TAG, "实况缓存命中 ${cached.size} 条，其中实况 ${detected.size} 张")
        }
        restored.complete(Unit)
    }

    private fun startWorkers() {
        repeat(WORKER_COUNT) {
            scope.launch {
                for (photo in queue) {
                    runCatching { parseAndRecord(photo) }
                    // 让出 IO，避免一次性扫上千张图时把缩略图加载挤住
                    delay(WORKER_DELAY_MS)
                }
            }
        }
    }

    /** 批量探测会把结果连着改很多次，落盘做防抖 */
    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            val entries = HashMap<Long, LiveInfo?>(detected.size + notLive.size)
            detected.forEach { (id, info) -> entries[id] = info }
            notLive.forEach { entries[it] = null }
            store.write(entries)
        }
    }

    private companion object {
        const val TAG = "InkLive"

        /** 同时探测的并发数：再多也快不了，反而抢缩略图的 IO */
        const val WORKER_COUNT = 2

        const val WORKER_DELAY_MS = 10L

        const val PERSIST_DEBOUNCE_MS = 3_000L
    }
}
