package com.ink.gallery.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * 「所有文件访问权限」（MANAGE_EXTERNAL_STORAGE）相关的判断与跳转。
 *
 * 只有拿到这个特殊权限，App 才能把照片移到 `DCIM` / `Pictures` 之外的目录
 * （存储根目录、`111sync/` 这类自定义顶层目录），否则 MediaProvider 会抛
 * `IllegalArgumentException: Primary directory ... not allowed`。
 */
object StorageAccess {

    /** Android 11 以下没有分区存储写限制，视为已具备全盘读写能力 */
    fun hasFullAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /** 图片能否移动到该相对目录（例如 `111sync/待办/`、`/`） */
    fun canReceiveImage(relativePath: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        if (Environment.isExternalStorageManager()) return true
        val topLevel = relativePath.trim('/').substringBefore('/')
        return topLevel == "DCIM" || topLevel == "Pictures"
    }

    /** 跳到系统「所有文件访问权限」页面；不同 ROM 支持程度不同，逐个兜底 */
    fun openSettings(context: Context) {
        val packageUri = Uri.fromParts("package", context.packageName, null)
        val candidates = listOf(
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).setData(packageUri),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(packageUri)
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
    }
}
