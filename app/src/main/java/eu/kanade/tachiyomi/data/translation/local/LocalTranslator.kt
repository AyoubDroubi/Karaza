package eu.kanade.tachiyomi.data.translation.local

import android.content.Context
import app.karaza.llm.LocalLlm
import eu.kanade.tachiyomi.crash.TraceLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import li.joye.yakuyomi.engine.BatchTranslation
import li.joye.yakuyomi.engine.BatchTranslator
import li.joye.yakuyomi.engine.LineProtocol
import li.joye.yakuyomi.engine.PageTranslation
import java.io.File

/**
 * Translation with the on-device model (see [LocalModel]). Uses the model's own instruction (Index-Translate is
 * trained on a short Chinese "translate into X, output only the translation" prompt) plus the numbered-line rule,
 * exactly as tested on a phone, inside the model's ChatML template with thinking turned off.
 *
 * The model is loaded on first use and kept until the engine closes ([close]). One generation runs at a time.
 * Failures throw (instead of returning the source text) so a page fails and is retried rather than being marked done.
 */
class LocalTranslator(
    private val context: Context,
    private val modelFile: File,
    private val targetLang: String,
    private val threads: Int = DEFAULT_THREADS,
) : BatchTranslator, AutoCloseable {

    private val mutex = Mutex()
    @Volatile
    private var llm: LocalLlm? = null

    override suspend fun translate(queries: List<String>): List<String> {
        val page = translatePages(listOf(queries)).pages.first()
        page.error?.let { throw IllegalStateException(it) }
        return page.translations
    }

    override suspend fun translatePages(pages: List<List<String>>): BatchTranslation {
        if (pages.isEmpty()) return BatchTranslation(emptyList(), 0, false)
        val flat = pages.flatten()
        val t0 = System.currentTimeMillis()
        return try {
            val parsed = generateLines(flat).toMutableMap()
            var requests = 1
            val missing = LineProtocol.missing(flat, parsed)
            if (missing.isNotEmpty()) {
                // Same recovery as the cloud translator: ask once more for just the missing lines.
                requests++
                val again = generateLines(missing.map { flat[it] })
                missing.forEachIndexed { k, i -> again[k + 1]?.takeIf { it.isNotBlank() }?.let { parsed[i + 1] = it } }
            }
            val ms = System.currentTimeMillis() - t0
            var offset = 0
            val out = pages.map { lines ->
                val idx = offset until offset + lines.size
                offset += lines.size
                val answered = idx.count { parsed[it + 1] != null }
                PageTranslation(
                    translations = idx.map { i -> parsed[i + 1]?.takeIf { it.isNotBlank() } ?: flat[i] },
                    error = if (answered < lines.size) "Parsed $answered/${lines.size} lines" else null,
                    translateMs = ms,
                )
            }
            BatchTranslation(out, requests = requests, hadMissingLines = missing.isNotEmpty())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            val err = "On-device model: ${e.message ?: e.javaClass.simpleName}"
            val ms = System.currentTimeMillis() - t0
            BatchTranslation(pages.map { PageTranslation(it, error = err, translateMs = ms) }, 1, false, failed = true)
        }
    }

    private suspend fun generateLines(queries: List<String>): Map<Int, String> = withContext(Dispatchers.Default) {
        val prompt = template(instruction(targetLang) + "\n\n" + LineProtocol.numbered(queries))
        // Room for the answer: Arabic needs more tokens than the English source; bounded by the context size.
        val maxTokens = (queries.sumOf { it.length } * 2 + 32 * queries.size).coerceIn(256, MAX_ANSWER_TOKENS)
        val raw = mutex.withLock {
            val model = llm ?: LocalLlm.load(context, modelFile.absolutePath, CONTEXT_TOKENS, threads).also {
                llm = it
                TraceLog.log("llm", "loaded ${modelFile.name} threads=$threads ctx=$CONTEXT_TOKENS")
            }
            val start = System.currentTimeMillis()
            model.generate(prompt, maxTokens).also {
                TraceLog.log("llm", "lines=${queries.size} ms=${System.currentTimeMillis() - start} out=${it.length}")
            }
        }
        LineProtocol.parse(raw)
    }

    /** Stops a running generation (pause / cancel); the call then fails and the pages are retried later. */
    fun cancel() {
        llm?.cancel()
    }

    override fun close() {
        llm?.cancel()
        // Free after the running generation (if any) has stopped.
        kotlinx.coroutines.runBlocking { mutex.withLock { llm?.close(); llm = null } }
    }

    companion object {
        private const val CONTEXT_TOKENS = 4096
        private const val MAX_ANSWER_TOKENS = 2048
        private const val DEFAULT_THREADS = 4

        /** ChatML with an empty thinking block = thinking off (the model's own template does the same). */
        internal fun template(user: String): String =
            "<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"

        /** The model's translation instruction (Chinese, as it was trained) plus the numbered-line rule. */
        internal fun instruction(targetLang: String): String =
            "请将以下文本翻译为${chineseName(targetLang)}，直接输出翻译结果，不要进行任何解释。" +
                "每一行都要保留开头的编号（例如 <|1|>），逐行翻译，不要合并或省略任何一行。"

        /** Target language preference key → the language name in Chinese for the instruction. */
        internal fun chineseName(targetLang: String): String = when {
            targetLang == "Arabic" -> "阿拉伯语"
            targetLang == "English" -> "英语"
            targetLang == "Japanese" -> "日语"
            targetLang == "Korean" -> "韩语"
            targetLang == "Simplified Chinese" -> "简体中文"
            targetLang.startsWith("Traditional Chinese") -> "繁体中文"
            targetLang == "Spanish" -> "西班牙语"
            targetLang == "French" -> "法语"
            targetLang == "German" -> "德语"
            targetLang == "Portuguese" -> "葡萄牙语"
            targetLang == "Russian" -> "俄语"
            else -> targetLang
        }
    }
}
