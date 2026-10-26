package com.ink.gallery.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.ink.gallery.ui.theme.InkTitleBar
import com.ink.gallery.ui.theme.InkTitleBarContent

/**
 * 全应用统一的页面标题栏：宣纸底 + 墨字，底边压一笔浓淡变化的墨线，
 * 像宣纸上题完字落的那一横。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {}
) {
    Column(modifier = modifier.background(InkTitleBar)) {
        TopAppBar(
            title = {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            navigationIcon = navigationIcon,
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = InkTitleBar,
                titleContentColor = InkTitleBarContent,
                navigationIconContentColor = InkTitleBarContent,
                actionIconContentColor = InkTitleBarContent
            )
        )
        // 起笔轻、收笔淡的一笔墨线，比均匀实线更有书写感
        InkBrushDivider()
    }
}
