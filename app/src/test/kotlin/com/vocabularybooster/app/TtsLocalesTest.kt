package com.vocabularybooster.app

import com.vocabularybooster.domain.model.Lang
import com.vocabularybooster.platform.TtsLocales
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * TtsLocales 纯映射 JVM 单测（Phase 4 Step 3）：
 * AUDIO_ENGINE_SPEC §8 双语段切换——EN → en-US、CN → zh-CN 的 Locale 映射。
 * TTS 引擎行为（init/完成/stop）无法在 JVM 稳定验证 → instrumented 测试覆盖。
 */
class TtsLocalesTest {

    @Test
    fun mapsBothLanguages() {
        assertEquals(Locale.US, TtsLocales.localeFor(Lang.EN_US))
        assertEquals(Locale.SIMPLIFIED_CHINESE, TtsLocales.localeFor(Lang.ZH_CN))
    }
}
