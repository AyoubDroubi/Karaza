// Minimal JNI over llama.cpp for on-device translation: load a GGUF model, greedy-generate one answer for one
// prompt (the caller formats the chat template), free. One generation at a time per handle (the Kotlin side
// serializes calls); a cancel flag stops a running generation, also during prompt processing (abort callback).
// No C++ exception may cross JNI (that would abort the app), so every entry point catches them.

#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <exception>
#include <string>
#include <vector>

#include "ggml-backend.h"
#include "llama.h"

#define TAG "KarazaLlm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    llama_sampler *sampler = nullptr;
    std::atomic<bool> cancel{false};
};

void log_callback(ggml_log_level level, const char *text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
}

bool abort_callback(void *data) {
    return static_cast<Session *>(data)->cancel.load();
}

void throw_java(JNIEnv *env, const std::string &msg) {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr) env->ThrowNew(cls, msg.c_str());
}

void free_session(Session *s) {
    if (s == nullptr) return;
    if (s->sampler != nullptr) llama_sampler_free(s->sampler);
    if (s->ctx != nullptr) llama_free(s->ctx);
    if (s->model != nullptr) llama_model_free(s->model);
    delete s;
}

std::string piece(const llama_vocab *vocab, llama_token tok) {
    std::vector<char> buf(256);
    int n = llama_token_to_piece(vocab, tok, buf.data(), (int32_t) buf.size(), 0, false);
    if (n < 0) {
        buf.resize((size_t) -n);
        n = llama_token_to_piece(vocab, tok, buf.data(), (int32_t) buf.size(), 0, false);
    }
    return n > 0 ? std::string(buf.data(), (size_t) n) : std::string();
}

std::string jbytes_to_string(JNIEnv *env, jbyteArray bytes) {
    const jsize len = env->GetArrayLength(bytes);
    std::string out((size_t) len, '\0');
    if (len > 0) env->GetByteArrayRegion(bytes, 0, len, reinterpret_cast<jbyte *>(&out[0]));
    return out;
}

std::string jstring_to_string(JNIEnv *env, jstring s) {
    const char *c = env->GetStringUTFChars(s, nullptr);
    if (c == nullptr) return std::string();
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

// CPU backend variants, best first (names from ggml's Android variant list).
const char *const kCpuVariants[] = {
    "libggml-cpu-android_armv9.2_2.so", "libggml-cpu-android_armv9.2_1.so", "libggml-cpu-android_armv9.0_1.so",
    "libggml-cpu-android_armv8.6_1.so", "libggml-cpu-android_armv8.2_2.so", "libggml-cpu-android_armv8.2_1.so",
    "libggml-cpu-android_armv8.0_1.so",
};

/**
 * When the APK keeps native libraries inside the archive (not extracted), the library directory is empty and
 * scanning it finds nothing; the dynamic linker can still open them by name. Pick the best variant this CPU
 * supports (ggml_backend_score > 0) and load it.
 */
void load_cpu_backend_by_name() {
    const char *best = nullptr;
    int bestScore = 0;
    for (const char *name : kCpuVariants) {
        void *h = dlopen(name, RTLD_NOW | RTLD_LOCAL);
        if (h == nullptr) continue;
        using score_fn = int (*)();
        auto score = reinterpret_cast<score_fn>(dlsym(h, "ggml_backend_score"));
        const int sc = score != nullptr ? score() : 0;
        dlclose(h);
        if (sc > bestScore) {
            bestScore = sc;
            best = name;
        }
    }
    if (best != nullptr) {
        LOGI("loading CPU backend %s (score %d)", best, bestScore);
        ggml_backend_load(best);
    }
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_app_karaza_llm_LocalLlm_nativeInit(JNIEnv *env, jclass, jstring backendDir) {
    try {
        llama_log_set(log_callback, nullptr);
        const std::string dir = jstring_to_string(env, backendDir);
        ggml_backend_load_all_from_path(dir.c_str());
        if (ggml_backend_reg_count() == 0) load_cpu_backend_by_name();
        llama_backend_init();
        const auto count = (jint) ggml_backend_reg_count();
        LOGI("backends loaded: %d", count);
        return count;
    } catch (const std::exception &e) {
        throw_java(env, std::string("Init failed: ") + e.what());
        return 0;
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_karaza_llm_LocalLlm_nativeLoad(JNIEnv *env, jclass, jstring path, jint nCtx, jint nThreads) {
    Session *s = nullptr;
    try {
        const std::string p = jstring_to_string(env, path);
        llama_model_params mp = llama_model_default_params();
        mp.n_gpu_layers = 0;
        llama_model *model = llama_model_load_from_file(p.c_str(), mp);
        if (model == nullptr) {
            throw_java(env, "Could not load the model file");
            return 0;
        }
        s = new Session();
        s->model = model;
        llama_context_params cp = llama_context_default_params();
        cp.n_ctx = (uint32_t) nCtx;
        cp.n_batch = 512;
        cp.n_ubatch = 512;
        cp.n_threads = nThreads;
        cp.n_threads_batch = nThreads;
        cp.no_perf = true;
        cp.abort_callback = abort_callback;
        cp.abort_callback_data = s;
        s->ctx = llama_init_from_model(model, cp);
        if (s->ctx == nullptr) {
            free_session(s);
            throw_java(env, "Could not create the model context (not enough memory?)");
            return 0;
        }
        llama_sampler_chain_params sp = llama_sampler_chain_default_params();
        sp.no_perf = true;
        s->sampler = llama_sampler_chain_init(sp);
        llama_sampler_chain_add(s->sampler, llama_sampler_init_greedy()); // temperature 0: deterministic
        return reinterpret_cast<jlong>(s);
    } catch (const std::exception &e) {
        free_session(s);
        throw_java(env, std::string("Load failed: ") + e.what());
        return 0;
    }
}

/**
 * Returns the answer as raw UTF-8 bytes prefixed by one status byte: 1 = the model finished (end of generation),
 * 0 = it was cut off by [maxTokens] or the context size (the last line may be incomplete).
 * The prompt also comes as UTF-8 bytes (JNI strings use modified UTF-8, which breaks characters outside the BMP).
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_karaza_llm_LocalLlm_nativeGenerate(JNIEnv *env, jclass, jlong handle, jbyteArray prompt, jint maxTokens) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) {
        throw_java(env, "Model is not loaded");
        return nullptr;
    }
    try {
        const llama_vocab *vocab = llama_model_get_vocab(s->model);
        const std::string text = jbytes_to_string(env, prompt);

        // Tokenize (special tokens like <|im_start|> are parsed; the template already holds them).
        const int n = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, true, true);
        std::vector<llama_token> tokens((size_t) std::max(n, 0));
        if (n <= 0 || llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), tokens.data(), n, true, true) < 0) {
            throw_java(env, "Tokenization failed");
            return nullptr;
        }
        const int nCtx = (int) llama_n_ctx(s->ctx);
        if ((int) tokens.size() + 16 >= nCtx) {
            throw_java(env, "Prompt too long for the model context (" + std::to_string(tokens.size()) + " tokens)");
            return nullptr;
        }

        llama_memory_clear(llama_get_memory(s->ctx), true);
        llama_sampler_reset(s->sampler);

        const int nBatch = (int) llama_n_batch(s->ctx);
        for (size_t i = 0; i < tokens.size(); i += (size_t) nBatch) {
            if (s->cancel.load()) {
                throw_java(env, "Cancelled");
                return nullptr;
            }
            const int count = (int) std::min((size_t) nBatch, tokens.size() - i);
            if (llama_decode(s->ctx, llama_batch_get_one(tokens.data() + i, count)) != 0) {
                throw_java(env, s->cancel.load() ? "Cancelled" : "Prompt processing failed");
                return nullptr;
            }
        }

        std::string out;
        bool finished = false;
        const int limit = std::min((int) maxTokens, nCtx - (int) tokens.size() - 1);
        for (int i = 0; i < limit; i++) {
            if (s->cancel.load()) {
                throw_java(env, "Cancelled");
                return nullptr;
            }
            llama_token tok = llama_sampler_sample(s->sampler, s->ctx, -1);
            if (llama_vocab_is_eog(vocab, tok)) {
                finished = true;
                break;
            }
            out += piece(vocab, tok);
            if (llama_decode(s->ctx, llama_batch_get_one(&tok, 1)) != 0) {
                throw_java(env, s->cancel.load() ? "Cancelled" : "Generation failed");
                return nullptr;
            }
        }

        jbyteArray result = env->NewByteArray((jsize) out.size() + 1);
        if (result == nullptr) return nullptr; // OutOfMemoryError is pending
        const jbyte status = finished ? 1 : 0;
        env->SetByteArrayRegion(result, 0, 1, &status);
        if (!out.empty()) {
            env->SetByteArrayRegion(result, 1, (jsize) out.size(), reinterpret_cast<const jbyte *>(out.data()));
        }
        return result;
    } catch (const std::exception &e) {
        throw_java(env, std::string("Generation failed: ") + e.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_app_karaza_llm_LocalLlm_nativeSetCancel(JNIEnv *, jclass, jlong handle, jboolean cancel) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (s != nullptr) s->cancel.store(cancel == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_app_karaza_llm_LocalLlm_nativeFree(JNIEnv *, jclass, jlong handle) {
    free_session(reinterpret_cast<Session *>(handle));
}
