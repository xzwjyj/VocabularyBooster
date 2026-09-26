package com.vocabularybooster.data.videoimport

import kotlinx.serialization.json.Json

/**
 * 视频导入数据包加载器
 *
 * 从 assets/video_import/data.json 加载预置的单词数据
 */
public class VideoImportProvider(
    private val jsonContent: String,
) {
    private val json: Json = Json { ignoreUnknownKeys = true }

    private val parsed: VideoImportPackage by lazy {
        json.decodeFromString(VideoImportPackage.serializer(), jsonContent)
    }

    /** 加载视频导入数据包 */
    public fun load(): VideoImportPackage = parsed

    /** 获取数据包版本 */
    public fun getVersion(): Int = parsed.version

    /** 获取生词本名称 */
    public fun getWordBookName(): String = parsed.wordBookName

    /** 获取词条数量 */
    public fun getWordCount(): Int = parsed.entries.size
}
