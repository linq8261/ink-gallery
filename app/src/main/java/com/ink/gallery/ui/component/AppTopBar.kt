package com.ink.gallery.ui.component

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
 * 全应用统一的页面标题栏：暗紫底色，标题使用浅紫白。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        modifier = modifier,
        navigationIcon = navigationIcon,
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = InkTitleBar,
            titleContentColor = InkTitleBarContent,
            navigationIconContentColor = InkTitleBarContent,
            actionIconContentColor = InkTitleBarContent
        )
    )
}
