package com.offlineref

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

// ШАГ 2: управление моделью. Единственный компонент с сетью.
// Качает GGUF по HTTPS, считает sha256 ПОКА пишет на диск
// (4,7 ГБ дважды читать на телефоне - больно), атомарно
// переименовывает .part -> готовый файл: битый файл не должен
// выглядеть готовым (правило 3 playbook: хэшируем каждую сборку).

class ModelManager(private val context: Context) {

    enum class State { READY, MISSING, HASH_MISMATCH }

    val modelFile: File get() = File(context.filesDir, "models/$MODEL_NAME")

    fun state(): State {
        if (!modelFile.exists()) return State.MISSING
        // Хэш ещё не внесён в код - верификация начнётся со следующего коммита
        if (EXPECTED_SHA256.isBlank()) return State.READY
        return if (sha256(modelFile) == EXPECTED_SHA256.lowercase())
            State.READY else State.HASH_MISMATCH
    }

    // Возвращает фактический sha256 скачанного файла
    fun download(onProgress: (done: Long, total: Long) -> Unit): String {
        modelFile.parentFile?.mkdirs()
        val tmp = File(modelFile.parentFile, "$MODEL_NAME.part")
        tmp.delete()

        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        conn.connect()

        val total = conn.contentLengthLong   // у LFS-файлов HF размер известен
        val digest = MessageDigest.getInstance("SHA-256")
        var done = 0L

        conn.inputStream.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(1024 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    done += n
                    onProgress(done, total)
                }
                out.flush()
            }
        }
        conn.disconnect()

        val got = digest.digest().joinToString("") { "%02x".format(it) }
        if (EXPECTED_SHA256.isNotBlank() && got != EXPECTED_SHA256.lowercase()) {
            tmp.delete()   // битое скачивание не оставляем на диске
            throw SecurityException("sha256 модели не совпал: $got")
        }
        if (!tmp.renameTo(modelFile)) {
            tmp.delete()
            throw IllegalStateException("не удалось переименовать .part в готовый файл")
        }
        return got
    }

    fun sha256(f: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(4 * 1024 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MODEL_NAME = "qwen2.5-7b-instruct-q4_k_m.gguf"
        const val MODEL_URL =
            "https://huggingface.co/Qwen/Qwen2.5-7B-Instruct-GGUF/resolve/main/qwen2.5-7b-instruct-q4_k_m.gguf"
        // ПУСТО = ждём первого скачивания на устройстве: приложение покажет
        // фактический хэш, мы впишем его сюда - и включится жёсткая проверка.
        const val EXPECTED_SHA256 = ""
    }
}
