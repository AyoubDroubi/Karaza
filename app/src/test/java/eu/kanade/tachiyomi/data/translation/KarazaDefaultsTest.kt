package eu.kanade.tachiyomi.data.translation

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.translation.service.TranslationPreferences
import java.io.File

class KarazaDefaultsTest {

    @Test
    fun `fresh install translates English to Arabic`() {
        val prefs = TranslationPreferences(InMemoryPreferenceStore())
        prefs.targetLangName.get() shouldBe "Arabic"
        prefs.sourceLangName.get() shouldBe "English"
    }

    @Test
    fun `bundled model manifest matches the engine's models json`() {
        // Unit tests run with the app module as working directory.
        val bundled = File("src/main/assets/models/models.json").readText()
        val engine = File("../yakuyomi-engine/models.json").readText()
        bundled shouldBe engine
    }
}
