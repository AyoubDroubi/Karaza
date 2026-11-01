package app.karaza.llm

import android.content.Context
import java.io.Closeable

/**
 * An on-device LLM loaded from a GGUF file (llama.cpp). Greedy decoding (temperature 0). Callers run one
 * [generate] at a time; [cancel] may be called from any thread to stop a running generation. [close] must not run
 * while [generate] is running (the caller serializes them); [cancel] and [close] are safe against each other.
 */
class LocalLlm private constructor(handle: Long) : Closeable {

    @Volatile
    private var handle: Long = handle
    private val lock = Any()

    /** One answer: [text] and whether the model finished on its own ([complete] = false: cut off by the token limit). */
    class Answer(val text: String, val complete: Boolean)

    /** Generates the answer for an already templated [prompt]; throws [IllegalStateException] on failure or cancel. */
    fun generate(prompt: String, maxTokens: Int): Answer {
        val h = handle
        check(h != 0L) { "Model is closed" }
        val bytes = nativeGenerate(h, prompt.toByteArray(Charsets.UTF_8), maxTokens) ?: error("Generation failed")
        return Answer(String(bytes, 1, bytes.size - 1, Charsets.UTF_8), complete = bytes[0].toInt() == 1)
    }

    /** Clears a previous cancel; call before a new generation (a cancel arriving after this still stops it). */
    fun resetCancel() = synchronized(lock) {
        val h = handle
        if (h != 0L) nativeSetCancel(h, false)
    }

    fun cancel() = synchronized(lock) {
        val h = handle
        if (h != 0L) nativeSetCancel(h, true)
    }

    override fun close() = synchronized(lock) {
        val h = handle
        handle = 0
        if (h != 0L) nativeFree(h)
    }

    companion object {
        @Volatile
        private var initialized = false

        /** False on devices whose ABI has no native library (only arm64-v8a is built). */
        val isSupported: Boolean by lazy {
            runCatching { System.loadLibrary("karaza_llm") }.isSuccess
        }

        /** Loads [modelPath]; throws when the library is missing, no CPU backend loads, or the model cannot be loaded. */
        @Synchronized
        fun load(context: Context, modelPath: String, contextTokens: Int, threads: Int): LocalLlm {
            check(isSupported) { "On-device translation is not available on this device (arm64 only)" }
            if (!initialized) {
                val backends = nativeInit(context.applicationInfo.nativeLibraryDir)
                check(backends > 0) { "No CPU backend could be loaded for the on-device model" }
                initialized = true
            }
            return LocalLlm(nativeLoad(modelPath, contextTokens, threads))
        }

        @JvmStatic private external fun nativeInit(backendDir: String): Int
        @JvmStatic private external fun nativeLoad(path: String, nCtx: Int, nThreads: Int): Long
        @JvmStatic private external fun nativeGenerate(handle: Long, prompt: ByteArray, maxTokens: Int): ByteArray?
        @JvmStatic private external fun nativeSetCancel(handle: Long, cancel: Boolean)
        @JvmStatic private external fun nativeFree(handle: Long)
    }
}
