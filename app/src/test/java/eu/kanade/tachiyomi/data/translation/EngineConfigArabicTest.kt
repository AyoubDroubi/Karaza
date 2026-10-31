package eu.kanade.tachiyomi.data.translation

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.translation.service.TranslationPreferences

class EngineConfigArabicTest {

    private fun prefs(target: String) =
        TranslationPreferences(InMemoryPreferenceStore()).apply { targetLangName.set(target) }

    @Test
    fun `Arabic target gets the fuller LLM language name`() {
        val cfg = EngineConfigMapping.buildEngineConfig(prefs("Arabic"), "auto_whole").translator
        cfg.toLangName shouldStartWith "Arabic"
        cfg.toLangName shouldContain "Modern Standard Arabic"
    }

    @Test
    fun `Arabic target ships an Arabic few-shot in the line protocol`() {
        val cfg = EngineConfigMapping.buildEngineConfig(prefs("Arabic"), "auto_whole").translator
        cfg.sampleSource shouldStartWith "<|1|>"
        cfg.sampleTarget shouldStartWith "<|1|>"
        cfg.sampleTarget.lines().size shouldBe cfg.sampleSource.lines().size
        cfg.sampleTarget.any { it.code in 0x0600..0x06FF } shouldBe true
    }

    @Test
    fun `other non-default targets still drop the built-in few-shot`() {
        val cfg = EngineConfigMapping.buildEngineConfig(prefs("English"), "auto_whole").translator
        cfg.toLangName shouldBe "English"
        cfg.sampleSource shouldBe ""
        cfg.sampleTarget shouldBe ""
    }
}
