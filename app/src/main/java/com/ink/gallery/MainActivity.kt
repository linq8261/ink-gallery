package com.ink.gallery

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.ink.gallery.data.remote.dto.VersionDto
import com.ink.gallery.data.repository.UpdateCheckResult
import com.ink.gallery.data.repository.UpdateRepository
import com.ink.gallery.navigation.AppNavGraph
import com.ink.gallery.ui.component.FullAccessDialog
import com.ink.gallery.ui.component.MediaPermissionGate
import com.ink.gallery.ui.component.UpdateDialog
import com.ink.gallery.ui.theme.InkGalleryTheme
import com.ink.gallery.util.openExternalUrl
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var updateRepository: UpdateRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须在 super.onCreate 之前调用：接管系统的启动窗口，
        // 否则 API 31+ 会回落到「白色 windowBackground + launcher icon」的默认样式。
        // 刻意不设置 keepOnScreenCondition —— 让它跟着首帧一起消失，不给冷启动额外加时间。
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent {
            InkGalleryTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        MediaPermissionGate {
                            AppNavGraph()
                        }
                        // 启动时检测「所有文件访问权限」，没有则引导用户去系统设置开启
                        FullAccessDialog()
                        // 启动时静默检查更新：只有确实有新版本才弹窗，失败不打扰
                        StartupUpdateCheck(updateRepository)
                    }
                }
            }
        }
    }

    internal companion object {
        /** 同一进程只检查一次，避免屏幕旋转等 Activity 重建时重复请求、重复弹窗 */
        var startupUpdateChecked = false
    }
}

@Composable
private fun StartupUpdateCheck(updateRepository: UpdateRepository) {
    val context = LocalContext.current
    var pendingUpdate by remember { mutableStateOf<VersionDto?>(null) }

    LaunchedEffect(Unit) {
        if (MainActivity.startupUpdateChecked) return@LaunchedEffect
        MainActivity.startupUpdateChecked = true

        val result = updateRepository.checkForUpdate()
        if (result is UpdateCheckResult.HasUpdate) {
            pendingUpdate = result.versionInfo
        }
    }

    pendingUpdate?.let { info ->
        UpdateDialog(
            versionInfo = info,
            currentVersionName = BuildConfig.VERSION_NAME,
            onDismiss = { pendingUpdate = null },
            onDownload = { url ->
                pendingUpdate = null
                if (!context.openExternalUrl(url)) {
                    Toast.makeText(context, "无法打开下载链接", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}
