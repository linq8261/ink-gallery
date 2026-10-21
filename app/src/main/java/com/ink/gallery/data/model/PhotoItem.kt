package com.ink.gallery.data.model

import android.net.Uri

enum class MediaType { IMAGE, VIDEO }

data class PhotoItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val dateTaken: Long,
    val dateModified: Long,
    val size: Long,
    val width: Int,
    val height: Int,
    val bucketId: Long,
    val bucketName: String,
    /**
     * 尽力还原的绝对路径：API 29+ 由 relative_path + 文件名拼接，
     * API 28 及以下直接取 MediaStore 的 _data 列。无法还原时为空串。
     */
    val path: String = "",
    val mediaType: MediaType = MediaType.IMAGE,
    /** 视频时长（毫秒），图片恒为 0 */
    val durationMs: Long = 0L
) {
    val isVideo: Boolean get() = mediaType == MediaType.VIDEO
}
