package com.ink.gallery.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ink.gallery.data.remote.dto.VersionDto
import com.ink.gallery.data.repository.UpdateCheckResult
import com.ink.gallery.data.repository.UpdateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AboutUiState(
    val isChecking: Boolean = false,
    val updateInfo: VersionDto? = null
)

@HiltViewModel
class AboutViewModel @Inject constructor(
    private val updateRepository: UpdateRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AboutUiState())
    val uiState: StateFlow<AboutUiState> = _uiState.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun checkForUpdate() {
        if (_uiState.value.isChecking) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isChecking = true)

            when (val result = updateRepository.checkForUpdate()) {
                is UpdateCheckResult.HasUpdate ->
                    _uiState.value = _uiState.value.copy(updateInfo = result.versionInfo)

                UpdateCheckResult.NoUpdate ->
                    _message.value = "当前已是最新版本"

                is UpdateCheckResult.Error ->
                    _message.value = result.message
            }

            _uiState.value = _uiState.value.copy(isChecking = false)
        }
    }

    fun dismissUpdate() {
        _uiState.value = _uiState.value.copy(updateInfo = null)
    }

    fun consumeMessage() {
        _message.value = null
    }
}
