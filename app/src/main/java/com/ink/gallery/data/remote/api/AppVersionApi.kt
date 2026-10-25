package com.ink.gallery.data.remote.api

import com.ink.gallery.data.remote.dto.ApiResponse
import com.ink.gallery.data.remote.dto.VersionDto
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * 应用版本接口（与 ink-player / ink-movie-book 共用 my-server）。
 */
interface AppVersionApi {

    /**
     * 查询最新版本。
     *
     * 公开接口：GET /api/v1/apps/{packageName}/versions/latest
     * 服务端没有该包名的版本记录时返回 HTTP 404 + 业务码 1006。
     */
    @GET("api/v1/apps/{packageName}/versions/latest")
    suspend fun getLatestVersion(@Path("packageName") packageName: String): ApiResponse<VersionDto>
}
