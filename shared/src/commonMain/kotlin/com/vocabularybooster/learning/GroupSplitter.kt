package com.vocabularybooster.learning

import com.vocabularybooster.domain.model.SessionWordPlacement

/**
 * 分组固化（LEARNING_ENGINE_SPEC §4，FR-6）：groupIndex/orderInGroup 在会话开始时固化入库，
 * 之后改 groupSize 设置只影响新会话。
 * 纯函数：不读设置/时钟/数据库/随机源；groupSize 由调用方传入已确定值
 * （设置默认值 10 属 AppSetting，DATABASE_SCHEMA §2.11，读取属设置仓储/引擎层）。
 */
public object GroupSplitter {

    /**
     * ordered queue → 固化放置表。索引 0 起（LE spec §4 / DOMAIN_MODEL §2.9）：
     * - `groupIndex = 队列位置 / groupSize`（0 起；用户可见组号 = groupIndex + 1）；
     * - `orderInGroup = 队列位置 % groupSize`（0 起）。
     *
     * 契约：
     * - 空 queue → 空表（引擎层不会发生：建队已拒绝空本/全掌握）；
     * - groupSize < 1 → [IllegalArgumentException]（调用方缺陷；仓储层 createSession 另有同规则守卫）；
     * - 不去重、不重排：位置即真相，词唯一性由 SessionWord 复合主键在物化事务兜底（Phase 3 Step 1）。
     */
    public fun split(queue: List<StudyQueueWord>, groupSize: Int): List<SessionWordPlacement> {
        require(groupSize >= 1) { "groupSize 必须 ≥ 1：groupSize=$groupSize" }
        return queue.mapIndexed { index, word ->
            SessionWordPlacement(
                wordId = word.wordId,
                groupIndex = index / groupSize,
                orderInGroup = index % groupSize,
            )
        }
    }
}
