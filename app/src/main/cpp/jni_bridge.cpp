#include <jni.h>
#include <string>

// ШАГ 3a: заглушка. Шаг 3b заменит тело на вызовы llama.cpp
// (load_model, generate, token callbacks).

extern "C" JNIEXPORT jstring JNICALL
Java_com_offlineref_LlamaEngine_nativeHello(JNIEnv* env, jclass /* clazz */) {
    std::string s = "llamajni.so: загружен, JNI-мост жив";
    return env->NewStringUTF(s.c_str());
}
