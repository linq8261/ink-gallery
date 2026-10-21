package com.ink.gallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.ink.gallery.navigation.AppNavGraph
import com.ink.gallery.ui.component.FullAccessDialog
import com.ink.gallery.ui.component.MediaPermissionGate
import com.ink.gallery.ui.theme.InkGalleryTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
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
                    }
                }
            }
        }
    }
}
