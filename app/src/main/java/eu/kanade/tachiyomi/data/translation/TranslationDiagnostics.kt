package eu.kanade.tachiyomi.data.translation

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import li.joye.yakuyomi.engine.LlmTranslator
import tachiyomi.domain.translation.service.TranslationPreferences
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * User-facing translation diagnostics:
 *  - [testConnection]: one real round-trip to the configured LLM with the current provider, key,
 *    model and language, so the user can tell "key/model/quota problem" apart from "page problem".
 *  - a small app-wide log of recent chapter failures ([recordChapterErrors] / [recentErrors]),
 *    because the per-chapter `.yakuyomi_errors.txt` files are hidden inside download folders.
 */
object TranslationDiagnostics {

    // ---------------------------------------------------------------- connection test

    data class TestResult(
        val ok: Boolean,
        val millis: Long,
        val provider: String,
        val model: String,
        val target: String,
        /** Translated lines on success (may be partial). */
        val lines: List<String> = emptyList(),
        /** Error text from the engine (HTTP status, provider message, parse problem…). */
        val error: String? = null,
        /** Start of the raw response, to see what the provider actually returned. */
        val raw: String? = null,
    )

    /** Two short original lines; enough to see register, punctuation and the line protocol. */
    private val SAMPLE = listOf(
        "Wait... you're telling me the gate opened again?!",
        "Get behind me. NOW!",
    )

    suspend fun testConnection(prefs: TranslationPreferences): TestResult = withContext(Dispatchers.IO) {
        val cfg = EngineConfigMapping.buildEngineConfig(prefs, prefs.inpaintMethod.get()).translator
        val key = prefs.activeApiKey()
        val base = TestResult(
            ok = false,
            millis = 0,
            provider = cfg.provider,
            model = cfg.model,
            target = prefs.targetLangName.get(),
        )
        if (key.isBlank()) return@withContext base.copy(error = "No API key set for ${cfg.provider}.")

        val start = System.currentTimeMillis()
        val result = runCatching { LlmTranslator(key, cfg).translateDetailed(SAMPLE) }
        val millis = System.currentTimeMillis() - start
        result.fold(
            onSuccess = { r ->
                // The engine falls back to the source line when a line is missing, so "unchanged" means "not translated".
                val translated = r.translations.zip(SAMPLE).count { (out, src) -> out.isNotBlank() && out != src }
                base.copy(
                    ok = r.error == null && translated == SAMPLE.size,
                    millis = millis,
                    lines = r.translations,
                    error = r.error ?: if (translated < SAMPLE.size) "Only $translated/${SAMPLE.size} lines came back translated." else null,
                    raw = r.raw,
                )
            },
            onFailure = { t -> base.copy(millis = millis, error = "${t.javaClass.simpleName}: ${t.message}") },
        )
    }

    // ---------------------------------------------------------------- recent errors

    private const val LOG_FILE = "translation_errors.log"
    private const val MAX_LINES = 300
    private val lock = Any()

    private fun logFile(context: Context) = File(context.filesDir, LOG_FILE)

    /**
     * Append one chapter's failure lines (`<page>\t<reason>` as written to `.yakuyomi_errors.txt`),
     * prefixed with time, manhwa and chapter, keeping only the newest [MAX_LINES] lines.
     */
    fun recordChapterErrors(context: Context, manga: String?, chapter: String?, lines: List<String>) {
        if (lines.isEmpty()) return
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val where = "${manga ?: "?"} / ${chapter ?: "?"}"
        // An entry starts with "<page>\t<reason>"; a reason that itself spanned several lines arrives as
        // continuation lines without a tab. Stitch those back so one failure stays one log line.
        val entries = ArrayList<String>()
        for (line in lines) {
            val l = line.trim()
            if (l.isEmpty()) continue
            if ('\t' in line || entries.isEmpty()) entries.add(l) else entries[entries.lastIndex] = entries.last() + " " + l
        }
        val newLines = entries.map { "$stamp | $where | ${it.replace('\t', ' ').replace(Regex("\\s+"), " ")}" }
        runCatching {
            synchronized(lock) {
                val f = logFile(context)
                val existing = if (f.exists()) f.readLines() else emptyList()
                f.writeText((existing + newLines).takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
            }
        }
    }

    /** Newest first. */
    fun recentErrors(context: Context, max: Int = 60): List<String> = runCatching {
        synchronized(lock) {
            val f = logFile(context)
            if (f.exists()) f.readLines().filter { it.isNotBlank() }.takeLast(max).asReversed() else emptyList()
        }
    }.getOrDefault(emptyList())

    fun clearErrors(context: Context) {
        runCatching { synchronized(lock) { logFile(context).delete() } }
    }
}
