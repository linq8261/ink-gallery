package com.ink.gallery.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 回收站条目。文件本体被 rename 到回收站目录（内部存储走 App 私有目录、
 * 外置卡走卡根隐藏目录），这里只记元数据；30 天后由启动时的清理任务连同文件一起删除。
 */
@Entity(tableName = "trash")
data class TrashEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 原 content:// uri，便于排查问题 */
    val originalUri: String,
    /** 原绝对路径，恢复时按它搬回去 */
    val originalPath: String,
    /** 回收站内的绝对路径 */
    val trashPath: String,
    val displayName: String,
    val mimeType: String,
    val size: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val isVideo: Boolean,
    val bucketId: Long,
    val bucketName: String,
    val deletedAt: Long,
    /** 到期时间，过了就由 [TrashRepository.purgeExpired] 彻底删除 */
    val expiresAt: Long
)
