package com.ink.gallery.feature.bucketdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ink.gallery.data.live.LivePhotoRepository
import com.ink.gallery.data.model.PhotoItem
import com.ink.gallery.data.repository.BucketRepository
import com.ink.gallery.data.repository.PhotoRepository
import com.ink.gallery.navigation.NavRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BucketDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val bucketRepository: BucketRepository,
    private val repository: PhotoRepository,
    private val livePhotoRepository: LivePhotoRepository
) : ViewModel() {

    val bucketId: Long = savedStateHandle.get<Long>(NavRoutes.ARG_BUCKET_ID) ?: NO_BUCKET_ID

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    /**
     * 该相册的全部照片/视频（从新到旧），null 表示首次读取还没完成。
     * bucket_id 是路径哈希，可能为负数，只能用哨兵值判断「未指定相册」。
     */
    val photos: StateFlow<List<PhotoItem>?> = repository
        .observePhotos(bucketId.takeIf { it != NO_BUCKET_ID })
        .map<List<PhotoItem>, List<PhotoItem>?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 实况图 id 集合，用于网格角标 */
    val liveIds: StateFlow<Set<Long>> = livePhotoRepository.livePhotos
        .map { it.keys.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    init {
        viewModelScope.launch {
            _title.value = runCatching { bucketRepository.getBucketName(bucketId) }.getOrNull().orEmpty()
        }
        viewModelScope.launch {
            photos.filterNotNull().collect { livePhotoRepository.prefetch(it) }
        }
    }

    /** 下拉刷新：丢弃缓存并重查，返回时数据已就绪 */
    suspend fun refresh() = repository.refresh()

    private companion object {
        const val NO_BUCKET_ID = -1L
    }
}
