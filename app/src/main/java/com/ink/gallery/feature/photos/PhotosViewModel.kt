package com.ink.gallery.feature.photos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.ink.gallery.data.model.PhotoItem
import com.ink.gallery.data.repository.PhotoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

@HiltViewModel
class PhotosViewModel @Inject constructor(
    private val repository: PhotoRepository
) : ViewModel() {

    val photos: Flow<PagingData<PhotoItem>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = PAGE_SIZE * 2,
            prefetchDistance = PAGE_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { repository.pagingSource() }
    ).flow.cachedIn(viewModelScope)

    /** 下拉刷新：丢弃元数据缓存，由界面再触发 Paging 重新加载 */
    fun refresh() = repository.refresh()

    private companion object {
        const val PAGE_SIZE = 60
    }
}
