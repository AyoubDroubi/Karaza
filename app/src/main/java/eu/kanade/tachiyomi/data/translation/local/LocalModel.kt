package eu.kanade.tachiyomi.data.translation.local

import android.content.Context
import app.karaza.llm.LocalLlm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import logcat.LogPriority
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * The on-device translation model: Index-Translate-2B (IndexTeam, Apache-2.0), Q8_0 GGUF, downloaded from the official
 * repository and checked against the SHA-256 pinned here (from the repository's file list and its SHA256SUMS.txt).
 * Lives in the app's private files; the download resumes where it stopped.
 */
class LocalModel(private val context: Context) {

    sealed interface State {
        data object Missing : State
        data class Downloading(val downloaded: Long, val total: Long) : State {
            val percent: Int get() = if (total > 0) ((downloaded * 100) / total).toInt() else 0
        }
        data object Verifying : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _state = MutableStateFlow(if (isReady()) State.Ready else State.Missing)
    val state: StateFlow<State> = _state.asStateFlow()

    private val dir: File get() = File(context.filesDir, "llm").apply { mkdirs() }
    val file: File get() = File(dir, FILE_NAME)
    private val partFile: File get() = File(dir, "$FILE_NAME.part")
    private val verifiedMarker: File get() = File(dir, "$FILE_NAME.sha256-ok")

    /** Downloaded, complete and verified (the verification is remembered so it is not redone on every start). */
    fun isReady(): Boolean = file.length() == SIZE && verifiedMarker.exists()

    fun download() {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                // Complete file left without its marker (app killed between saving and marking): just verify it.
                if (file.length() == SIZE && !verifiedMarker.exists()) {
                    _state.value = State.Verifying
                    if (sha256(file) == SHA256) {
                        verifiedMarker.writeText(SHA256)
                        _state.value = State.Ready
                        return@launch
                    }
                    file.delete()
                }
                _state.value = State.Downloading(partFile.length().coerceAtMost(SIZE), SIZE)
                fetch()
                _state.value = State.Verifying
                val got = sha256(partFile)
                if (got != SHA256) {
                    partFile.delete()
                    error("Checksum mismatch: the download was damaged, please try again")
                }
                file.delete()
                check(partFile.renameTo(file)) { "Could not save the model file" }
                verifiedMarker.writeText(SHA256)
                _state.value = State.Ready
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = if (isReady()) State.Ready else State.Missing
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "On-device model download failed" }
                _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancelDownload() {
        job?.cancel()
    }

    /** A finished or partial download is on disk (it can be deleted). */
    fun hasFiles(): Boolean = file.exists() || partFile.exists()

    fun delete() {
        job?.cancel()
        file.delete()
        partFile.delete()
        verifiedMarker.delete()
        _state.value = State.Missing
    }

    /** Downloads into the .part file, continuing from its current length when the server supports ranges. */
    private suspend fun fetch() {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        var have = partFile.length().coerceAtMost(SIZE)
        if (have == SIZE) return
        val request = Request.Builder().url(URL).apply {
            if (have > 0) header("Range", "bytes=$have-")
        }.build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("Download failed: HTTP ${resp.code}")
            if (resp.code != 206) have = 0 // server sent the whole file: start over
            val body = resp.body
            RandomAccessFile(partFile, "rw").use { raf -> raf.setLength(have) }
            FileOutputStream(partFile, have > 0).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(1 shl 16)
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        have += n
                        if (have - lastReport >= REPORT_EVERY || have == SIZE) {
                            lastReport = have
                            _state.value = State.Downloading(have, SIZE)
                        }
                    }
                }
            }
        }
        if (partFile.length() != SIZE) error("Download incomplete (${partFile.length()} of $SIZE bytes); tap to resume")
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Translation provider id of the on-device model (stored in the provider preference). */
        const val PROVIDER_ID = "local"
        const val DISPLAY_NAME = "Index-Translate 2B (Q8_0)"

        const val FILE_NAME = "Index-Translate-2B.Q8_0.gguf"
        /** Pinned to a repository commit, so a later re-upload cannot break the pinned size and checksum. */
        const val URL = "https://huggingface.co/IndexTeam/Index-Translate-2B-GGUF/resolve/" +
            "449c9e6457b3632d328c6cbb78ae8e8e0c8059a5/$FILE_NAME"
        const val SIZE = 2_076_674_560L
        const val SHA256 = "a09d287f41d297206947a2e51acc29dd03c8b22356111fdb1e289c0c97c173f3"
        private const val REPORT_EVERY = 4L * 1024 * 1024

        /** On-device translation runs on arm64 phones only. */
        val isSupported: Boolean get() = LocalLlm.isSupported
    }
}
