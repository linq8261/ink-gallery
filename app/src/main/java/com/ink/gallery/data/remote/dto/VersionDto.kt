package com.ink.gallery.data.remote.dto

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import com.ink.gallery.BuildConfig

/**
 * 服务端下发的最新版本信息。
 *
 * - [url]：后台手工填写的外链（本项目填的是 Gitee Release 页地址），可能为空串
 * - [fileUrl]：服务端上传安装包时返回的相对路径，如 `/api/v1/versions/12/file`，未上传文件时为 null
 */
@Keep
data class VersionDto(
    @SerializedName("id")
    val id: Int = 0,
    @SerializedName("packageName")
    val packageName: String = "",
    @SerializedName("versionCode")
    val versionCode: Int = 0,
    @SerializedName("versionName")
    val versionName: String = "",
    @SerializedName("updateTitle")
    val updateTitle: String? = null,
    @SerializedName("updateContent")
    val updateContent: String? = null,
    @SerializedName("fileSize")
    val fileSize: String? = null,
    @SerializedName("fileUrl")
    val fileUrl: String? = null,
    @SerializedName("url")
    val url: String? = null,
    @SerializedName("isForceUpdate")
    val isForceUpdate: Boolean = false
) {
    /**
     * 拼出可直接打开的下载地址：外链优先，否则用 fileUrl 拼当前服务器 base。
     */
    fun resolveDownloadUrl(): String? {
        url?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val path = fileUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (path.startsWith("http://", ignoreCase = true) ||
            path.startsWith("https://", ignoreCase = true)
        ) {
            return path
        }
        return BuildConfig.SERVER_BASE_URL.trimEnd('/') + "/" + path.trimStart('/')
    }
}
