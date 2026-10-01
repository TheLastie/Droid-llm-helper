package com.offlineref

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

// ШАГ 2.1: управление моделью. Единственный компонент с сетью.
// История боли: HF вернул 404 на q4_k_m, а Android сообщил об этом
// FileNotFoundException с URL в message - диагностика была бесполезна.
// Теперь: коды HTTP читаем явно, докачка через Range, хэш сверяется
// с эталоном (правило 3 playbook - хэшируем всё).

class ModelManager(private val context: Context) {

    enum class State { READY, MISSING, HASH_MISMATCH }

    // Файл ищем в двух местах: внутреннее хранилище и внешнее
    // приложение-специфичное (/sdcard/Android/data/com.offlineref/files/models).
    // Второе - чтобы пользователь мог ВРУЧНУЮ скопировать файл, скачанный
    // браузером, без второй траты трафика.
    private fun primary() = File(context.filesDir, "models/$MODEL_NAME")
    private fun externalCandidate() =
        context.getExternalFilesDir(null)?.let { File(it, "models/$MODEL_NAME") }
    private fun existing() = listOfNotNull(primary(), externalCandidate())
        .firstOrNull { it.exists() }

    val modelFile: File get() = existing() ?: primary()

    fun state(): State {
        val f = existing() ?: return State.MISSING
        if (EXPECTED_SHA256.isBlank()) return State.READY
        return if (sha256(f) == EXPECTED_SHA256.lowercase())
            State.READY else State.HASH_MISMATCH
    }

    // Возвращает фактический sha256 готового файла. Умеет докачивать .part.
    fun download(onProgress: (done: Long, total: Long) -> Unit): String {
        val target = primary().also { it.parentFile?.mkdirs() }
        val tmp = File(target.parentFile, "$MODEL_NAME.part")
        var resumeAt = if (tmp.exists()) tmp.length() else 0L

        while (true) {
            val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            if (resumeAt > 0) conn.setRequestProperty("Range", "bytes=$resumeAt-")
            conn.connect()
            val code = conn.responseCode

            when {
                // Диапазон за пределами файла: .part, похоже, уже полный - проверим
                code == 416 && tmp.exists() -> {
                    conn.disconnect()
                    val h = sha256(tmp)
                    if (h == EXPECTED_SHA256.lowercase()) {
                        if (!tmp.renameTo(target)) throw IllegalStateException("rename failed")
                        return h
                    }
                    tmp.delete(); resumeAt = 0; continue
                }
                // Сервер не поддерживает докачку - начинаем с нуля
                resumeAt > 0 && code == 200 -> {
                    conn.disconnect(); tmp.delete(); resumeAt = 0; continue
                }
                code != 200 && code != 206 -> {
                    conn.disconnect()
                    throw IOException(
                        "HTTP $code: ${conn.responseMessage ?: "?"} (файл: $MODEL_NAME)")
                }
            }

            val resumed = code == 206
            val total = conn.contentLengthLong.takeIf { it > 0 }
                ?.plus(if (resumed) resumeAt else 0) ?: -1
            val digest = MessageDigest.getInstance("SHA-256")
            var done = if (resumed) resumeAt else 0L

            conn.inputStream.use { input ->
                (if (resumed) FileOutputStream(tmp, true) else tmp.outputStream()).use { out ->
                    val buf = ByteArray(1024 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (!resumed) digest.update(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                    out.flush()
                }
            }
            conn.disconnect()

            // При докачке потоковый дайджест невалиден (старые байты не хэшировали)
            val got = if (resumed) sha256(tmp)
                      else digest.digest().joinToString("") { "%02x".format(it) }
            if (EXPECTED_SHA256.isNotBlank() && got != EXPECTED_SHA256.lowercase()) {
                tmp.delete()
                throw SecurityException("sha256 не совпал: $got")
            }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw IllegalStateException("не удалось переименовать .part")
            }
            return got
        }
    }

    // Удаляет устаревшие GGUF в папке models (например, старую 7B после
    // смены модели) - иначе молча съедают гигабайты.
    fun cleanupStaleModels() {
        try {
            val dir = primary().parentFile ?: return
            if (!dir.isDirectory) return
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".gguf") &&
                    f.name != MODEL_NAME && !f.name.endsWith(".part")) {
                    f.delete()
                }
            }
        } catch (_: Throwable) { }
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
        // Исследование (замеры на Nothing Phone 2a): 7B не укладывается
        // в 60 с на Dimensity 7200 (даже рабочий 7B ~2 ток/с). Взята 3B:
        // промпт за секунды, генерация ~4-6 ток/с (Alibaba/академ. замеры).
        const val MODEL_NAME = "qwen2.5-3b-instruct-q4_k_m.gguf"
        const val MODEL_URL =
            "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf"
        // Эталон зафиксирован со скриншота пользователя (v0.13.2)
        const val EXPECTED_SHA256 =
            "626b4a6678b86442240e33df819e00132d3ba7dddfe1cdc4fbb18e0a9615c62d"
    }
}
