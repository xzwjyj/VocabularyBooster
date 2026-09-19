package com.vocabularybooster.platform

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.vocabularybooster.domain.dictionary.DictionaryDefinitionEntry
import com.vocabularybooster.domain.dictionary.DictionaryProvider
import com.vocabularybooster.domain.dictionary.DictionaryWord
import com.vocabularybooster.domain.model.toNormalizedWordText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
 * 映射表镜像规范序。例句恒空（例句契约仅精选种子，PROJECT_SPEC FR-3 注记）。
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
            "SELECT text, ipa, definitions FROM DictEntry WHERE normalized = ? LIMIT 1",
            arrayOf(normalized),
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2))
        } ?: return@withContext null
        val senses = runCatching { json.decodeFromString<List<List<String>>>(row.third) }
            .getOrDefault(emptyList())
        val orderCounter = mutableMapOf<String, Int>() // 每词局部：definitionOrder 同词性组内从 0 起
        DictionaryWord(
            text = row.first,
            ipaAm = row.second.ifBlank { null },
            definitions = senses.map { sense -> sense.toDefinitionEntry(orderCounter) },
        )
    }

    /** definitionOrder = 同词性组内顺序（每词局部计数，senses 已按词性序预排）。 */
    private fun List<String>.toDefinitionEntry(
        orderCounter: MutableMap<String, Int>,
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
        )
    }

    private fun openDatabase(): SQLiteDatabase {
        database?.let { return it }
        synchronized(this) {
            database?.let { return it }
            val dir = File(appContext.filesDir, DICT_DIR)
            val file = File(dir, DICT_FILE)
            if (!file.exists() || file.length() == 0L) {
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

    private companion object {
        const val DICT_DIR = "dict"
        const val DICT_FILE = "ecdict.sqlite"

        /** DOMAIN_MODEL §3.1 规范序镜像（provider 分配职责归本类）。 */
        val POS_ORDER = mapOf(
            "verb" to 0, "noun" to 1, "adjective" to 2, "adverb" to 3,
            "pronoun" to 4, "preposition" to 5, "conjunction" to 6,
            "interjection" to 7, "determiner" to 8, "numeral" to 9,
            "phrase" to 10, "other" to 90,
        )
    }
}
