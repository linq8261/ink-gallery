package com.ink.gallery.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 用浏览器打开外部链接（检查更新的下载 / Release 页）。
 *
 * @return 是否成功唤起
 */
fun Context.openExternalUrl(url: String): Boolean {
    if (url.isBlank()) return false

    return runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        // Application / Service 这类非 Activity 上下文启动 Activity 必须带这个 flag
        if (this !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        true
    }.getOrDefault(false)
}
