package com.vocabularybooster.learning

import com.vocabularybooster.domain.model.SessionStatus
import com.vocabularybooster.domain.repository.LearningSessionRepository
import com.vocabularybooster.domain.repository.RepositoryValidationException
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * "会了"落库（LEARNING_ENGINE_SPEC §6，FR-7 / NFR-8）。
 * 语音与按钮走同一入口、完全等价（source 仅作来源标识，v1 不落库、不改变行为）；
 * 时间取注入 [Clock]（铁律 10：不取系统时间）；事务原子性由仓储端口
 * [LearningSessionRepository.markSessionWordMastered] 承载（data 层单事务）。
 */

/** 掌握来源（LE spec §6/§11 入口签名；NFR-8 等价语义）。 */
public enum class MasterySource { VOICE, BUTTON }

/** 掌握标记结果（LE spec §6：0 行受影响 → 幂等返回 AlreadyMastered——不是错误）。 */
public sealed interface MasteryResult {

    /** 首次掌握成功：PENDING/PLAYING → MASTERED + WordMastery 落行（单事务）。 */
    public data class Marked(val masteredAt: Instant) : MasteryResult

    /** 幂等：该词已 MASTERED；masteredAt 保持首次时刻，无新语义事件（TC-LE-04）。 */
    public data object AlreadyMastered : MasteryResult

    /** 拒绝（非异常的业务否决）。 */
    public data class Rejected(val reason: Reason) : MasteryResult

    public enum class Reason {
        /** 会话非 ACTIVE（COMPLETED/ABANDONED 无出边，DOMAIN_MODEL §8.2，不再接受掌握标记）。 */
        SESSION_NOT_ACTIVE,
    }
}

public class MasteryMarker(
    private val repository: LearningSessionRepository,
    private val clock: Clock,
) {

    /**
     * 标记掌握（引擎唯一入口，语音 = 按钮）：
     * - 会话不存在 → [RepositoryValidationException]（沿用 domain 既有契约）；
     * - 会话非 ACTIVE → [MasteryResult.Rejected] SESSION_NOT_ACTIVE；
     * - 词不属于会话 → [RepositoryValidationException]（仓储事务内裁决）；
     * - 已 MASTERED → [MasteryResult.AlreadyMastered]（masteredAt 不刷新）；
     * - PENDING/PLAYING → [MasteryResult.Marked]（SessionWord + masteredAt + WordMastery 单事务写入）。
     */
    @Suppress("UnusedParameter") // source 为 LE spec §11 入口签名：v1 仅标识来源、不落库不改变行为
    public suspend fun markMastered(
        sessionId: Long,
        wordId: Long,
        source: MasterySource,
    ): MasteryResult {
        val session = repository.getSession(sessionId)
            ?: throw RepositoryValidationException("会话不存在：sessionId=$sessionId")
        if (session.status != SessionStatus.ACTIVE) {
            return MasteryResult.Rejected(MasteryResult.Reason.SESSION_NOT_ACTIVE)
        }
        val now = clock.now()
        return if (repository.markSessionWordMastered(sessionId, wordId, now)) {
            MasteryResult.Marked(now)
        } else {
            MasteryResult.AlreadyMastered
        }
    }
}
