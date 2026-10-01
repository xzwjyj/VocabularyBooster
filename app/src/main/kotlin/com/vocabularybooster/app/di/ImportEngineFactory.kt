package com.vocabularybooster.app.di

import android.content.Context
import android.net.Uri
import com.vocabularybooster.domain.dictionary.DictionaryProvider
import com.vocabularybooster.domain.event.DomainEventBus
import com.vocabularybooster.importing.ImportEngine
import com.vocabularybooster.importing.ImportRepository
import com.vocabularybooster.platform.ContentResolverFileBytesSource
import com.vocabularybooster.platform.ContentResolverTextLineSource
import kotlinx.datetime.Clock

/** 导入引擎装配（Phase 7，FR-14；SCR-TXTDICTENRICH：+词典富化源）：SAF Uri → 平台字节/行源 actual + 共享仓储/总线/时钟。 */
class ImportEngineFactory(
    private val context: Context,
    private val repository: ImportRepository,
    private val eventBus: DomainEventBus,
    private val clock: Clock,
    private val dictionaryProvider: DictionaryProvider? = null,
) {
    fun create(uri: Uri): ImportEngine = ImportEngine(
        bytesSource = ContentResolverFileBytesSource(context.contentResolver, uri),
        lineSource = ContentResolverTextLineSource(context.contentResolver, uri),
        repository = repository,
        eventBus = eventBus,
        clock = clock,
        dictionaryProvider = dictionaryProvider,
    )
}
