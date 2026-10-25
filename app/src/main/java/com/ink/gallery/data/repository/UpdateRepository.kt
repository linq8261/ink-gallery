package com.ink.gallery.data.repository

import com.ink.gallery.BuildConfig
import com.ink.gallery.data.remote.api.AppVersionApi
import com.ink.gallery.data.remote.dto.VersionDto
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

sealed class UpdateCheckResult {
    /** 当前已是最新（含服务端无该应用版本记录的情况） */
    data object NoUpdate : UpdateCheckResult()

    data class HasUpdate(val versionInfo: VersionDto) : UpdateCheckResult()

    data class Error(val message: String) : UpdateCheckResult()
}

/**
 * 检查更新：只比对 versionCode，不下载安装包（跳转浏览器打开 Release 页）。
 */
@Singleton
class UpdateRepository @Inject constructor(
    private val api: AppVersionApi
) {

    suspend fun checkForUpdate(): UpdateCheckResult {
        return try {
            val response = api.getLatestVersion(BuildConfig.APPLICATION_ID)

            when {
                response.isSuccess && response.data != null -> {
                    val latest = response.data
                    if (latest.versionCode > BuildConfig.VERSION_CODE) {
                        UpdateCheckResult.HasUpdate(latest)
                    } else {
                        UpdateCheckResult.NoUpdate
                    }
                }

                response.isSuccess -> UpdateCheckResult.NoUpdate

                else -> UpdateCheckResult.Error(
                    response.message.takeIf { it.isNotBlank() } ?: "检查更新失败"
                )
            }
        } catch (e: HttpException) {
            // 服务端无该应用的版本记录（HTTP 404 / 业务码 1006）视为已是最新
            if (e.code() == 404) UpdateCheckResult.NoUpdate
            else UpdateCheckResult.Error("检查更新失败（HTTP ${e.code()}）")
        } catch (e: Exception) {
            UpdateCheckResult.Error(e.message ?: "检查更新失败")
        }
    }
}
