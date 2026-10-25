package com.ink.gallery.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ink.gallery.data.remote.dto.VersionDto

/**
 * 版本更新对话框：启动时静默检查、关于页手动检查共用。
 *
 * 强制更新（isForceUpdate）时不允许点遮罩关闭，也不显示「稍后再说」。
 */
@Composable
fun UpdateDialog(
    versionInfo: VersionDto,
    currentVersionName: String,
    onDismiss: () -> Unit,
    onDownload: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = {
            if (!versionInfo.isForceUpdate) onDismiss()
        },
        title = {
            Text(text = versionInfo.updateTitle?.takeIf { it.isNotBlank() } ?: "发现新版本")
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "最新版本：${versionInfo.versionName}（${versionInfo.versionCode}）",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "当前版本：$currentVersionName",
                    style = MaterialTheme.typography.bodyMedium
                )
                versionInfo.fileSize?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = "安装包大小：$it",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "更新内容",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = versionInfo.updateContent?.takeIf { it.isNotBlank() }
                        ?: "修复了一些问题，提升使用体验",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = { onDownload(versionInfo.resolveDownloadUrl().orEmpty()) }) {
                Text("去更新")
            }
        },
        dismissButton = if (versionInfo.isForceUpdate) {
            null
        } else {
            {
                TextButton(onClick = onDismiss) {
                    Text("稍后再说")
                }
            }
        }
    )
}
