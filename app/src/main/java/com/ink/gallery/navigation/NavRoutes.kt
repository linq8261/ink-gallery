package com.ink.gallery.navigation

object NavRoutes {

    const val ARG_BUCKET_ID = "bucketId"
    const val ARG_PHOTO_ID = "photoId"

    const val HOME = "home"
    const val SETTINGS = "settings"
    const val ABOUT = "about"

    const val BUCKET_DETAIL = "bucket/{$ARG_BUCKET_ID}"
    const val VIEWER = "viewer/{$ARG_PHOTO_ID}?$ARG_BUCKET_ID={$ARG_BUCKET_ID}"

    fun bucketDetail(bucketId: Long): String = "bucket/$bucketId"

    fun viewer(photoId: Long, bucketId: Long? = null): String =
        "viewer/$photoId?$ARG_BUCKET_ID=${bucketId ?: -1L}"
}
