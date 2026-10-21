package com.ink.gallery.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.ink.gallery.data.model.PhotoItem

/**
 * 基于内存快照的分页源：每次 load 取一份照片列表（仓库层已缓存），
 * 在列表上切片，不做任何 SQL 查询，因此翻到第几页都一样快。
 */
class PhotoPagingSource(
    private val loadPhotos: suspend () -> List<PhotoItem>
) : PagingSource<Int, PhotoItem>() {

    override fun getRefreshKey(state: PagingState<Int, PhotoItem>): Int? {
        val anchor = state.anchorPosition ?: return null
        return (anchor - state.config.initialLoadSize / 2).coerceAtLeast(0)
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, PhotoItem> = try {
        val photos = loadPhotos()
        val offset = (params.key ?: 0).coerceIn(0, photos.size)
        val end = minOf(offset + params.loadSize, photos.size)
        LoadResult.Page(
            data = photos.subList(offset, end).toList(),
            prevKey = if (offset == 0) null else maxOf(0, offset - params.loadSize),
            nextKey = if (end >= photos.size) null else end
        )
    } catch (e: Exception) {
        LoadResult.Error(e)
    }
}
