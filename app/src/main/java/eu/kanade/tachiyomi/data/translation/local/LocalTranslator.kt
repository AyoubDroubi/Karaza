package eu.kanade.tachiyomi.data.translation.local

import android.content.Context
import app.karaza.llm.LocalLlm
import eu.kanade.tachiyomi.crash.TraceLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import li.joye.yakuyomi.engine.BatchTranslation
import li.joye.yakuyomi.engine.BatchTranslator
import li.joye.yakuyomi.engine.LineProtocol
import li.joye.yakuyomi.engine.PageTranslation
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/**
 * Translation with the on-device model (see [LocalModel]). Uses the model's own instruction (Index-Translate is
 * trained on a short Chinese "translate into X, output only the translation" prompt) plus the numbered-line rule,
 * exactly as tested on a phone, inside the model's ChatML template with thinking turned off.
 *
 * Lines are sent in chunks of at most [CHUNK_CHARS] characters so prompt + answer always fit the context (a single
 * very text-heavy page is split too). The model is loaded on first use and kept until the engine closes ([close]).
 * One generation runs at a time on a dedicated thread; cancelling the calling coroutine stops it within a token.
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

    /** Single-page path: partial answers are kept (missing lines fall back and are filtered); only a failed run throws. */
    override suspend fun translate(queries: List<String>): List<String> {
        val batch = translatePages(listOf(queries))
        val page = batch.pages.first()
        if (batch.failed) throw IllegalStateException(page.error ?: "On-device translation failed")
        return page.translations
    }

    override suspend fun translatePages(pages: List<List<String>>): BatchTranslation {
        if (pages.isEmpty()) return BatchTranslation(emptyList(), 0, false)
        val flat = pages.flatten()
        val t0 = System.currentTimeMillis()
        return try {
            val parsed = HashMap<Int, String>()
            var requests = 0
            var hadMissing = false
            for (chunk in chunks(flat)) {
                val queries = chunk.map { flat[it] }
                val got = generateLines(queries).toMutableMap()
                requests++
                val missing = LineProtocol.missing(queries, got)
                if (missing.isNotEmpty()) {
                    // Same recovery as the cloud translator: ask once more for just the missing lines.
                    hadMissing = true
                    requests++
                    val again = generateLines(missing.map { queries[it] })
                    missing.forEachIndexed { k, i -> again[k + 1]?.takeIf { it.isNotBlank() }?.let { got[i + 1] = it } }
                }
                chunk.forEachIndexed { k, globalIndex -> got[k + 1]?.let { parsed[globalIndex + 1] = it } }
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
            BatchTranslation(out, requests = requests, hadMissingLines = hadMissing)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val err = "On-device model: ${e.message ?: e.javaClass.simpleName}"
            val ms = System.currentTimeMillis() - t0
            BatchTranslation(pages.map { PageTranslation(it, error = err, translateMs = ms) }, 1, false, failed = true)
        }
    }

    /** Consecutive index ranges of [lines] with at most [CHUNK_CHARS] characters (a longer single line goes alone). */
    private fun chunks(lines: List<String>): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        var cur = ArrayList<Int>()
        var chars = 0
        lines.forEachIndexed { i, line ->
            if (cur.isNotEmpty() && (chars + line.length > CHUNK_CHARS || cur.size >= CHUNK_LINES)) {
                out += cur
                cur = ArrayList()
                chars = 0
            }
            cur += i
            chars += line.length
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    private suspend fun generateLines(queries: List<String>): Map<Int, String> {
        // A single line longer than the chunk budget is cut so prompt + answer still fit the context.
        val safe = queries.map { if (it.length > CHUNK_CHARS) it.take(CHUNK_CHARS) else it }
        val prompt = template(instruction(targetLang) + "\n\n" + LineProtocol.numbered(safe))
        // Room for the answer: Arabic needs more tokens than the English source.
        val maxTokens = (safe.sumOf { it.length } * 2 + 32 * safe.size).coerceIn(256, MAX_ANSWER_TOKENS)
        val answer = mutex.withLock {
            coroutineContext.ensureActive()
            val model = llm ?: withContext(dispatcher) {
                LocalLlm.load(context, modelFile.absolutePath, CONTEXT_TOKENS, threads)
            }.also {
                llm = it
                TraceLog.log("llm", "loaded ${modelFile.name} threads=$threads ctx=$CONTEXT_TOKENS")
            }
            model.resetCancel()
            coroutineScope {
                // Cancelling this coroutine (pause, leaving the chapter) stops the native generation right away.
                val finished = AtomicBoolean(false)
                val watcher = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        if (!finished.get()) model.cancel()
                    }
                }
                try {
                    val start = System.currentTimeMillis()
                    withContext(dispatcher) { model.generate(prompt, maxTokens) }.also {
                        TraceLog.log(
                            "llm",
                            "lines=${safe.size} ms=${System.currentTimeMillis() - start} out=${it.text.length} complete=${it.complete}",
                        )
                    }
                } finally {
                    finished.set(true)
                    watcher.cancel()
                }
            }
        }
        val parsed = LineProtocol.parse(answer.text)
        // Cut off by the token limit: the last line may be half written, so treat it as missing (it is retried).
        if (!answer.complete) parsed.keys.maxOrNull()?.let { return parsed - it }
        return parsed
    }

    override fun close() {
        llm?.cancel()
        // Free after a running generation (if any) has stopped.
        runBlocking { mutex.withLock { llm?.close(); llm = null } }
    }

    companion object {
        private const val CONTEXT_TOKENS = 4096
        private const val MAX_ANSWER_TOKENS = 2048
        private const val DEFAULT_THREADS = 4

        /** Source characters per generation: about 300–900 prompt tokens, leaving room for the answer. */
        private const val CHUNK_CHARS = 900
        private const val CHUNK_LINES = 40

        /** All generations run on one dedicated thread (they block for seconds to minutes). */
        private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "karaza-llm") }
            .asCoroutineDispatcher()

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
