package com.vocabularybooster.domain.event

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * 领域事件总线端口（DOMAIN_MODEL §9，ARCHITECTURE §5）：进程内发布/订阅。
 * 发布方（PlaybackOrchestrator，播放控制路径）不依赖消费进度——
 * 缓冲满时丢弃最旧事件而非阻塞发布（勋章授予失败隔离的传输层保障）。
 */
public interface DomainEventBus {

    /**
     * 事件流（无 replay：订阅者只收订阅之后的事件——
     * 应用冷启动即订阅 [com.vocabularybooster.achievement.AchievementEngine]，先于任何完成事件）。
     */
    public val events: SharedFlow<DomainEvent>

    /** 发布事件（实现不得抛异常、不得长期挂起——播放控制路径调用）。 */
    public suspend fun publish(event: DomainEvent)
}

/** 进程内默认实现：缓冲 64 + DROP_OLDEST——publish 永不阻塞发布方（书完成事件极低频，实际不触底）。 */
public class DefaultDomainEventBus : DomainEventBus {

    private val _events = MutableSharedFlow<DomainEvent>(
        extraBufferCapacity = EVENT_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val events: SharedFlow<DomainEvent> = _events

    override suspend fun publish(event: DomainEvent) {
        _events.emit(event)
    }

    private companion object {
        const val EVENT_BUFFER_CAPACITY = 64
    }
}
