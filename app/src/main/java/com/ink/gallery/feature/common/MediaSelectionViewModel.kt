package com.ink.gallery.feature.common

import android.content.IntentSender
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ink.gallery.data.model.Bucket
import com.ink.gallery.data.model.PhotoItem
import com.ink.gallery.data.repository.BatchMoveResult
import com.ink.gallery.data.repository.BatchTrashResult
import com.ink.gallery.data.repository.BucketRepository
import com.ink.gallery.data.repository.PhotoRepository
import com.ink.gallery.data.repository.TrashRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 相片页与相册详情页共用的多选批量操作：移入回收站、移动到相册。
 *
 * 批量移动一次只弹一次系统授权框：被拦下的条目由 [BatchMoveResult.NeedPermission] 带回，
 * 用户确认后继续处理剩余项，已成功的不会重复。
 */
@HiltViewModel
class MediaSelectionViewModel @Inject constructor(
    private val photoRepository: PhotoRepository,
    private val bucketRepository: BucketRepository,
    private val trashRepository: TrashRepository
) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _moveTargets = MutableStateFlow<List<Bucket>>(emptyList())
    val moveTargets: StateFlow<List<Bucket>> = _moveTargets.asStateFlow()

    private val _isTargetsLoading = MutableStateFlow(false)
    val isTargetsLoading: StateFlow<Boolean> = _isTargetsLoading.asStateFlow()

    private val _movePermission = MutableStateFlow<IntentSender?>(null)
    val movePermission: StateFlow<IntentSender?> = _movePermission.asStateFlow()

    private var pendingMove: PendingMove? = null

    private data class PendingMove(
        val photos: List<PhotoItem>,
        val targetPath: String,
        val targetName: String,
        val movedSoFar: Int = 0
    )

    /** 批量移入回收站（有回收站兜底，不需要二次确认） */
    fun delete(photos: List<PhotoItem>) {
        if (photos.isEmpty()) return
        viewModelScope.launch {
            when (val result = trashRepository.moveToTrash(photos)) {
                BatchTrashResult.NeedFullAccess ->
                    _message.value = "需要「所有文件访问权限」才能删除"

                is BatchTrashResult.Done -> _message.value = when {
                    result.success == 0 -> result.firstError ?: "移入回收站失败"
                    result.failed == 0 -> "已移入回收站：${result.success} 项"
                    else -> "已移入回收站 ${result.success} 项，${result.failed} 项失败：${result.firstError.orEmpty()}"
                }
            }
        }
    }

    fun loadMoveTargets() {
        if (_isTargetsLoading.value) return
        viewModelScope.launch {
            _isTargetsLoading.value = true
            _moveTargets.value = runCatching { bucketRepository.getBuckets() }.getOrDefault(emptyList())
            _isTargetsLoading.value = false
        }
    }

    fun move(photos: List<PhotoItem>, target: Bucket) {
        if (photos.isEmpty()) return
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
        pendingMove = PendingMove(photos, target.relativePath, target.name)
        attemptMove()
    }

    fun onMovePermissionResult(granted: Boolean) {
        _movePermission.value = null
        if (!granted) {
            pendingMove = null
            _message.value = "已取消移动"
            return
        }
        if (pendingMove == null) return
        attemptMove()
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun attemptMove() {
        val pending = pendingMove ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        viewModelScope.launch {
            when (val result = photoRepository.movePhotos(pending.photos, pending.targetPath)) {
                is BatchMoveResult.NeedPermission -> {
                    pendingMove = pending.copy(
                        photos = result.remaining,
                        movedSoFar = pending.movedSoFar + result.movedSoFar
                    )
                    _movePermission.value = result.intentSender
                }

                is BatchMoveResult.Done -> {
                    pendingMove = null
                    val total = pending.movedSoFar + result.success
                    _message.value = when {
                        total == 0 -> result.firstError ?: "移动失败"
                        result.failed == 0 -> "已移动到「${pending.targetName}」：$total 项"
                        else -> "已移动 $total 项，${result.failed} 项失败：${result.firstError.orEmpty()}"
                    }
                }
            }
        }
    }
}
