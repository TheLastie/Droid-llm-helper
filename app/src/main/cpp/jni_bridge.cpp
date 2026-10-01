#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <random>
#include <ctime>
#include <string>
#include <vector>

#include "llama.h"

// Tesseract C API (собственная сборка, ветка tess-ndk27)
#include "tesseract/capi.h"

// ШАГ 3b: полный JNI-мост к llama.cpp v0.5.0.
// Сознательно без llama_sampler/common: вручную argmax/temperature -
// меньше зависимостей от смены API между релизами.

// ---- мост нативных логов в UI (диагностика зависаний, playbook 4) ----
static JavaVM*     g_vm = nullptr;
static jclass      g_listenerClass = nullptr;
static jmethodID   g_listenerMethod = nullptr;
static jmethodID   g_tokenMethod = nullptr;
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

// фазовые маркеры в тот же лог-канал, что и логи llama.cpp
static void emit_log(JNIEnv* env, const std::string& msg) {
    if (!env || !g_listenerClass || !g_listenerMethod) return;
    jstring s = env->NewStringUTF(msg.c_str());
    env->CallStaticVoidMethod(g_listenerClass, g_listenerMethod, s);
    env->DeleteLocalRef(s);
}

static void emit_log_anythread(const std::string& msg) {
    if (!g_vm || !g_listenerClass || !g_listenerMethod) return;
    JNIEnv* env = nullptr;
    bool detach = false;
    if (g_vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        g_vm->AttachCurrentThread(&env, nullptr);
        detach = true;
    }
    emit_log(env, msg);
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
    // мост логов включаем ОДИН раз, ДО backend_init, чтобы маркеры фаз работали
    if (!g_log_set) {
        jclass cls = env->FindClass("com/offlineref/LlamaEngine");
        if (cls) {
            g_listenerClass = (jclass)env->NewGlobalRef(cls);
            g_listenerMethod = env->GetStaticMethodID(g_listenerClass, "onNativeLog",
                                                      "(Ljava/lang/String;)V");
            g_tokenMethod = env->GetStaticMethodID(g_listenerClass, "onNativeToken",
                                                   "(Ljava/lang/String;)V");
            llama_log_set(log_bridge, nullptr);
        }
        g_log_set = true;
    }

    if (!g_backend_init) { llama_backend_init(); g_backend_init = true; }
    emit_log(env, "phase: backend init ok");

    if (g_ctx)   { llama_free(g_ctx);       g_ctx   = nullptr; }
    if (g_model) { llama_free_model(g_model); g_model = nullptr; }

    long long t0 = clock_ms();

    const char* path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;   // v1: только CPU (GPU на MediaTek - отдельная история)
    // КЛЮЧЕВОЙ ФИКС (диагноз: decode 0 = буря page faults по холодному mmap
    // на FBE/f2fs: 2.87 ГБ случайных чтений 4 КБ с UFS 2.2 = минуты).
    // Читаем веса в ОЗУ ПРИ ЗАГРУЗКЕ (линейное чтение, ~30-60 с), дальше
    // decode работает с тёплой памятью без единого fault'а.
    mparams.use_mmap = false;
    llama_model* model = llama_load_model_from_file(path, mparams);
    env->ReleaseStringUTFChars(jpath, path);
    long long t1 = clock_ms();
    log_ts("load_model_from_file", t1 - t0);
    emit_log(env, "phase: load_model_from_file ok, " + std::to_string(t1 - t0) + " ms");
    if (!model) return 0;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = (uint32_t)n_ctx;
    // РАЗДЕЛЬНЫЕ ПОТОКИ (замеры на устройстве):
    // - генерация (n_threads): 2 - упирается в память, 4 потока НЕ дают
    //   прироста (230 мс/токен и так, и так), но греют сильнее;
    // - промпт (n_threads_batch): 4 - вычислительно-ёмкий, 25 с -> 14 с.
    cparams.n_threads       = 2;
    cparams.n_threads_batch = (int32_t)n_threads;
    llama_context* ctx = llama_new_context_with_model(model, cparams);
    long long t2 = clock_ms();
    log_ts("new_context_with_model", t2 - t1);
    emit_log(env, "phase: new_context ok, " + std::to_string(t2 - t1) + " ms");
    if (!ctx) { llama_free_model(model); return 0; }

    g_model = model;
    g_ctx   = ctx;
    return (jlong)(intptr_t)ctx;
}

extern "C" JNIEXPORT void JNICALL
Java_com_offlineref_LlamaEngine_nativeUnload(JNIEnv*, jclass) {
    if (g_ctx)   { llama_free(g_ctx);         g_ctx   = nullptr; }
    if (g_model) { llama_free_model(g_model); g_model = nullptr; }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_offlineref_LlamaEngine_nativeGenerate(JNIEnv* env, jclass,
                                               jstring jsystem, jstring juser,
                                               jint max_tokens, jfloat jtemp) {
    if (!g_model || !g_ctx) return err(env, "модель не загружена");
    emit_log(env, "phase: generate entered");


    // --- chat template (взят из метаданных GGUF, add_assistant=true) ---
    const char* sys  = env->GetStringUTFChars(jsystem, nullptr);
    const char* user = env->GetStringUTFChars(juser, nullptr);
    std::vector<llama_chat_message> msgs;
    msgs.push_back({"system", sys});
    msgs.push_back({"user", user});

    std::vector<char> tmpl(8192);
    int32_t n = llama_chat_apply_template(g_model, nullptr, msgs.data(), msgs.size(),
                                          true, tmpl.data(), (int32_t)tmpl.size());
    if (n < 0) {
        env->ReleaseStringUTFChars(jsystem, sys);
        env->ReleaseStringUTFChars(juser, user);
        return err(env, "chat template вернул ошибку");
    }
    if (n >= (int32_t)tmpl.size()) {
        tmpl.resize((size_t)n + 1);
        n = llama_chat_apply_template(g_model, nullptr, msgs.data(), msgs.size(),
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
    emit_log(env, "phase: template ok, " + std::to_string(prompt.size()) + " bytes");

    // --- токенизация: parse_special=true (нужен для <|im_start|> Qwen) ---
    std::vector<llama_token> tokens(8192);
    int32_t n_tok = llama_tokenize(g_model, prompt.c_str(), (int32_t)prompt.size(),
                                   tokens.data(), (int32_t)tokens.size(), false, true);
    if (n_tok < 0) {
        tokens.resize((size_t)(-n_tok) + 8);
        n_tok = llama_tokenize(g_model, prompt.c_str(), (int32_t)prompt.size(),
                               tokens.data(), (int32_t)tokens.size(), false, true);
    }
    if (n_tok <= 0) return err(env, "токенизация не удалась");
    tokens.resize((size_t)n_tok);
    emit_log(env, "phase: tokenized " + std::to_string(n_tok) + " tokens");

    // --- генерация ---
    const int n_vocab = llama_n_vocab(g_model);
    const int max     = max_tokens > 0 ? max_tokens : 256;
    const float temp  = jtemp;
    std::string out;

    // ШТАТНЫЙ sampler-chain llama.cpp: penalties (анти-заикание) + temp + dist.
    // Заменяет ручной argmax/softmax - убирает артефакты "ПрПрП"/"Я ЯЯ",
    // которые давало сэмплирование без штрафа за повторы.
    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(64, 1.10f, 0.00f, 1.05f));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist((uint32_t)clock_ms()));

    llama_token last = 0;   // ВНЕ цикла: batch.token указывает на него
                            // между итерациями (было UB с висячим указателем)
    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t)tokens.size());
    for (int i = 0; i < max; i++) {
        const bool mark = (i < 3) || (i % 32 == 0);
        if (mark) {
            emit_log(env, "phase: decode " + std::to_string(i) + " begin, batch " +
                          std::to_string(batch.n_tokens));
        }
        long long d0 = clock_ms();
        if (llama_decode(g_ctx, batch) != 0) { out += " [ошибка decode]"; break; }
        long long d1 = clock_ms();
        if (mark) {
            emit_log(env, "phase: decode " + std::to_string(i) + " done, " +
                          std::to_string(d1 - d0) + " ms");
        }
        last = llama_sampler_sample(smpl, g_ctx, -1);
        llama_sampler_accept(smpl, last);
        if (llama_token_is_eog(g_model, last)) break;
        char buf[64];
        int32_t len = llama_token_to_piece(g_model, last, buf, (int32_t)sizeof(buf), 0, true);
        if (len > 0) {
            out.append(buf, (size_t)len);
            if (g_tokenMethod && g_listenerClass) {
                jstring piece = env->NewStringUTF(std::string(buf, (size_t)len).c_str());
                env->CallStaticVoidMethod(g_listenerClass, g_tokenMethod, piece);
                env->DeleteLocalRef(piece);
            }
        }
        batch = llama_batch_get_one(&last, 1);
    }
    llama_sampler_free(smpl);

    return env->NewStringUTF(out.c_str());
}

// ---------- OCR (tesseract capi) ----------
extern "C" JNIEXPORT jlong JNICALL
Java_com_offlineref_TessApi_nativeTessInit(JNIEnv* env, jclass, jstring jpath, jstring jlang) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    const char* lang = env->GetStringUTFChars(jlang, nullptr);
    TessResultCode rc = TessBaseAPIInit3(TessBaseAPICreate(), path, lang);
    env->ReleaseStringUTFChars(jpath, path);
    env->ReleaseStringUTFChars(jlang, lang);
    return (jlong)(intptr_t)rc; // TessResultCode - это указатель на TessBaseAPI
}

extern "C" JNIEXPORT void JNICALL
Java_com_offlineref_TessApi_nativeTessSetImage(JNIEnv* env, jclass, jlong handle,
                                               jbyteArray jpix, jint w, jint h) {
    TessResultCode api = (TessResultCode)(intptr_t)handle;
    jbyte* px = env->GetByteArrayElements(jpix, nullptr);
    TessBaseAPISetImage(api, (const unsigned char*)px, w, h, 4, w * 4);
    env->ReleaseByteArrayElements(jpix, px, JNI_ABORT);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_offlineref_TessApi_nativeTessGetText(JNIEnv* env, jclass, jlong handle) {
    TessResultCode api = (TessResultCode)(intptr_t)handle;
    char* txt = TessBaseAPIGetUTF8Text(api);
    jstring out = txt ? env->NewStringUTF(txt) : env->NewStringUTF("");
    if (txt) TessDeleteText(txt);
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_com_offlineref_TessApi_nativeTessEnd(JNIEnv*, jclass, jlong handle) {
    TessResultCode api = (TessResultCode)(intptr_t)handle;
    TessBaseAPIEnd(api);
    TessBaseAPIDelete(api);
}

