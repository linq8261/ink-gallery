package com.ink.gallery.navigation

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
import com.ink.gallery.feature.viewer.PhotoViewerScreen

@Composable
fun AppNavGraph(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = NavRoutes.HOME
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
                onOpenAbout = { navController.navigate(NavRoutes.ABOUT) }
            )
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
