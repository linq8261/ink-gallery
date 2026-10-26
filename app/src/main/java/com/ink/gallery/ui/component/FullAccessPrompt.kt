package com.ink.gallery.ui.component

import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ink.gallery.util.StorageAccess

private const val PREFS_NAME = "ink_gallery_prefs"
private const val KEY_FULL_ACCESS_PROMPTED = "full_access_prompted"

private fun wasPrompted(context: Context): Boolean =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(KEY_FULL_ACCESS_PROMPTED, false)

private fun markPrompted(context: Context) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_FULL_ACCESS_PROMPTED, true)
        .apply()
}

/**
 * 跟随生命周期刷新的「所有文件访问权限」状态：
 * 用户从系统设置页返回后，本状态会在 ON_RESUME 时重新读取。
 */
@Composable
fun rememberHasFullAccess(): Boolean {
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasAccess by remember { mutableStateOf(StorageAccess.hasFullAccess()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAccess = StorageAccess.hasFullAccess()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return hasAccess
}

/**
 * 启动引导：没有「所有文件访问权限」时弹一次说明框（装好后只弹一次），
 * 用户可以选择去开启，或先跳过——跳过后由 [FullAccessBanner] 常驻提示。
 */
@Composable
fun FullAccessDialog() {
    val context = LocalContext.current
    val hasAccess = rememberHasFullAccess()
    var prompted by remember { mutableStateOf(wasPrompted(context)) }

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || hasAccess || prompted) return

    val dismiss = {
        prompted = true
        markPrompted(context)
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("需要「所有文件访问权限」") },
        text = {
            Text(
                "开启后可以把照片移动到任意相册（例如 111sync 下的分类、存储根目录）。" +
                    "不开启也能正常浏览照片，只是移动目标仅限于 DCIM / Pictures 下的相册。"
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    dismiss()
                    StorageAccess.openSettings(context)
                }
            ) {
                Text("去开启")
            }
        },
        dismissButton = {
            TextButton(onClick = dismiss) { Text("暂不") }
        }
    )
}

/**
 * 常驻提示条：未开启权限时显示在相册页顶部，可本次会话内关闭。
 */
@Composable
fun FullAccessBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val hasAccess = rememberHasFullAccess()
    var dismissed by remember { mutableStateOf(false) }

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || hasAccess || dismissed) return

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "未开启「所有文件访问权限」，移动目标受限",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { StorageAccess.openSettings(context) }) {
                Text("去开启")
            }
            IconButton(onClick = { dismissed = true }) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "关闭提示",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // 一笔墨线收边，避免提示条和宣纸底糊在一起
        InkBrushDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
