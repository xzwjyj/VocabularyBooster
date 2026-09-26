package com.vocabularybooster.data.videoimport

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 视频导入数据包格式 (JSON)
 *
 * 与 tools/video_importer/exporter.py 生成的格式一致
 */
@Serializable
public data class VideoImportPackage(
    val version: Int,
    val sourceVideo: String,
    val wordBookName: String,
    val exportedAt: String,
    val entries: List<VideoImportEntry>,
)

@Serializable
public data class VideoImportEntry(
    val word: String,
    val meaningEN: String,
    val meaningCN: String = "",
    val partOfSpeech: String = "noun",
    val examples: List<VideoImportExample> = emptyList(),
)

@Serializable
public data class VideoImportExample(
    val sentence: String,
    val chineseTranslation: String = "",
    val audioFile: String? = null,
)

/**
 * 视频导入报告
 */
public data class VideoImportReport(
    val wordBookName: String,
    val totalWords: Int,
    val importedWords: Int,
    val reusedWords: Int,
    val totalExamples: Int,
    val importedExamples: Int,
)
