package com.ink.gallery.feature.bucketdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.ink.gallery.data.model.PhotoItem
import com.ink.gallery.data.repository.BucketRepository
import com.ink.gallery.data.repository.PhotoRepository
import com.ink.gallery.navigation.NavRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BucketDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val bucketRepository: BucketRepository,
    private val repository: PhotoRepository
) : ViewModel() {

    val bucketId: Long = savedStateHandle.get<Long>(NavRoutes.ARG_BUCKET_ID) ?: NO_BUCKET_ID

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    val photos: Flow<PagingData<PhotoItem>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = PAGE_SIZE * 2,
            prefetchDistance = PAGE_SIZE,
            enablePlaceholders = false
        ),
        // bucket_id 是路径哈希，可能为负数，只能用哨兵值判断
        pagingSourceFactory = { repository.pagingSource(bucketId.takeIf { it != NO_BUCKET_ID }) }
    ).flow.cachedIn(viewModelScope)

    init {
        viewModelScope.launch {
            _title.value = runCatching { bucketRepository.getBucketName(bucketId) }.getOrNull().orEmpty()
        }
    }

    /** 下拉刷新：丢弃元数据缓存，由界面再触发 Paging 重新加载 */
    fun refresh() = repository.refresh()

    private companion object {
        const val PAGE_SIZE = 60
        const val NO_BUCKET_ID = -1L
    }
}
