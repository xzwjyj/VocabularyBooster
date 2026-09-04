package com.vocabularybooster.learning

import com.vocabularybooster.domain.model.LearningSession
import com.vocabularybooster.domain.model.SessionWord
import com.vocabularybooster.domain.model.SessionWordStatus
import com.vocabularybooster.domain.repository.RepositoryValidationException
import com.vocabularybooster.domain.repository.WordBookRepository
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * 派生生词本（LEARNING_ENGINE_SPEC §8 分支 B，DOMAIN_MODEL §10；Phase 3 Step 5D）。
 * 职责 = 退出三分支中 A/B 的裁决与派生编排：
 * - **分支 A**（零掌握）：会话无任何 MASTERED 词 → 返回 null——不派生、不建空本；
 * - **分支 B**（部分掌握，MASTERED > 0 且 REMAINING > 0）：命名 → 委托仓储单事务派生，
 *   返回新本 ID；**复制交集为空（effectiveRemaining = 0，Case 3 裁决 2026-09-04）→
 *   仓储返回 null，不建空 DERIVED 本**（会话仍 ABANDONED，endedAt 已由终态事务写入）。
 *   REMAINING（Q3）的裁决权在引擎侧 [CompletionDetector]，本类不重复实现。
 *
 * 命名规则（DOMAIN_MODEL §6.3 / LE spec §8 步骤 1）：`"{母本名称} yyyy-MM-dd HH:mm"`
 * （24h 制、本地时区、注入 Clock）；精确重名追加 `-2`、`-3`…——命名器为纯 Kotlin。
 *
 * 引擎只在 exitSession 的 ABANDONED 路径（终态写入完成后）调用本类；
 * 会话终态裁决 / 终态幂等 / endedAt 属 Step 5C 语义，本类不触碰。
 */
public class WordBookDeriver(
    private val wordBookRepository: WordBookRepository,
    private val clock: Clock,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) {

    /**
     * 按会话退出快照派生 DERIVED 本（LE spec §8 步骤 1–7）。
     * 会话零 MASTERED（分支 A）→ **null（不建空本）**；分支 B → 新本 ID，
     * 但复制交集为空（Case 3：当前母本词条 ∩ SessionWord 非 MASTERED = ∅）→ null（不建空本）。
     * 母本不存在 → [RepositoryValidationException]（书删悬挂态，调用方为已终态会话——
     * 该会话只能保持 ABANDONED，学习历史仍完整保留）。
     */
    public suspend fun derive(session: LearningSession, sessionWords: List<SessionWord>): Long? {
        if (sessionWords.none { it.status == SessionWordStatus.MASTERED }) {
            return null // 分支 A（LE spec §8 表行 A）：零掌握不派生不建空本
        }
        val motherName = wordBookRepository.getWordBookName(session.wordBookId)
            ?: throw RepositoryValidationException("母本不存在：wordBookId=${session.wordBookId}")
        val now = clock.now()
        val base = formatDerivedName(motherName, now)
        var name = base
        var suffix = 2
        while (wordBookRepository.countBooksWithName(name) > 0) {
            name = "$base-$suffix" // 重名追加 -2、-3…（§8 步骤 1）
            suffix++
        }
        return wordBookRepository.deriveWordBook(
            parentWordBookId = session.wordBookId,
            sourceSessionId = session.sessionId,
            name = name,
            createdAt = now,
        )
    }

    /** `"{母本名称} yyyy-MM-dd HH:mm"`（24h 制，[timeZone] 本地时区；纯函数，供直测）。 */
    @Suppress("MagicNumber") // 日期格式化：padStart 参数是视觉对齐，非业务常量
    internal fun formatDerivedName(motherName: String, at: Instant): String {
        val local = at.toLocalDateTime(timeZone)
        fun pad(value: Int): String = value.toString().padStart(2, '0')
        val date = "${local.year.toString().padStart(4, '0')}-${pad(local.monthNumber)}-${pad(local.dayOfMonth)}"
        val time = "${pad(local.hour)}:${pad(local.minute)}"
        return "$motherName $date $time"
    }
}
