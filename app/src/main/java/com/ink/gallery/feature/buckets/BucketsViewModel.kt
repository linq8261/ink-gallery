package com.ink.gallery.feature.buckets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ink.gallery.data.model.Bucket
import com.ink.gallery.data.repository.BucketRepository
import com.ink.gallery.data.repository.PhotoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BucketsUiState(
    val buckets: List<Bucket> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class BucketsViewModel @Inject constructor(
    private val repository: BucketRepository,
    photoRepository: PhotoRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(BucketsUiState())
    val uiState: StateFlow<BucketsUiState> = _uiState.asStateFlow()

    private var refreshJob: Job? = null

    init {
        refresh()
        // 照片被移动/删除/新增后自动刷新相册列表与数量
        viewModelScope.launch {
            photoRepository.mediaChanges.collect { refresh() }
        }
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val buckets = runCatching { repository.getBuckets() }.getOrDefault(emptyList())
            _uiState.update { it.copy(buckets = buckets, isLoading = false) }
        }
    }
}
