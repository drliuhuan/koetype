// JNI bridge between Kotlin (com.drliuhuan.sayboardpro.llm.LlamaInferenceEngine)
// and llama.cpp. Modelled on the official examples/llama.android binding for
// llama.cpp b4999.
//
// Kotlin surface (com.drliuhuan.sayboardpro.llm.LlamaInferenceEngine):
//   nativeLoadModel(modelPath: String, nThreads: Int): Long
//       - load model + context, return session handle
//   nativeGenerate(handle: Long, prompt: String, maxTokens: Int): String
//       - run completion
//   nativeFree(handle: Long)                        - release the session

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cstring>

#include "llama.h"
#include "common.h"

#define TAG "sayboard-llama"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Everything a single inference session needs. The jlong handle handed back to
// Kotlin is a pointer to one of these.
struct LlamaSession {
    llama_model * model;
    llama_context * ctx;
    llama_batch batch;
    llama_sampler * sampler;
};

static void log_callback(ggml_log_level level, const char * fmt, void * /*data*/) {
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", fmt); break;
        case GGML_LOG_LEVEL_WARN:  __android_log_print(ANDROID_LOG_WARN,  TAG, "%s", fmt); break;
        case GGML_LOG_LEVEL_INFO:  __android_log_print(ANDROID_LOG_INFO,  TAG, "%s", fmt); break;
        default:                   __android_log_print(ANDROID_LOG_DEBUG, TAG, "%s", fmt); break;
    }
}

static void throw_jni(JNIEnv * env, const char * klass, const char * message) {
    jclass clazz = env->FindClass(klass);
    if (clazz) {
        env->ThrowNew(clazz, message);
    }
}

//
// init
//

extern "C" JNIEXPORT jlong JNICALL
Java_com_drliuhuan_sayboardpro_llm_LlamaInferenceEngine_nativeLoadModel(
        JNIEnv * env, jclass, jstring jModelPath, jint jThreads) {
    const char * path = env->GetStringUTFChars(jModelPath, nullptr);
    if (path == nullptr) {
        throw_jni(env, "java/lang/IllegalStateException", "Could not read model path");
        return 0;
    }

    llama_log_set(log_callback, nullptr);
    llama_backend_init();

    llama_model_params model_params = llama_model_default_params();
    llama_model * model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(jModelPath, path);

    if (model == nullptr) {
        LOGE("llama_model_load_from_file failed");
        llama_backend_free();
        throw_jni(env, "java/lang/IllegalStateException",
                  "Failed to load GGUF model (wrong architecture or corrupt file)");
        return 0;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx           = 2048;
    ctx_params.n_threads       = jThreads > 0 ? jThreads : 4;
    ctx_params.n_threads_batch = ctx_params.n_threads;

    llama_context * ctx = llama_new_context_with_model(model, ctx_params);
    if (ctx == nullptr) {
        LOGE("llama_new_context_with_model failed");
        llama_model_free(model);
        llama_backend_free();
        throw_jni(env, "java/lang/IllegalStateException",
                  "Failed to create inference context (not enough memory?)");
        return 0;
    }

    // Deterministic decoding: best for punctuation/correction tasks and avoids
    // hallucinated phrasing. (temperature 0)
    llama_sampler * sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (sampler == nullptr) {
        LOGE("llama_sampler_chain_init failed");
        llama_free(ctx);
        llama_model_free(model);
        llama_backend_free();
        throw_jni(env, "java/lang/IllegalStateException", "Failed to create sampler");
        return 0;
    }
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

    // Batch holds the whole prompt (up to 2048 tokens) plus one decoded token.
    llama_batch batch = llama_batch_init(2048, 0, 1);

    auto * session = new LlamaSession{ model, ctx, batch, sampler };
    const int n_vocab = llama_vocab_n_tokens(llama_model_get_vocab(model));
    LOGI("model loaded: n_vocab=%d", n_vocab);
    return reinterpret_cast<jlong>(session);
}

//
// generate
//

extern "C" JNIEXPORT jstring JNICALL
Java_com_drliuhuan_sayboardpro_llm_LlamaInferenceEngine_nativeGenerate(
        JNIEnv * env, jclass, jlong jHandle, jstring jPrompt, jint jMaxTokens) {
    auto * session = reinterpret_cast<LlamaSession *>(jHandle);
    if (session == nullptr) {
        throw_jni(env, "java/lang/IllegalStateException", "Invalid llama session");
        return env->NewStringUTF("");
    }

    const char * prompt = env->GetStringUTFChars(jPrompt, nullptr);
    if (prompt == nullptr) {
        return env->NewStringUTF("");
    }
    const std::string text(prompt);
    env->ReleaseStringUTFChars(jPrompt, prompt);

    const int max_tokens = jMaxTokens > 0 ? jMaxTokens : 256;
    const llama_vocab * vocab = llama_model_get_vocab(session->model);
    const int n_ctx = llama_n_ctx(session->ctx);

    // parse_special=true so the ChatML control tokens (e.g. <|im_start|>) that
    // Kotlin embeds in the prompt become single special tokens; add_special=false
    // so no BOS is prepended on top of the template.
    const std::vector<llama_token> tokens = common_tokenize(session->ctx, text, false, true);
    const int n_prompt = static_cast<int>(tokens.size());

    if (n_prompt == 0) {
        throw_jni(env, "java/lang/IllegalArgumentException", "Empty prompt");
        return env->NewStringUTF("");
    }
    if (n_prompt + max_tokens > n_ctx - 16) {
        LOGE("prompt (%d tokens) + generation (%d) exceeds context (%d)",
             n_prompt, max_tokens, n_ctx);
        throw_jni(env, "java/lang/IllegalArgumentException", "Prompt too long");
        return env->NewStringUTF("");
    }

    llama_kv_self_clear(session->ctx);

    // Evaluate the full prompt; keep logits only for the last token.
    common_batch_clear(session->batch);
    for (int i = 0; i < n_prompt; i++) {
        common_batch_add(session->batch, tokens[i], i, {0}, false);
    }
    session->batch.logits[session->batch.n_tokens - 1] = true;

    if (llama_decode(session->ctx, session->batch) != 0) {
        LOGE("llama_decode failed while processing the prompt");
        throw_jni(env, "java/lang/IllegalStateException", "llama_decode failed");
        return env->NewStringUTF("");
    }

    std::string output;
    int n_cur = n_prompt;
    while (n_cur - n_prompt < max_tokens) {
        const llama_token new_token_id = llama_sampler_sample(session->sampler, session->ctx, -1);
        if (llama_vocab_is_eog(vocab, new_token_id)) {
            break;
        }

        // special=false renders special tokens as empty pieces, so any stray
        // control token cannot leak literal text into the correction.
        output += common_token_to_piece(session->ctx, new_token_id, false);

        common_batch_clear(session->batch);
        common_batch_add(session->batch, new_token_id, n_cur, {0}, true);
        n_cur++;

        if (llama_decode(session->ctx, session->batch) != 0) {
            LOGE("llama_decode failed during generation");
            break;
        }
    }

    llama_kv_self_clear(session->ctx);
    return env->NewStringUTF(output.c_str());
}

//
// free
//

extern "C" JNIEXPORT void JNICALL
Java_com_drliuhuan_sayboardpro_llm_LlamaInferenceEngine_nativeFree(
        JNIEnv * /*env*/, jclass, jlong jHandle) {
    auto * session = reinterpret_cast<LlamaSession *>(jHandle);
    if (session == nullptr) {
        return;
    }
    llama_sampler_free(session->sampler);
    llama_batch_free(session->batch);
    llama_free(session->ctx);
    llama_model_free(session->model);
    llama_backend_free();
    delete session;
    LOGI("model unloaded");
}
