package com.offlineref

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.core.view.isVisible
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

// ШАГ 1+2 (бисекция по playbook): B1 - конфигурация, B2 - скачивание модели.
// Каждый следующий слой добавляем отдельным шагом, чтобы падение
// локализовалось одной сборкой.

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var progressLine: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var button: Button
    private lateinit var buttonTest: Button
    private val mm by lazy { ModelManager(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            buildUi()
            step("B1: UI создан")
            step("Устройство: ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            step("ABI: ${Build.SUPPORTED_ABIS?.joinToString()}")
            checkSigning()
            step("B3: JNI -> " + LlamaEngine.nativeHello())
            when (mm.state()) {
                ModelManager.State.READY -> {
                    step("Модель: на месте (${ModelManager.MODEL_NAME})")
                    step("B2 OK")
                    showTestButton()
                }
                ModelManager.State.MISSING -> {
                    step("Модель: не найдена (${ModelManager.MODEL_NAME}, ~3 ГБ, один раз по сети)")
                    showDownloadButton()
                }
                ModelManager.State.HASH_MISMATCH -> {
                    step("Модель: файл есть, но sha256 НЕ совпал - файл повреждён, скачайте заново")
                    showDownloadButton()
                }
            }
        } catch (t: Throwable) {
            showFatal(t)
        }
    }

    private fun buildUi() {
        status = TextView(this).apply {
            textSize = 16f
            setPadding(48, 48, 48, 24)
        }
        progressLine = TextView(this).apply {
            textSize = 14f
            setPadding(48, 0, 48, 0)
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            isVisible = false
            setPadding(48, 8, 48, 8)
        }
        button = Button(this).apply {
            text = "Скачать модель"
            isVisible = false
            setPadding(48, 8, 48, 8)
        }
        buttonTest = Button(this).apply {
            text = "Тест модели (загрузка ~3 ГБ + генерация)"
            isVisible = false
            setPadding(48, 8, 48, 8)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(progressLine)
            addView(progressBar)
            addView(button)
            addView(buttonTest)
        }
        setContentView(ScrollView(this).apply { addView(box) })
    }

    private fun showDownloadButton() {
        progressBar.isVisible = true
        button.isVisible = true
        button.setOnClickListener {
            button.isEnabled = false
            button.text = "Качаю... не закрывайте приложение"
            startDownload()
        }
    }

    private fun showTestButton() {
        buttonTest.isVisible = true
        buttonTest.setOnClickListener {
            buttonTest.isEnabled = false
            runModelTest()
        }
    }

    private fun runModelTest() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "offlineref:test")
        wake.acquire(10 * 60 * 1000L)
        Thread {
            try {
                step("Загрузка модели в память (~3 ГБ, до пары минут)...")
                val handle = LlamaEngine.nativeLoadModel(
                    mm.modelFile.absolutePath, 2048, threads())
                if (handle == 0L) {
                    step("ОШИБКА: nativeLoadModel вернул 0 (файл битый или не хватило памяти)")
                    return@Thread
                }
                step("Модель в памяти. Генерация тестового ответа...")
                val t0 = System.currentTimeMillis()
                val ans = LlamaEngine.nativeGenerate(
                    "Ты - тестовый ассистент. Отвечай кратко и только по делу.",
                    "Сколько будет 2+2? Ответь одним словом.",
                    32, 0.0f)
                val dt = (System.currentTimeMillis() - t0) / 1000
                LlamaEngine.nativeUnload()
                if (ans.startsWith("ERR:")) {
                    step("Ошибка генерации: $ans")
                } else {
                    step("Ответ модели за $dt с: $ans")
                    step("B3b OK")
                }
            } catch (t: Throwable) {
                step("Исключение теста: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                wake.release()
                runOnUiThread { buttonTest.isEnabled = true }
            }
        }.start()
    }

    private fun threads() =
        Runtime.getRuntime().availableProcessors().coerceIn(2, 8)

    private fun startDownload() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "offlineref:download")
        wake.acquire(4 * 60 * 60 * 1000L)   // до 4 часов на 4,7 ГБ
        Thread {
            var lastPct = -1
            try {
                val hash = mm.download { done, total ->
                    val pct = if (total > 0) (done * 100 / total).toInt() else -1
                    if (pct != lastPct) {
                        lastPct = pct
                        val mbDone = done / 1048576
                        val mbTotal = if (total > 0) "${total / 1048576}" else "?"
                        runOnUiThread {
                            progressBar.progress = if (pct >= 0) pct * 10 else 0
                            progressLine.text = "$mbDone / $mbTotal МБ" + if (pct >= 0) " ($pct%)" else ""
                        }
                    }
                }
                runOnUiThread {
                    step("Модель скачана и проверена (sha256 совпал с эталоном)")
                    step("B2 OK")
                    button.isVisible = false
                    progressBar.isVisible = false
                    progressLine.text = ""
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    button.isEnabled = true
                    button.text = "Повторить скачивание"
                    step("Ошибка скачивания: ${t.message ?: t.javaClass.simpleName}")
                }
            } finally {
                wake.release()
            }
        }.start()
    }

    private fun checkSigning() {
        val sigs = packageManager.getPackageInfo(packageName, 64).signatures
        step("Подписей в APK: ${sigs?.size ?: 0}")
    }

    private fun step(msg: String) {
        runOnUiThread { status.append("$msg\n") }
    }

    private fun showFatal(t: Throwable) {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        val trace = sw.toString()
        try {
            File(getExternalFilesDir(null), "fatal.log").writeText(trace)
        } catch (_: Throwable) { }
        val view = TextView(this).apply {
            textSize = 13f
            setPadding(48, 48, 48, 48)
            text = "КРИТИЧЕСКАЯ ОШИБКА\n\n$trace\n—\nНажмите, чтобы отправить лог разработчику"
            setOnClickListener {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "OfflineRef fatal log v${BuildConfig.VERSION_NAME}")
                    putExtra(Intent.EXTRA_TEXT, trace)
                }
                startActivity(Intent.createChooser(send, "Отправить лог"))
            }
        }
        setContentView(ScrollView(this).apply { addView(view) })
    }
}
