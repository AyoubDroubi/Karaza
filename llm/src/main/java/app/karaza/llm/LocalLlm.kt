package app.karaza.llm

import android.content.Context
import java.io.Closeable

/**
 * An on-device LLM loaded from a GGUF file (llama.cpp). Greedy decoding (temperature 0). Not thread-safe:
 * callers run one [generate] at a time; [cancel] may be called from any thread to stop a running generation.
 */
class LocalLlm private constructor(private var handle: Long) : Closeable {

    /** Generates the answer for an already templated [prompt]; throws [IllegalStateException] on failure or cancel. */
    fun generate(prompt: String, maxTokens: Int): String {
        val h = handle
        check(h != 0L) { "Model is closed" }
        val bytes = nativeGenerate(h, prompt, maxTokens) ?: error("Generation failed")
        return String(bytes, Charsets.UTF_8)
    }

    fun cancel() {
        val h = handle
        if (h != 0L) nativeCancel(h)
    }

    override fun close() {
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

        /** Loads [modelPath]; throws when the library is missing or the model cannot be loaded. */
        @Synchronized
        fun load(context: Context, modelPath: String, contextTokens: Int, threads: Int): LocalLlm {
            check(isSupported) { "On-device translation is not available on this device (arm64 only)" }
            if (!initialized) {
                nativeInit(context.applicationInfo.nativeLibraryDir)
                initialized = true
            }
            return LocalLlm(nativeLoad(modelPath, contextTokens, threads))
        }

        @JvmStatic private external fun nativeInit(backendDir: String)
        @JvmStatic private external fun nativeLoad(path: String, nCtx: Int, nThreads: Int): Long
        @JvmStatic private external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int): ByteArray?
        @JvmStatic private external fun nativeCancel(handle: Long)
        @JvmStatic private external fun nativeFree(handle: Long)
    }
}
