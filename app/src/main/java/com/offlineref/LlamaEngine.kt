package com.offlineref

// JNI-мост к llama.cpp v0.5.0 (CPU-only).
// Ошибки нативной стороны возвращаются строками с префиксом "ERR:" -
// нативный код не бросает исключения в JVM (проще диагностика в поле).

object LlamaEngine {
    init {
        System.loadLibrary("llamajni")
    }

    external fun nativeHello(): String

    // 0 = не удалось загрузить (путь неверный, нехватка памяти, битый GGUF)
    external fun nativeLoadModel(path: String, nCtx: Int, nThreads: Int): Long

    // "ERR: ..." при ошибке, иначе сгенерированный текст
    external fun nativeGenerate(systemPrompt: String, userPrompt: String,
                                maxTokens: Int, temp: Float): String

    external fun nativeUnload()

    // ---- приём нативных логов llama.cpp (вызывается из C++ по JNI) ----
    @Volatile
    var logSink: ((String) -> Unit)? = null

    @JvmStatic
    fun onNativeLog(line: String) {
        logSink?.invoke(line.trim().trimEnd('\n'))
    }
}
