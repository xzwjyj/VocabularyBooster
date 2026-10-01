package com.vocabularybooster.platform

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.vocabularybooster.domain.dictionary.DictionaryDefinitionEntry
import com.vocabularybooster.domain.dictionary.DictionaryExample
import com.vocabularybooster.domain.dictionary.DictionaryProvider
import com.vocabularybooster.domain.dictionary.DictionaryWord
import com.vocabularybooster.domain.model.toNormalizedWordText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream

/**
 * 随包全量词典源（FR-18，Phase 8.6）：assets/dict/ecdict.sqlite（ECDICT 1.0.28 子集，
 * ~83.6 万词/短语，MIT；再生见 tools/dict/README.md）。
 * 首次使用 assets → filesDir/dict 一次性复制（~90MB），此后只读打开；
 * 失败（资产缺席/损坏）永久降级返回 null——词典是查词增强，不阻断任何路径。
 *
 * definitions 列 = 紧凑 JSON 数组 [["partOfSpeech","meaningEN","meaningCN"],…]
 * （tools/dict/convert.py 产出，已按 partOfSpeechOrder 排序）；
 * partOfSpeechOrder/definitionOrder 由本 provider 分配（DOMAIN_MODEL §3.1 职责），
 * 映射表镜像规范序。
 * examples 列 = 紧凑 JSON 数组 [["en","zh",defIdx(,"g")], …]（v6 起逐释义归属：
 * defIdx = definitions 数组下标；"g" = 离线工具 LLM 生成句 → AI_GENERATED；
 * 其余为 Tatoeba CC-BY 2.0 FR，译文人译优先、NLLB/Qwen 机翻兜底）。
 * 资产 PRAGMA user_version（v1 = 无例句列，v2 = 例句对格式，v3 = 例句数据修订：粗口过滤 + zh 优先重选，
 * v4 = 全量机翻，v5 = ipa-dict 双音标，v6 = 逐释义归属 + 全量重译 + 生成兜底）。
 * 版本号 = 资产修订号（不只格式）：数据重建也必须递增，否则设备缓存（同 user_version 的旧数据）不会重拷。
 */
public class BundledDictionaryProvider(
    context: Context,
) : DictionaryProvider {

    private val appContext: Context = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var database: SQLiteDatabase? = null

    /** 资产确认缺席（未打包/CI 环境）后置位——后续查询直接 null，不再重复探测。 */
    @Volatile
    private var unavailable = false

    override suspend fun lookup(text: String): DictionaryWord? = withContext(Dispatchers.IO) {
        if (unavailable) return@withContext null
        val db = runCatching { openDatabase() }.getOrElse {
            unavailable = true
            return@withContext null
        }
        val normalized = text.toNormalizedWordText()
        val row = db.rawQuery(
            "SELECT text, ipa, definitions, examples, ipaBr FROM DictEntry WHERE normalized = ? LIMIT 1",
            arrayOf(normalized),
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                RowData(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getString(4))
            }
        } ?: return@withContext null
        val senses = runCatching { json.decodeFromString<List<List<String>>>(row.definitions) }
            .getOrDefault(emptyList())
        // v6 例句逐释义归属：[en, zh, defIdx(, "g")]——defIdx = definitions 数组下标
        // （build 期三级归属确定）；"g" = 离线工具 LLM 生成句（如实标注 AI_GENERATED，FR-3）。
        // defIdx 是数字基元——按 JsonElement 宽松取值，严格 List<List<String>> 解码会整列失败
        val exampleRows = runCatching { json.decodeFromString<List<List<JsonElement>>>(row.examples) }
            .getOrDefault(emptyList())
        val examplesBySense = mutableMapOf<Int, MutableList<DictionaryExample>>()
        for (row0 in exampleRows) {
            val en = row0.getOrNull(0)?.jsonPrimitive?.content ?: continue
            val zh = row0.getOrNull(1)?.jsonPrimitive?.content ?: continue
            val defIdx = row0.getOrNull(2)?.jsonPrimitive?.content?.toIntOrNull()
                ?.coerceIn(0, (senses.size - 1).coerceAtLeast(0)) ?: 0
            val generated = row0.getOrNull(3)?.jsonPrimitive?.content == "g"
            val list = examplesBySense.getOrPut(defIdx) { mutableListOf() }
            list += DictionaryExample(
                sentence = en,
                chineseTranslation = zh,
                sourceType = if (generated) "AI_GENERATED" else "TATOEBA",
                licenseNote = if (generated) {
                    "AI generated (Qwen, offline tooling)"
                } else {
                    "CC-BY 2.0 FR"
                },
                exampleOrder = list.size,
            )
        }
        val orderCounter = mutableMapOf<String, Int>() // 每词局部：definitionOrder 同词性组内从 0 起
        DictionaryWord(
            text = row.text,
            ipaAm = row.ipa.ifBlank { null },
            ipaBr = row.ipaBr?.takeIf { it.isNotBlank() },
            definitions = senses.mapIndexed { senseIndex, sense ->
                sense.toDefinitionEntry(
                    orderCounter = orderCounter,
                    examples = examplesBySense[senseIndex].orEmpty(),
                )
            },
        )
    }

    private data class RowData(
        val text: String,
        val ipa: String,
        val definitions: String,
        val examples: String,
        val ipaBr: String?,
    )

    /** definitionOrder = 同词性组内顺序（每词局部计数，senses 已按词性序预排）。 */
    private fun List<String>.toDefinitionEntry(
        orderCounter: MutableMap<String, Int>,
        examples: List<DictionaryExample>,
    ): DictionaryDefinitionEntry {
        val pos = getOrElse(0) { "other" }
        val next = orderCounter.getOrDefault(pos, 0)
        orderCounter[pos] = next + 1
        return DictionaryDefinitionEntry(
            partOfSpeech = pos,
            partOfSpeechOrder = POS_ORDER[pos] ?: 90,
            definitionOrder = next,
            meaningEN = getOrElse(1) { "" },
            meaningCN = getOrElse(2) { "" },
            examples = examples,
        )
    }

    private fun openDatabase(): SQLiteDatabase {
        database?.let { return it }
        synchronized(this) {
            database?.let { return it }
            val dir = File(appContext.filesDir, DICT_DIR)
            val file = File(dir, DICT_FILE)
            if (!file.exists() || file.length() == 0L || !isCurrentAssetVersion(file)) {
                // 首次复制，或设备缓存为旧版资产（user_version < DICT_ASSET_VERSION）→ 重新拷贝
                dir.mkdirs()
                val tmp = File(dir, "$DICT_FILE.tmp")
                appContext.assets.open("$DICT_DIR/$DICT_FILE").use { input ->
                    FileOutputStream(tmp).use { output -> input.copyTo(output) }
                }
                check(tmp.renameTo(file)) { "词典复制落盘失败：${file.absolutePath}" }
            }
            return SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                .also { database = it }
        }
    }

    /** 资产演进校验：PRAGMA user_version < DICT_ASSET_VERSION → 判定过期需重拷（版本含数据修订）。 */
    private fun isCurrentAssetVersion(file: File): Boolean = runCatching {
        // SQLiteDatabase API 27 前不实现 Closeable——手动 close（不写 .use）
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            db.rawQuery("PRAGMA user_version", null).use { cursor ->
                cursor.moveToFirst() && cursor.getInt(0) >= DICT_ASSET_VERSION
            }
        } finally {
            db.close()
        }
    }.getOrDefault(false)

    private companion object {
        const val DICT_DIR = "dict"
        const val DICT_FILE = "ecdict.sqlite"

        /** 词典资产版本（tools/dict 管线写入 user_version）：v2 = 例句对格式，v3 = 例句数据修订（粗口过滤 + zh 优先重选），v4 = 全部例句中文翻译（opus-mt-en-zh），v5 = ipa-dict 英音音标 + 美音补缺，v6 = 例句逐释义归属 + 全量重译 + 生成兜底。数据重建也必须递增本号。 */
        const val DICT_ASSET_VERSION = 6

        /** DOMAIN_MODEL §3.1 规范序镜像（provider 分配职责归本类）。 */
        val POS_ORDER = mapOf(
            "verb" to 0, "noun" to 1, "adjective" to 2, "adverb" to 3,
            "pronoun" to 4, "preposition" to 5, "conjunction" to 6,
            "interjection" to 7, "determiner" to 8, "numeral" to 9,
            "phrase" to 10, "other" to 90,
        )
    }
}
