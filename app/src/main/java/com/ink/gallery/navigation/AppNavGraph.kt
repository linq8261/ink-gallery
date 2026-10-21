package com.ink.gallery.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ink.gallery.feature.bucketdetail.BucketDetailScreen
import com.ink.gallery.feature.home.HomeScreen
import com.ink.gallery.feature.settings.AboutScreen
import com.ink.gallery.feature.settings.SettingsScreen
import com.ink.gallery.feature.trash.TrashScreen
import com.ink.gallery.feature.viewer.PhotoViewerScreen

@Composable
fun AppNavGraph(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = NavRoutes.HOME,
        // navigation-compose 2.7 给每个目的地默认加了 700ms 淡入淡出，
        // 相册里频繁进详情/返回会显得拖沓，这里全部关掉
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None }
    ) {
        composable(NavRoutes.HOME) {
            HomeScreen(
                onOpenSettings = { navController.navigate(NavRoutes.SETTINGS) },
                onOpenBucket = { bucketId ->
                    navController.navigate(NavRoutes.bucketDetail(bucketId))
                },
                onOpenPhoto = { photoId, bucketId ->
                    navController.navigate(NavRoutes.viewer(photoId, bucketId))
                }
            )
        }

        composable(NavRoutes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenAbout = { navController.navigate(NavRoutes.ABOUT) },
                onOpenTrash = { navController.navigate(NavRoutes.TRASH) }
            )
        }

        composable(NavRoutes.TRASH) {
            TrashScreen(onBack = { navController.popBackStack() })
        }

        composable(NavRoutes.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = NavRoutes.BUCKET_DETAIL,
            arguments = listOf(
                navArgument(NavRoutes.ARG_BUCKET_ID) { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val bucketId = backStackEntry.arguments?.getLong(NavRoutes.ARG_BUCKET_ID) ?: -1L
            BucketDetailScreen(
                onBack = { navController.popBackStack() },
                onPhotoClick = { photoId ->
                    navController.navigate(NavRoutes.viewer(photoId, bucketId))
                }
            )
        }

        composable(
            route = NavRoutes.VIEWER,
            arguments = listOf(
                navArgument(NavRoutes.ARG_PHOTO_ID) { type = NavType.LongType },
                navArgument(NavRoutes.ARG_BUCKET_ID) {
                    type = NavType.LongType
                    defaultValue = -1L
                }
            )
        ) {
            PhotoViewerScreen(onBack = { navController.popBackStack() })
        }
    }
}
