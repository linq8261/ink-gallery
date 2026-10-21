package com.ink.gallery.feature.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    val currentPage: StateFlow<Int> = savedStateHandle.getStateFlow(KEY_CURRENT_PAGE, DEFAULT_PAGE)

    fun onPageChanged(page: Int) {
        savedStateHandle[KEY_CURRENT_PAGE] = page
    }

    private companion object {
        const val KEY_CURRENT_PAGE = "home_current_page"

        /** 0 = 相册页，1 = 相片页；默认进入相片页 */
        const val DEFAULT_PAGE = 1
    }
}
