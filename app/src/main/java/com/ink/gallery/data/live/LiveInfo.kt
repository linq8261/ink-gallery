package com.ink.gallery.data.live

/**
 * 一张实况图（动态照片）里那段视频的位置。
 *
 * 动态照片 = 一张完整的静态图片，尾部直接拼接一个完整的 MP4，
 * 两者靠文件里的 XMP 元数据标出视频字节数。因此只要知道
 * [lengthBytes]，视频起点就是 `文件大小 - lengthBytes`，不需要解析 MP4 结构。
 *
 * 只存数值不存 Uri／路径：万张级别的列表会持有这些对象，字符串会让内存翻倍。
 */
data class LiveInfo(
    /** 视频在文件内的起始字节偏移 */
    val offsetBytes: Long,
    /** 视频字节数（也等于从文件末尾回退的距离） */
    val lengthBytes: Long,
    /** 片段时长（毫秒）；解析不到时为 0 */
    val durationMs: Long = 0L,
    /** 与静态图对应的视频帧时间戳（微秒），XMP 未标注时为 -1 */
    val presentationTimestampUs: Long = -1L
)
