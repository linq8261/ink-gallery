package com.ink.gallery.feature.photos

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ink.gallery.data.model.PhotoItem
import com.ink.gallery.ui.component.PhotoGrid
import com.ink.gallery.ui.component.SelectionState

@Composable
fun PhotosScreen(
    onPhotoClick: (PhotoItem) -> Unit,
    selection: SelectionState<PhotoItem>,
    viewModel: PhotosViewModel = hiltViewModel()
) {
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    val liveIds by viewModel.liveIds.collectAsStateWithLifecycle()

    PhotoGrid(
        photos = photos,
        onPhotoClick = onPhotoClick,
        onRefresh = { viewModel.refresh() },
        modifier = Modifier.fillMaxSize(),
        selection = selection,
        liveIds = liveIds
    )
}
