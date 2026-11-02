package eu.kanade.tachiyomi.data.translation

import android.content.Context
import android.graphics.Typeface
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Picks the typeface the engine draws translations with, per target language.
 *
 * Arabic uses the bundled Tajawal Bold (SIL OFL 1.1, see assets/fonts/Tajawal-OFL.txt): a clean,
 * heavy sans that stays legible at small bubble sizes, unlike the system Naskh which reads like
 * book text. Typefaces built from assets keep the system fallback chain, so any Latin, CJK or
 * emoji the LLM leaves in the line still renders.
 *
 * Every other target returns null, which keeps the engine's existing default.
 */
object TranslationFonts {

    private const val ARABIC_FONT = "fonts/Tajawal-Bold.ttf"

    @Volatile private var arabic: Typeface? = null

    fun forTarget(context: Context, target: String): Typeface? = when (target) {
        "Arabic" -> arabic ?: load(context, ARABIC_FONT)?.also { arabic = it }
        else -> null
    }

    private fun load(context: Context, path: String): Typeface? =
        runCatching { Typeface.createFromAsset(context.assets, path) }
            .onFailure { logcat(LogPriority.WARN, it) { "Could not load translation font $path; using system default" } }
            .getOrNull()
}
