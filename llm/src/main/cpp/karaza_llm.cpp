// Minimal JNI over llama.cpp for on-device translation: load a GGUF model, greedy-generate one answer for one
// prompt (the caller formats the chat template), free. One generation at a time per handle (the Kotlin side
// serializes calls); a cancel flag lets a running generation stop between tokens.

#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
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

void throw_java(JNIEnv *env, const std::string &msg) {
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

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_app_karaza_llm_LocalLlm_nativeInit(JNIEnv *env, jclass, jstring backendDir) {
    llama_log_set(log_callback, nullptr);
    const char *dir = env->GetStringUTFChars(backendDir, nullptr);
    // CPU backend variants are separate .so files in the app's native library directory.
    ggml_backend_load_all_from_path(dir);
    env->ReleaseStringUTFChars(backendDir, dir);
    llama_backend_init();
    LOGI("backends loaded: %zu", ggml_backend_reg_count());
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_karaza_llm_LocalLlm_nativeLoad(JNIEnv *env, jclass, jstring path, jint nCtx, jint nThreads) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    llama_model *model = llama_model_load_from_file(p, mp);
    env->ReleaseStringUTFChars(path, p);
    if (model == nullptr) {
        throw_java(env, "Could not load the model file");
        return 0;
    }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) nCtx;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = nThreads;
    cp.n_threads_batch = nThreads;
    cp.no_perf = true;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (ctx == nullptr) {
        llama_model_free(model);
        throw_java(env, "Could not create the model context (not enough memory?)");
        return 0;
    }
    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    sp.no_perf = true;
    llama_sampler *sampler = llama_sampler_chain_init(sp);
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy()); // temperature 0: deterministic
    auto *s = new Session();
    s->model = model;
    s->ctx = ctx;
    s->sampler = sampler;
    return reinterpret_cast<jlong>(s);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_karaza_llm_LocalLlm_nativeGenerate(JNIEnv *env, jclass, jlong handle, jstring prompt, jint maxTokens) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) {
        throw_java(env, "Model is not loaded");
        return nullptr;
    }
    s->cancel.store(false);
    const llama_vocab *vocab = llama_model_get_vocab(s->model);

    const char *ptext = env->GetStringUTFChars(prompt, nullptr);
    std::string text(ptext);
    env->ReleaseStringUTFChars(prompt, ptext);

    // Tokenize (special tokens like <|im_start|> are parsed; the template already holds them).
    int n = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, true, true);
    std::vector<llama_token> tokens((size_t) n);
    if (llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), tokens.data(), n, true, true) < 0) {
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
            throw_java(env, "Prompt processing failed");
            return nullptr;
        }
    }

    std::string out;
    int pos = (int) tokens.size();
    const int limit = std::min((int) maxTokens, nCtx - pos - 1);
    for (int i = 0; i < limit; i++) {
        if (s->cancel.load()) {
            throw_java(env, "Cancelled");
            return nullptr;
        }
        llama_token tok = llama_sampler_sample(s->sampler, s->ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;
        out += piece(vocab, tok);
        if (llama_decode(s->ctx, llama_batch_get_one(&tok, 1)) != 0) {
            throw_java(env, "Generation failed");
            return nullptr;
        }
        pos++;
    }

    // Raw UTF-8 bytes (NewStringUTF would mangle characters outside the BMP); Kotlin decodes them.
    jbyteArray result = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(result, 0, (jsize) out.size(), reinterpret_cast<const jbyte *>(out.data()));
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_app_karaza_llm_LocalLlm_nativeCancel(JNIEnv *, jclass, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (s != nullptr) s->cancel.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_app_karaza_llm_LocalLlm_nativeFree(JNIEnv *, jclass, jlong handle) {
    free_session(reinterpret_cast<Session *>(handle));
}
