package com.ink.gallery.feature.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ink.gallery.data.local.TrashEntity
import com.ink.gallery.data.repository.TrashRepository
import com.ink.gallery.data.repository.TrashResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TrashViewModel @Inject constructor(
    private val repository: TrashRepository
) : ViewModel() {

    val entries: StateFlow<List<TrashEntity>> = repository.entries
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val count: StateFlow<Int> = repository.count
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val totalSize: StateFlow<Long> = repository.totalSize
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        // 进回收站时顺手清一次过期项（启动时也会清一次）
        viewModelScope.launch { runCatching { repository.purgeExpired() } }
    }

    fun restore(entry: TrashEntity) {
        viewModelScope.launch {
            when (val result = repository.restore(entry)) {
                TrashResult.Success -> _message.value = "已恢复到原相册"
                TrashResult.NeedFullAccess -> _message.value = "需要「所有文件访问权限」才能恢复"
                is TrashResult.Failed -> _message.value = result.message
            }
        }
    }

    fun deleteForever(entry: TrashEntity) {
        viewModelScope.launch {
            when (val result = repository.deleteForever(entry)) {
                TrashResult.Success -> _message.value = "已彻底删除"
                TrashResult.NeedFullAccess -> _message.value = "需要「所有文件访问权限」才能删除"
                is TrashResult.Failed -> _message.value = result.message
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}
