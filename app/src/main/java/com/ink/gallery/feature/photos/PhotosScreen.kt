package com.ink.gallery.feature.photos

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import com.ink.gallery.data.model.PhotoItem
import com.ink.gallery.ui.component.PagedPhotoGrid

@Composable
fun PhotosScreen(
    onPhotoClick: (PhotoItem) -> Unit,
    viewModel: PhotosViewModel = hiltViewModel()
) {
    val photos = viewModel.photos.collectAsLazyPagingItems()

    PagedPhotoGrid(
        photos = photos,
        onPhotoClick = onPhotoClick,
        modifier = Modifier.fillMaxSize(),
        onRefresh = {
            viewModel.refresh()
            photos.refresh()
        }
    )
}
