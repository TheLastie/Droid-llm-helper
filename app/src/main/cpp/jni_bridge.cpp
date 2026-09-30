#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <random>
#include <ctime>
#include <string>
#include <vector>

#include "llama.h"

// ШАГ 3b: полный JNI-мост к llama.cpp v0.5.0.
// Сознательно без llama_sampler/common: вручную argmax/temperature -
// меньше зависимостей от смены API между релизами.

// ---- мост нативных логов в UI (диагностика зависаний, playbook 4) ----
static JavaVM*     g_vm = nullptr;
static jclass      g_listenerClass = nullptr;
static jmethodID   g_listenerMethod = nullptr;
static bool        g_log_set = false;

jint JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

static void log_bridge(enum ggml_log_level level, const char* text, void*) {
    (void)level;
    if (!g_vm || !g_listenerClass || !g_listenerMethod || !text) return;
    JNIEnv* env = nullptr;
    bool detach = false;
    if (g_vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        g_vm->AttachCurrentThread(&env, nullptr);
        detach = true;
    }
    if (env) {
        jstring s = env->NewStringUTF(text);
        env->CallStaticVoidMethod(g_listenerClass, g_listenerMethod, s);
        env->DeleteLocalRef(s);
    }
    if (detach) g_vm->DetachCurrentThread();
}

#include <android/log.h>
static void log_ts(const char* phase, long long ms) {
    __android_log_print(ANDROID_LOG_INFO, "OfflineRef", "%s: %lld ms", phase, ms);
}

namespace {

llama_model*   g_model = nullptr;
llama_context* g_ctx   = nullptr;
std::mt19937   g_rng(1234);
bool           g_backend_init = false;

llama_token sample_token(const float* logits, int n_vocab, float temp) {
    if (temp <= 0.0f) {
        return (llama_token)(std::max_element(logits, logits + n_vocab) - logits);
    }
    float mx = *std::max_element(logits, logits + n_vocab);
    std::vector<float> probs((size_t)n_vocab);
    double sum = 0.0;
    for (int i = 0; i < n_vocab; i++) {
        float p = std::exp((logits[i] - mx) / temp);
        probs[(size_t)i] = p;
        sum += p;
    }
    std::uniform_real_distribution<double> dist(0.0, sum);
    double r = dist(g_rng);
    double acc = 0.0;
    for (int i = 0; i < n_vocab; i++) {
        acc += probs[(size_t)i];
        if (r <= acc) return (llama_token)i;
    }
    return (llama_token)(n_vocab - 1);
}

long long clock_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

jstring err(JNIEnv* env, const std::string& msg) {
    return env->NewStringUTF(("ERR: " + msg).c_str());
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_offlineref_LlamaEngine_nativeHello(JNIEnv* env, jclass) {
    return env->NewStringUTF("llamajni.so: загружен, llama.cpp v0.5.0 подключён");
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_offlineref_LlamaEngine_nativeLoadModel(JNIEnv* env, jclass,
                                                jstring jpath, jint n_ctx, jint n_threads) {
    if (!g_backend_init) { llama_backend_init(); g_backend_init = true; }

    // мост логов включаем ОДИН раз, до загрузки - иначе теряем первые строки
    if (!g_log_set) {
        jclass cls = env->FindClass("com/offlineref/LlamaEngine");
        if (cls) {
            g_listenerClass = (jclass)env->NewGlobalRef(cls);
            g_listenerMethod = env->GetStaticMethodID(g_listenerClass, "onNativeLog",
                                                      "(Ljava/lang/String;)V");
            llama_log_set(log_bridge, nullptr);
        }
        g_log_set = true;
    }

    if (g_ctx)   { llama_free(g_ctx);       g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }

    long long t0 = clock_ms();

    const char* path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;   // v1: только CPU (GPU на MediaTek - отдельная история)
    llama_model* model = llama_load_model_from_file(path, mparams);
    env->ReleaseStringUTFChars(jpath, path);
    long long t1 = clock_ms();
    log_ts("load_model_from_file", t1 - t0);
    if (!model) return 0;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = (uint32_t)n_ctx;
    cparams.n_threads       = (int32_t)n_threads;
    cparams.n_threads_batch = (int32_t)n_threads;
    llama_context* ctx = llama_new_context_with_model(model, cparams);
    long long t2 = clock_ms();
    log_ts("new_context_with_model", t2 - t1);
    if (!ctx) { llama_model_free(model); return 0; }

    g_model = model;
    g_ctx   = ctx;
    return (jlong)(intptr_t)ctx;
}

extern "C" JNIEXPORT void JNICALL
Java_com_offlineref_LlamaEngine_nativeUnload(JNIEnv*, jclass) {
    if (g_ctx)   { llama_free(g_ctx);         g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_offlineref_LlamaEngine_nativeGenerate(JNIEnv* env, jclass,
                                               jstring jsystem, jstring juser,
                                               jint max_tokens, jfloat jtemp) {
    if (!g_model || !g_ctx) return err(env, "модель не загружена");

    const llama_vocab* vocab = llama_model_get_vocab(g_model);

    // --- chat template (взят из метаданных GGUF, add_assistant=true) ---
    const char* sys  = env->GetStringUTFChars(jsystem, nullptr);
    const char* user = env->GetStringUTFChars(juser, nullptr);
    std::vector<llama_chat_message> msgs;
    msgs.push_back({"system", sys});
    msgs.push_back({"user", user});

    std::vector<char> tmpl(8192);
    int32_t n = llama_chat_apply_template(nullptr, msgs.data(), msgs.size(),
                                          true, tmpl.data(), (int32_t)tmpl.size());
    if (n < 0) {
        env->ReleaseStringUTFChars(jsystem, sys);
        env->ReleaseStringUTFChars(juser, user);
        return err(env, "chat template вернул ошибку");
    }
    if (n >= (int32_t)tmpl.size()) {
        tmpl.resize((size_t)n + 1);
        n = llama_chat_apply_template(nullptr, msgs.data(), msgs.size(),
                                      true, tmpl.data(), (int32_t)tmpl.size());
        if (n < 0) {
            env->ReleaseStringUTFChars(jsystem, sys);
            env->ReleaseStringUTFChars(juser, user);
            return err(env, "chat template (retry) вернул ошибку");
        }
    }
    std::string prompt(tmpl.data(), (size_t)n);
    env->ReleaseStringUTFChars(jsystem, sys);
    env->ReleaseStringUTFChars(juser, user);

    // --- токенизация: parse_special=true (нужен для <|im_start|> Qwen) ---
    std::vector<llama_token> tokens(8192);
    int32_t n_tok = llama_tokenize(vocab, prompt.c_str(), (int32_t)prompt.size(),
                                   tokens.data(), (int32_t)tokens.size(), false, true);
    if (n_tok < 0) {
        tokens.resize((size_t)(-n_tok) + 8);
        n_tok = llama_tokenize(vocab, prompt.c_str(), (int32_t)prompt.size(),
                               tokens.data(), (int32_t)tokens.size(), false, true);
    }
    if (n_tok <= 0) return err(env, "токенизация не удалась");
    tokens.resize((size_t)n_tok);

    // --- генерация ---
    const int n_vocab = llama_n_vocab(vocab);
    const int max     = max_tokens > 0 ? max_tokens : 256;
    const float temp  = jtemp;
    std::string out;

    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t)tokens.size());
    for (int i = 0; i < max; i++) {
        if (llama_decode(g_ctx, batch) != 0) { out += " [ошибка decode]"; break; }
        const float* logits = llama_get_logits_ith(g_ctx, batch.n_tokens - 1);
        if (!logits) break;
        llama_token next = sample_token(logits, n_vocab, temp);
        if (llama_token_is_eog(vocab, next)) break;
        char buf[64];
        int32_t len = llama_token_to_piece(vocab, next, buf, (int32_t)sizeof(buf), 0, true);
        if (len > 0) out.append(buf, (size_t)len);
        batch = llama_batch_get_one(&next, 1);
    }

    return env->NewStringUTF(out.c_str());
}
