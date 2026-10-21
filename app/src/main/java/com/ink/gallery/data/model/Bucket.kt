package com.ink.gallery.data.model

import android.net.Uri
import com.ink.gallery.util.StorageAccess

data class Bucket(
    val id: Long,
    val name: String,
    val coverUri: Uri,
    val count: Int,
    val latestModified: Long,
    /** 相册对应的相对目录（如 `DCIM/Camera/`），Android 10+ 才有；用于把照片移动到该相册 */
    val relativePath: String = ""
) {

    /**
     * 这个相册能否作为「移动照片」的目标。
     *
     * 有「所有文件访问权限」时任何目录都能进；没有时 Android 11+ 的 MediaStore
     * 只放行 `DCIM` / `Pictures` 顶层目录，存储根目录和自定义顶层目录（如 `111sync/`）
     * 都会被系统以 `Primary directory ... not allowed` 拒绝。
     */
    val canReceiveMovedPhoto: Boolean
        get() = StorageAccess.canReceiveImage(relativePath)
}
