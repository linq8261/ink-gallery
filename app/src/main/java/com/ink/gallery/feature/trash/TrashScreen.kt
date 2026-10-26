package com.ink.gallery.feature.trash

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ink.gallery.data.local.TrashEntity
import com.ink.gallery.data.model.MediaType
import com.ink.gallery.data.model.PhotoItem
import com.ink.gallery.ui.component.AppTopBar
import com.ink.gallery.ui.component.EmptyView
import com.ink.gallery.ui.component.PhotoGridSpec
import com.ink.gallery.ui.component.PhotoThumbnail
import com.ink.gallery.ui.component.SelectionAction
import com.ink.gallery.ui.component.SelectionBottomBar
import com.ink.gallery.ui.component.SelectionTopBar
import com.ink.gallery.ui.component.gridDragSelect
import com.ink.gallery.ui.component.rememberSelectionState
import com.ink.gallery.ui.theme.InkPaper
import com.ink.gallery.ui.theme.InkViewerBlack
import com.ink.gallery.util.formatFileSize
import java.io.File
import kotlin.math.ceil

private const val DAY_MS = 24L * 60 * 60 * 1000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    onBack: () -> Unit,
    viewModel: TrashViewModel = hiltViewModel()
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val totalSize by viewModel.totalSize.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val selection = rememberSelectionState<TrashEntity> { it.id }
    val selectionActive = selection.isActive
    val gridState = rememberLazyGridState()

    var selectedEntry by remember { mutableStateOf<TrashEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<List<TrashEntity>>(emptyList()) }
    var clearConfirmVisible by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
            // 批量操作结束就退出选择模式
            if (selection.isActive) selection.exit()
        }
    }

    BackHandler(enabled = selectionActive) { selection.exit() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (selectionActive) {
                SelectionTopBar(
                    count = selection.count,
                    allSelected = entries.isNotEmpty() && selection.count == entries.size,
                    onExit = { selection.exit() },
                    onToggleSelectAll = {
                        if (entries.isNotEmpty() && selection.count == entries.size) {
                            selection.clearSelection()
                        } else {
                            selection.selectAll(entries)
                        }
                    }
                )
            } else {
                AppTopBar(
                    title = "回收站",
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    },
                    actions = {
                        if (entries.isNotEmpty()) {
                            IconButton(onClick = { clearConfirmVisible = true }) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteSweep,
                                    contentDescription = "清空回收站"
                                )
                            }
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (selectionActive) {
                SelectionBottomBar(
                    actions = listOf(
                        SelectionAction(
                            icon = Icons.Outlined.Restore,
                            label = "恢复",
                            onClick = { viewModel.restoreAll(selection.selected) }
                        ),
                        SelectionAction(
                            icon = Icons.Outlined.DeleteForever,
                            label = "彻底删除",
                            onClick = { pendingDelete = selection.selected }
                        )
                    )
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Text(
                text = if (entries.isEmpty()) {
                    "删除的照片和视频会保留 30 天"
                } else {
                    "${entries.size} 项 · ${formatFileSize(totalSize)} · 30 天后自动删除"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (entries.isEmpty()) {
                EmptyView(text = "回收站是空的")
            } else {
                val dragModifier = if (entries.isNotEmpty()) {
                    Modifier.gridDragSelect(
                        gridState = gridState,
                        items = entries,
                        keyOf = { it.id },
                        selection = selection
                    )
                } else {
                    Modifier
                }

                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(PhotoGridSpec.COLUMNS),
                    contentPadding = PaddingValues(PhotoGridSpec.SPACING),
                    horizontalArrangement = Arrangement.spacedBy(PhotoGridSpec.SPACING),
                    verticalArrangement = Arrangement.spacedBy(PhotoGridSpec.SPACING),
                    modifier = Modifier
                        .fillMaxSize()
                        .then(dragModifier)
                ) {
                    items(items = entries, key = { it.id }) { entry ->
                        Box {
                            PhotoThumbnail(
                                photo = entry.toPhotoItem(),
                                onClick = {
                                    if (selectionActive) {
                                        selection.toggle(entry)
                                    } else {
                                        selectedEntry = entry
                                    }
                                },
                                selectionActive = selectionActive,
                                selected = selection.isSelected(entry.id)
                            )
                            Text(
                                text = "剩 ${remainingDays(entry)} 天",
                                color = InkPaper,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .background(
                                        color = InkViewerBlack.copy(alpha = 0.55f),
                                        shape = RoundedCornerShape(4.dp)
                                    )
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    selectedEntry?.let { entry ->
        ModalBottomSheet(onDismissRequest = { selectedEntry = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text(
                    text = entry.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "${formatFileSize(entry.size)} · 剩余 ${remainingDays(entry)} 天",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = {
                            selectedEntry = null
                            pendingDelete = listOf(entry)
                        }
                    ) {
                        Text("彻底删除")
                    }
                    TextButton(
                        onClick = {
                            selectedEntry = null
                            viewModel.restore(entry)
                        }
                    ) {
                        Text("恢复")
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }

    if (pendingDelete.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { pendingDelete = emptyList() },
            title = { Text("彻底删除？") },
            text = { Text("这 ${pendingDelete.size} 项将从回收站永久删除，无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val targets = pendingDelete
                        pendingDelete = emptyList()
                        viewModel.deleteForeverAll(targets)
                    }
                ) {
                    Text("彻底删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = emptyList() }) { Text("取消") }
            }
        )
    }

    if (clearConfirmVisible) {
        AlertDialog(
            onDismissRequest = { clearConfirmVisible = false },
            title = { Text("清空回收站？") },
            text = { Text("回收站内的 ${entries.size} 项将被永久删除，无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        clearConfirmVisible = false
                        viewModel.clearAll()
                    }
                ) {
                    Text("清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { clearConfirmVisible = false }) { Text("取消") }
            }
        )
    }
}

/** 剩余天数（向上取整），到期时显示 0 */
private fun remainingDays(entry: TrashEntity): Int {
    val left = entry.expiresAt - System.currentTimeMillis()
    return ceil(left / DAY_MS.toDouble()).toInt().coerceIn(0, 30)
}

private fun TrashEntity.toPhotoItem(): PhotoItem = PhotoItem(
    id = id,
    uri = Uri.fromFile(File(trashPath)),
    displayName = displayName,
    mimeType = mimeType,
    dateTaken = deletedAt,
    dateModified = deletedAt,
    size = size,
    width = width,
    height = height,
    bucketId = bucketId,
    bucketName = bucketName,
    path = trashPath,
    mediaType = if (isVideo) MediaType.VIDEO else MediaType.IMAGE,
    durationMs = durationMs
)
