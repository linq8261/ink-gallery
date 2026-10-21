package com.ink.gallery.data.model

import android.net.Uri
import android.os.Build

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
     * Android 11+ 的 MediaStore 只允许把图片写进 `DCIM` / `Pictures` 顶层目录之下，
     * 存储根目录（[relativePath] 为 `/`）和自定义顶层目录（如 `111sync/`）都会被
     * 系统以 `IllegalArgumentException: Primary directory ... not allowed` 拒绝。
     */
    val canReceiveMovedPhoto: Boolean
        get() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
            val topLevel = relativePath.trim('/').substringBefore('/')
            return topLevel == "DCIM" || topLevel == "Pictures"
        }
}
