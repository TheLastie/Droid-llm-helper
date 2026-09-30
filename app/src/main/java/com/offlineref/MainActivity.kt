package com.offlineref

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

// ШАГ 1 (бисекционная сборка "B1 OK" по правилу 4.5 playbook):
// доказывает, что весь конфигурационный контур (Kotlin-плагин, JVM 17,
// signing, manifest) работает. Дальше добавляем слои по одному.

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            buildUi()
            step("B1: UI создан")
            step("Устройство: ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            step("ABI: ${Build.SUPPORTED_ABIS?.joinToString()}")
            checkSigning()
            step("B1 OK")
        } catch (t: Throwable) {
            // showFatal: приложение НЕ падает молча, а показывает traceback
            // и предлагает отправить его одним тапом (правило 4.2 playbook)
            showFatal(t)
        }
    }

    private fun buildUi() {
        status = TextView(this).apply {
            textSize = 16f
            setPadding(48, 48, 48, 48)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
        }
        setContentView(ScrollView(this).apply { addView(box) })
    }

    private fun checkSigning() {
        // Косвенная проверка, что keystore подхватился: если бы подписи не было,
        // система бы не установила APK вообще. Здесь лишь фиксируем факт запуска.
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
