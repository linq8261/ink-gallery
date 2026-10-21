package com.ink.gallery.feature.photos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ink.gallery.data.model.PhotoItem
import com.ink.gallery.data.repository.PhotoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class PhotosViewModel @Inject constructor(
    private val repository: PhotoRepository
) : ViewModel() {

    /**
     * 全部照片/视频（从新到旧），null 表示首次读取还没完成。
     *
     * 用 Eagerly：页面被详情页盖住时 ViewModel 仍然存活，仓库发来变更就后台更新，
     * 返回相册时数据已经是最新的，不需要再刷一次、也不会闪加载圈。
     */
    val photos: StateFlow<List<PhotoItem>?> = repository.observePhotos()
        .map<List<PhotoItem>, List<PhotoItem>?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 下拉刷新：丢弃缓存并重查，返回时数据已就绪 */
    suspend fun refresh() = repository.refresh()
}
