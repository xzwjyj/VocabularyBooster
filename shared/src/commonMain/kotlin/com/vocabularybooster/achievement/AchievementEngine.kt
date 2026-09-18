package com.vocabularybooster.achievement

import com.vocabularybooster.domain.event.DomainEvent
import com.vocabularybooster.domain.event.DomainEventBus
import com.vocabularybooster.domain.model.AchievementType
import com.vocabularybooster.domain.model.BookCompletedPayload
import com.vocabularybooster.domain.model.SessionStatus
import com.vocabularybooster.domain.repository.AchievementRepository
import com.vocabularybooster.domain.repository.LearningSessionRepository
import com.vocabularybooster.domain.repository.WordBookRepository
import com.vocabularybooster.learning.CompletionDetector
import com.vocabularybooster.platform.LogLevel
import com.vocabularybooster.platform.LogSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 勋章引擎（ACHIEVEMENT_SPEC §2，FR-13，Phase 6）：订阅 [DomainEventBus]，
 * `WordBookCompleted` → 防御复核 → 快照 → 幂等授予 → 首次授予发 `AchievementUnlocked`。
 *
 * - **防御复核（不信事件、信数据）**：session.status == COMPLETED &&
 *   [CompletionDetector.isSessionComplete]（与学习引擎完成判据同源，ADR-002 会话快照口径——
 *   会话完成 ≠ 书 Q3==0，中途加词不入快照，书级复核会误丢合法勋章）。
 * - **失败隔离**：授予路径任何异常 → LogSink 记录丢弃，绝不阻断事件流/学习/播放。
 * - **幂等三态**：复核不过 → 丢弃；已授予 → no-op 零事件；首次 → 授予 + AchievementUnlocked
 *   （重复事件/终态 resume 重发/重学同本再完成均收敛为同一行，唯一索引兜底）。
 * - **授予规则只在 shared**（架构铁律 2）；本类零 UI、零平台依赖。
 */
public class AchievementEngine(
    private val eventBus: DomainEventBus,
    private val sessionRepository: LearningSessionRepository,
    private val wordBookRepository: WordBookRepository,
    private val achievementRepository: AchievementRepository,
    private val logSink: LogSink,
) {

    /**
     * 订阅事件（应用级 scope 一次调用；进程生命周期内存活——
     * 冷启动即订阅先于任何完成事件，SharedFlow 无 replay 不会收到历史）。
     */
    public fun start(scope: CoroutineScope) {
        scope.launch {
            eventBus.events.collect { handleEvent(it) }
        }
    }

    @Suppress("TooGenericExceptionCaught") // 隔离边界：广谱捕获即语义（授予失败只丢弃留痕，绝不传播）
    private suspend fun handleEvent(event: DomainEvent) {
        if (event !is DomainEvent.WordBookCompleted) return
        try {
            onWordBookCompleted(event)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logSink.log(LogLevel.ERROR, TAG, "勋章授予失败，已丢弃（学习/播放不受影响）：$event", e)
        }
    }

    /**
     * 授予路径（ACHIEVEMENT_SPEC v1.1 §2）：
     * 1. 防御复核：库中会话 COMPLETED 且快照全掌握（快照空集 = 不构成完成，不授勋）；
     * 2. 快照：bookName（现名）/ wordCount = 会话快照词数 / finishedAt = session.endedAt；
     * 3. 幂等授予（grantBookCompleted 三层兜底）；
     * 4. 首次授予 → 发布 AchievementUnlocked（已授予重发 = 零事件）。
     */
    internal suspend fun onWordBookCompleted(event: DomainEvent.WordBookCompleted) {
        val snapshot = sessionRepository.getSessionWithWords(event.sessionId)
        if (snapshot == null ||
            snapshot.session.status != SessionStatus.COMPLETED ||
            !CompletionDetector.isSessionComplete(snapshot.words)
        ) {
            logSink.log(LogLevel.WARN, TAG, "防御复核未通过，事件丢弃：$event")
            return
        }
        val endedAt = snapshot.session.endedAt
        val bookName = wordBookRepository.getWordBookName(event.wordBookId)
        if (endedAt == null || bookName == null) {
            logSink.log(LogLevel.WARN, TAG, "会话/生词本终态数据缺失，事件丢弃：$event")
            return
        }
        val grant = achievementRepository.grantBookCompleted(
            wordBookId = event.wordBookId,
            payload = BookCompletedPayload(
                bookName = bookName,
                wordCount = snapshot.words.size,
                finishedAt = endedAt,
            ),
        )
        if (grant.firstGrant) {
            eventBus.publish(
                DomainEvent.AchievementUnlocked(
                    achievementId = grant.achievement.achievementId,
                    type = AchievementType.BOOK_COMPLETED.name,
                    wordBookId = event.wordBookId,
                ),
            )
        }
    }

    private companion object {
        const val TAG = "VB-Achievement"
    }
}
