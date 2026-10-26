package com.ink.gallery.feature.home

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ink.gallery.data.model.PhotoItem
import com.ink.gallery.feature.buckets.BucketsScreen
import com.ink.gallery.feature.common.MediaSelectionViewModel
import com.ink.gallery.feature.photos.PhotosScreen
import com.ink.gallery.feature.photos.PhotosViewModel
import com.ink.gallery.feature.viewer.MoveToBucketSheet
import com.ink.gallery.ui.component.AppTopBar
import com.ink.gallery.ui.component.FullAccessBanner
import com.ink.gallery.ui.component.SelectionAction
import com.ink.gallery.ui.component.SelectionBottomBar
import com.ink.gallery.ui.component.SelectionTopBar
import com.ink.gallery.ui.component.rememberHasFullAccess
import com.ink.gallery.ui.component.rememberSelectionState
import com.ink.gallery.ui.theme.InkHairline
import com.ink.gallery.ui.theme.InkInk
import com.ink.gallery.util.StorageAccess

private const val PAGE_COUNT = 2
private const val PAGE_BUCKETS = 0
private const val PAGE_PHOTOS = 1

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenBucket: (Long) -> Unit,
    onOpenPhoto: (photoId: Long, bucketId: Long?) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
    photosViewModel: PhotosViewModel = hiltViewModel(),
    selectionViewModel: MediaSelectionViewModel = hiltViewModel()
) {
    val currentPage by viewModel.currentPage.collectAsStateWithLifecycle()
    val photos by photosViewModel.photos.collectAsStateWithLifecycle()
    val selectionMessage by selectionViewModel.message.collectAsStateWithLifecycle()
    val moveTargets by selectionViewModel.moveTargets.collectAsStateWithLifecycle()
    val isTargetsLoading by selectionViewModel.isTargetsLoading.collectAsStateWithLifecycle()
    val movePermission by selectionViewModel.movePermission.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val hasFullAccess = rememberHasFullAccess()
    // 与 PhotosScreen 共用同一个选择状态，顶栏由这里统一管理
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

    // 批量操作结束后统一提示并退出选择模式
    LaunchedEffect(selectionMessage) {
        selectionMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            selectionViewModel.consumeMessage()
            selection.exit()
        }
    }

    BackHandler(enabled = selectionActive) { selection.exit() }

    val pagerState = rememberPagerState(initialPage = currentPage, pageCount = { PAGE_COUNT })

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            viewModel.onPageChanged(page)
        }
    }

    val allPhotos = photos.orEmpty()
    val showSelectionBars = currentPage == PAGE_PHOTOS && selectionActive

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (showSelectionBars) {
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
                    title = if (currentPage == PAGE_BUCKETS) "相册页" else "相片页",
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                imageVector = Icons.Outlined.Settings,
                                contentDescription = "设置"
                            )
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (showSelectionBars) {
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (!selectionActive) {
                    PageIndicator(pagerState = pagerState, pageCount = PAGE_COUNT)
                    FullAccessBanner()
                }

                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = !selectionActive,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    when (page) {
                        PAGE_BUCKETS -> BucketsScreen(onBucketClick = onOpenBucket)
                        else -> PhotosScreen(
                            onPhotoClick = { photo -> onOpenPhoto(photo.id, null) },
                            selection = selection,
                            viewModel = photosViewModel
                        )
                    }
                }
            }

            if (moveSheetVisible) {
                MoveToBucketSheet(
                    buckets = moveTargets,
                    isLoading = isTargetsLoading,
                    currentBucketId = null,
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

@Composable
private fun PageIndicator(
    pagerState: PagerState,
    pageCount: Int
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(InkHairline)
    ) {
        val segmentWidth = maxWidth / pageCount
        val position = (pagerState.currentPage + pagerState.currentPageOffsetFraction)
            .coerceIn(0f, (pageCount - 1).toFloat())

        Box(
            modifier = Modifier
                .width(segmentWidth)
                .fillMaxHeight()
                .offset(x = segmentWidth * position)
                // 当前页用整块浓墨，和极淡墨线底形成明确的墨色差
                .background(InkInk)
        )
    }
}
