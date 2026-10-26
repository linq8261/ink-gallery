package com.ink.gallery.feature.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ink.gallery.ui.component.ToolbarAction
import com.ink.gallery.ui.theme.InkPaper
import com.ink.gallery.ui.theme.InkViewerBlack

@Composable
fun PhotoViewerToolbar(
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 删除依赖「所有文件访问权限」。没有权限时图标置灰，
     * 点击仍会触发 [onDelete]，由界面弹引导框而不是静默无反应。
     */
    deleteEnabled: Boolean = true,
    /**
     * 「转为静态图」只对实况图有意义：非实况（以及探测还没回来）时菜单项置灰，
     * 让用户知道有这功能、只是这张图用不上。
     */
    exportStaticEnabled: Boolean = false,
    /** 导出中：菜单项文字变成进度提示并禁用，避免连点生成一堆副本 */
    exporting: Boolean = false,
    onExportStatic: () -> Unit = {}
) {
    var moreExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(InkViewerBlack.copy(alpha = 0.45f))
            .navigationBarsPadding()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 与多选底部操作栏共用同一个 ToolbarAction，两处按钮观感一致
        ToolbarAction(icon = Icons.Outlined.Edit, label = "编辑", onClick = onEdit, tint = InkPaper)
        ToolbarAction(icon = Icons.Outlined.Share, label = "分享", onClick = onShare, tint = InkPaper)
        ToolbarAction(icon = Icons.Outlined.FolderOpen, label = "移动", onClick = onMove, tint = InkPaper)
        ToolbarAction(
            icon = Icons.Outlined.Delete,
            label = "删除",
            onClick = onDelete,
            tint = InkPaper,
            enabled = deleteEnabled
        )
        Box {
            ToolbarAction(
                icon = Icons.Outlined.MoreHoriz,
                label = "更多",
                onClick = { moreExpanded = true },
                tint = InkPaper
            )
            DropdownMenu(
                expanded = moreExpanded,
                onDismissRequest = { moreExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(if (exporting) "正在导出…" else "转为静态图") },
                    onClick = {
                        moreExpanded = false
                        onExportStatic()
                    },
                    enabled = exportStaticEnabled && !exporting
                )
            }
        }
    }
}
