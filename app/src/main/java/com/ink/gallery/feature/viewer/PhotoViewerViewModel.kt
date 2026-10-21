package com.ink.gallery.feature.viewer

import android.content.IntentSender
import android.os.Build
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ink.gallery.data.model.Bucket
import com.ink.gallery.data.model.PhotoItem
import com.ink.gallery.data.repository.BucketRepository
import com.ink.gallery.data.repository.MovePhotoResult
import com.ink.gallery.data.repository.PhotoRepository
import com.ink.gallery.navigation.NavRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PhotoViewerUiState(
    val photos: List<PhotoItem> = emptyList(),
    val startIndex: Int = 0,
    val isLoading: Boolean = true,
    val buckets: List<Bucket> = emptyList(),
    val isBucketsLoading: Boolean = false
)

@HiltViewModel
class PhotoViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: PhotoRepository,
    private val bucketRepository: BucketRepository
) : ViewModel() {

    private val photoId: Long = savedStateHandle.get<Long>(NavRoutes.ARG_PHOTO_ID) ?: -1L

    // bucket_id 是路径哈希，可能为负数，只能用哨兵值 -1 判断「未指定相册」
    private val bucketId: Long? = savedStateHandle
        .get<Long>(NavRoutes.ARG_BUCKET_ID)
        ?.takeIf { it != NO_BUCKET_ID }

    private val _uiState = MutableStateFlow(PhotoViewerUiState())
    val uiState: StateFlow<PhotoViewerUiState> = _uiState.asStateFlow()

    /** 移动照片需要用户授权时，交给界面启动的 intent */
    private val _movePermission = MutableStateFlow<IntentSender?>(null)
    val movePermission: StateFlow<IntentSender?> = _movePermission.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var pendingMove: Pair<PhotoItem, Bucket>? = null

    init {
        loadInitial()
    }

    /**
     * 一次性取整个相册（或全部照片）的列表，顶部计数就是「相册内序号 / 相册总数」，
     * 与网格顺序（date_modified DESC, _id DESC）一致。列表来自仓库内存缓存，
     * 页面本身由 Pager 懒加载，不预加载位图。
     */
    private fun loadInitial() {
        viewModelScope.launch {
            val photos = runCatching { repository.getPhotos(bucketId) }.getOrDefault(emptyList())
            if (photos.isEmpty()) {
                _uiState.update { it.copy(isLoading = false) }
                return@launch
            }
            val index = photos.indexOfFirst { it.id == photoId }
            _uiState.value = PhotoViewerUiState(
                photos = photos,
                startIndex = if (index >= 0) index else 0,
                isLoading = false
            )
        }
    }

    /** 打开移动面板时加载相册列表（相册仓库按最近修改倒序返回） */
    fun loadMoveTargets() {
        if (_uiState.value.isBucketsLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBucketsLoading = true) }
            val buckets = runCatching { bucketRepository.getBuckets() }.getOrDefault(emptyList())
            _uiState.update { it.copy(buckets = buckets, isBucketsLoading = false) }
        }
    }

    fun movePhoto(photo: PhotoItem, target: Bucket) {
        if (target.relativePath.isBlank()) {
            _message.value = "无法获取该相册的目录"
            return
        }
        if (!target.canReceiveMovedPhoto) {
            _message.value = "系统不允许移动到该相册：图片只能移到 DCIM 或 Pictures 下的目录"
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            _message.value = "当前系统版本不支持移动照片"
            return
        }
        pendingMove = photo to target
        attemptMove()
    }

    fun onMovePermissionResult(granted: Boolean) {
        _movePermission.value = null
        if (!granted) {
            pendingMove = null
            _message.value = "已取消移动"
            return
        }
        attemptMove()
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun attemptMove() {
        val pending = pendingMove ?: return
        val photo = pending.first
        val target = pending.second
        viewModelScope.launch {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                when (val result = repository.movePhoto(photo, target.relativePath)) {
                    MovePhotoResult.Moved -> {
                        pendingMove = null
                        // 照片已不属于当前浏览集合，直接从列表中移除
                        _uiState.update { state ->
                            val remaining = state.photos.filterNot { it.id == photo.id }
                            state.copy(
                                photos = remaining,
                                startIndex = state.startIndex
                                    .coerceIn(0, (remaining.size - 1).coerceAtLeast(0))
                            )
                        }
                        _message.value = "已移动到「${target.name}」"
                    }

                    is MovePhotoResult.NeedPermission -> _movePermission.value = result.intentSender

                    is MovePhotoResult.Failed -> {
                        pendingMove = null
                        _message.value = result.message
                    }
                }
            } else {
                pendingMove = null
                _message.value = "当前系统版本不支持移动照片"
            }
        }
    }

    private companion object {
        const val NO_BUCKET_ID = -1L
    }
}
