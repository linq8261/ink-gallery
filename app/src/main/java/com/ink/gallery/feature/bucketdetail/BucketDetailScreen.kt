package com.ink.gallery.feature.bucketdetail

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ink.gallery.data.model.PhotoItem
import com.ink.gallery.feature.common.MediaSelectionViewModel
import com.ink.gallery.feature.viewer.MoveToBucketSheet
import com.ink.gallery.ui.component.AppTopBar
import com.ink.gallery.ui.component.PhotoGrid
import com.ink.gallery.ui.component.SelectionAction
import com.ink.gallery.ui.component.SelectionBottomBar
import com.ink.gallery.ui.component.SelectionTopBar
import com.ink.gallery.ui.component.rememberHasFullAccess
import com.ink.gallery.ui.component.rememberSelectionState
import com.ink.gallery.util.StorageAccess

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BucketDetailScreen(
    onBack: () -> Unit,
    onPhotoClick: (photoId: Long) -> Unit,
    viewModel: BucketDetailViewModel = hiltViewModel(),
    selectionViewModel: MediaSelectionViewModel = hiltViewModel()
) {
    val title by viewModel.title.collectAsStateWithLifecycle()
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    val selectionMessage by selectionViewModel.message.collectAsStateWithLifecycle()
    val moveTargets by selectionViewModel.moveTargets.collectAsStateWithLifecycle()
    val isTargetsLoading by selectionViewModel.isTargetsLoading.collectAsStateWithLifecycle()
    val movePermission by selectionViewModel.movePermission.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val hasFullAccess = rememberHasFullAccess()
    val selection = rememberSelectionState<PhotoItem> { it.id }
    val selectionActive = selection.isActive

    var moveSheetVisible by remember { mutableStateOf(false) }
    var fullAccessGuideVisible by remember { mutableStateOf(false) }

    val moveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        selectionViewModel.onMovePermissionResult(result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(movePermission) {
        movePermission?.let { sender ->
            moveLauncher.launch(IntentSenderRequest.Builder(sender).build())
        }
    }

    LaunchedEffect(selectionMessage) {
        selectionMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            selectionViewModel.consumeMessage()
            selection.exit()
        }
    }

    BackHandler(enabled = selectionActive) { selection.exit() }

    val allPhotos = photos.orEmpty()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (selectionActive) {
                SelectionTopBar(
                    count = selection.count,
                    allSelected = allPhotos.isNotEmpty() && selection.count == allPhotos.size,
                    onExit = { selection.exit() },
                    onToggleSelectAll = {
                        if (allPhotos.isNotEmpty() && selection.count == allPhotos.size) {
                            selection.clearSelection()
                        } else {
                            selection.selectAll(allPhotos)
                        }
                    }
                )
            } else {
                AppTopBar(
                    title = title.ifEmpty { "相册" },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
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
                            icon = Icons.Outlined.FolderOpen,
                            label = "移动",
                            onClick = {
                                selectionViewModel.loadMoveTargets()
                                moveSheetVisible = true
                            }
                        ),
                        SelectionAction(
                            icon = Icons.Outlined.Delete,
                            label = "删除",
                            enabled = hasFullAccess,
                            onClick = {
                                if (hasFullAccess) {
                                    selectionViewModel.delete(selection.selected)
                                } else {
                                    fullAccessGuideVisible = true
                                }
                            }
                        )
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            PhotoGrid(
                photos = photos,
                onPhotoClick = { photo -> onPhotoClick(photo.id) },
                onRefresh = { viewModel.refresh() },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                selection = selection
            )

            if (moveSheetVisible) {
                MoveToBucketSheet(
                    buckets = moveTargets,
                    isLoading = isTargetsLoading,
                    currentBucketId = viewModel.bucketId,
                    onDismiss = { moveSheetVisible = false },
                    onSelect = { bucket ->
                        moveSheetVisible = false
                        selectionViewModel.move(selection.selected, bucket)
                    }
                )
            }
        }
    }

    if (fullAccessGuideVisible) {
        AlertDialog(
            onDismissRequest = { fullAccessGuideVisible = false },
            title = { Text("需要「所有文件访问权限」") },
            text = {
                Text(
                    "删除会把照片/视频移入回收站，需要该权限才能搬动文件。" +
                        "请先到系统设置里为本应用开启。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        fullAccessGuideVisible = false
                        StorageAccess.openSettings(context)
                    }
                ) {
                    Text("去开启")
                }
            },
            dismissButton = {
                TextButton(onClick = { fullAccessGuideVisible = false }) { Text("取消") }
            }
        )
    }
}
