package com.offlineref

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

// ШАГ 4: настоящий чат. Модель держим в памяти между вопросами.
// v1 без стриминга: "думаю..." -> готовый ответ со временем.
// v1 без памяти диалога: каждый вопрос независим (контекст 2048 токенов
// экономим под будущую базу знаний). Это осознанное ограничение.

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var progressLine: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var buttonDownload: Button
    private lateinit var chatScroll: ScrollView
    private lateinit var chatBox: LinearLayout
    private lateinit var input: EditText
    private lateinit var buttonSend: Button
    private lateinit var buttonKb: Button
    private lateinit var verbatimCheck: CheckBox

    private val mm by lazy { ModelManager(this) }

    init {
        // нативные логи llama.cpp -> серые строки в чате (диагностика в поле).
        // Фильтр: фаза sched_reserve печатает СОТНИ строк llama_graph_n_input_tensors
        // (шум про ROPE-узлы) - в поле они бесполезны, режем.
        LlamaEngine.logSink = { line ->
            if (line.isNotEmpty() && !line.startsWith("llama_graph_n_input_tensors")) {
                logBubble(line)
            }
        }
    }
    @Volatile private var modelHandle: Long = 0L
    @Volatile private var generating = false
    @Volatile private var gotFirstToken = false

    // Компактен намеренно: русский токенизируется ~2-3 символа/токен,
    // каждый десяток токенов промпта = ~2 с ожидания на этом чипе.
    // Усилен запрет на другие языки: Qwen2.5-3B мультиязычна и на неуверенных
    // ответах съезжает в китайский (наблюдено в поле).
    private val systemPrompt = "Ты офлайн-справочник. Отвечай кратко (до 6 предложений), " +
            "простым языком, ТОЛЬКО на русском языке - не используй другие языки " +
            "и иероглифы ни при каких обстоятельствах. Если не знаешь - так и скажи. " +
            "По вопросам здоровья/безопасности напомни, что это не замена специалисту."

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            buildUi()
            step("OfflineRef v${BuildConfig.VERSION_NAME} (llama.cpp b4353, CPU)")
            step("Устройство: ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            LlamaEngine.nativeHello()
            step("B3: JNI + llama.cpp OK")
            Thread { mm.cleanupStaleModels() }.start()
            when (mm.state()) {
                ModelManager.State.READY -> {
                    step("Модель: на месте (${ModelManager.MODEL_NAME})")
                    showChat()
                    runCpuBench()
                    // Диагностика БД при старте: если базу снова снесёт,
                    // увидим путь/размер/счётчики в первом же скрине
                    Thread {
                        try {
                            val kb = KbDb.get(this@MainActivity)
                            // предзагрузка зашитой в APK базы (если БД пуста)
                            if (kb.preloadFromAssets())
                                logBubble("база предзагружена из APK (первый запуск)")
                            val f = getDatabasePath("kb")
                            logBubble("база: " + f.absolutePath + ", " + f.length() + " байт, " + kb.stats())
                        } catch (t: Throwable) {
                            logBubble("база: ошибка диагностики " + (t.message ?: t.javaClass.simpleName))
                        }
                    }.start()
                    if (ModelManager.EXPECTED_SHA256.isBlank()) {
                        // Эталон ещё не вписан в код: считаем хэш и показываем,
                        // чтобы пользователь прислал его разработчику.
                        Thread {
                            val h = mm.sha256(mm.modelFile)
                            logBubble("модель sha256: " + h + " - пришлите разработчику")
                        }.start()
                    }
                }
                ModelManager.State.MISSING -> {
                    step("Модель: не найдена (${ModelManager.MODEL_NAME}, ~3 ГБ, один раз по сети)")
                    showDownloadButton()
                }
                ModelManager.State.HASH_MISMATCH -> {
                    step("Модель: файл есть, но sha256 НЕ совпал - скачайте заново")
                    showDownloadButton()
                }
            }
        } catch (t: Throwable) {
            showFatal(t)
        }
    }

    // ---------- UI ----------

    private fun buildUi() {
        status = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(48, 32, 48, 8)
        }
        progressLine = TextView(this).apply {
            textSize = 14f
            setPadding(48, 8, 48, 0)
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            isVisible = false
            setPadding(48, 8, 48, 8)
        }
        buttonDownload = Button(this).apply {
            text = "Скачать модель"
            isVisible = false
            setPadding(48, 8, 48, 8)
        }
        chatBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chatScroll = ScrollView(this).apply {
            isVisible = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(chatBox)
        }
        input = EditText(this).apply {
            hint = "Спросить справочник..."
            isVisible = false
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        buttonSend = Button(this).apply {
            text = "Отправить"
            isVisible = false
            setOnClickListener { onSend() }
        }
        buttonKb = Button(this).apply {
            text = "База знаний"
            isVisible = false
            setOnClickListener { startActivity(Intent(this@MainActivity, KnowledgeActivity::class.java)) }
        }
        verbatimCheck = CheckBox(this).apply {
            text = "Дословно (текст из базы без модели, мгновенно)"
            isVisible = false
            setPadding(48, 0, 48, 0)
        }
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(32, 8, 32, 24)
            addView(input)
            addView(buttonSend)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(progressLine)
            addView(progressBar)
            addView(buttonDownload)
            addView(buttonKb)
            addView(verbatimCheck)
            addView(chatScroll)
            addView(inputRow)
        }
        setContentView(root)
    }

    private fun showChat() {
        chatScroll.isVisible = true
        input.isVisible = true
        buttonSend.isVisible = true
        buttonKb.isVisible = true
        verbatimCheck.isVisible = true
        bubble("OfflineRef готов. Работаю полностью офлайн. " +
                "Задайте вопрос - ответ до ~60 секунд.", assistant = true)
    }

    // Кнопка "📷 стр. N" -> страница книги на весь экран
    private fun addPageButton(docTitle: String, pageNo: Int, imgPath: String) {
        runOnUiThread {
            val btn = Button(this).apply {
                text = "📷 " + docTitle + ", стр. " + pageNo + " (показать страницу)"
                setOnClickListener { showPageImage(imgPath) }
            }
            chatBox.addView(btn)
            chatScroll.post { chatScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun showPageImage(imgPath: String) {
        val bmp = android.graphics.BitmapFactory.decodeFile(imgPath) ?: return
        val dialog = android.app.Dialog(this)
        val img = android.widget.ImageView(this).apply {
            setImageBitmap(bmp)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFF000000.toInt())
            setOnClickListener { dialog.dismiss() }
        }
        dialog.setContentView(img)
        dialog.window?.setLayout(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT)
        dialog.show()
    }

    private fun logBubble(text: String) {
        // ВСЕГДА с UI-потока: вызывается и из logSink, и из рабочих потоков.
        // Прямой addView с чужого потока = CalledFromWrongThreadException
        // на следующей отрисовке (краш ~1 с после старта, опыт v0.10.0).
        runOnUiThread {
            val tv = TextView(this).apply {
                this.text = text
                textSize = 11f
                setTextColor(Color.GRAY)
                setPadding(32, 4, 32, 4)
            }
            chatBox.addView(tv)
            chatScroll.post { chatScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun bubble(text: String, assistant: Boolean): TextView {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(Color.BLACK)
            setBackgroundColor(if (assistant) 0xFFF5F5F5.toInt() else 0xFFE3F2FD.toInt())
            setPadding(32, 20, 32, 20)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(24, 8, 24, 8)
            layoutParams = lp
        }
        runOnUiThread {
            chatBox.addView(tv)
            chatScroll.post { chatScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
        return tv
    }

    // Быстрый тест здоровья CPU: 2 секунты однопоточного целочисленного
    // цикла. Ориентир для mid-range 2024+: 1.5-3.0 (млрд итераций).
    // Значительно ниже = частоты зажаты (энергосбережение/троттлинг/режим сна).
    // Дословный режим: поиск + вывод сырого текста, LLM не участвует
    private fun onVerbatim(q: String) {
        Thread {
            val kb = KbDb.get(this@MainActivity)
            logBubble("дословно: документов в базе = " + kb.countDocs())
            val chunks = kb.searchSafe(q, 3)
            if (chunks.isEmpty()) {
                runOnUiThread {
                    bubble("OfflineRef: в базе нет текста по этому запросу", assistant = true)
                }
                return@Thread
            }
            logBubble("дословно: найдено фрагментов = " + chunks.size)
            runOnUiThread {
                for (ch in chunks) {
                    bubble("📄 " + ch.docTitle + ", фрагмент " + (ch.ordinal + 1) +
                            " из " + ch.total +
                            (if (ch.pageNo > 0) ", стр. " + ch.pageNo else "") +
                            ":\n\n" + ch.text.trim(), assistant = true)
                    if (ch.imgPath.isNotEmpty() && ch.pageNo > 0) {
                        addPageButton(ch.docTitle, ch.pageNo, ch.imgPath)
                    }
                }
            }
        }.start()
    }

    private fun runCpuBench() {
        Thread {
          try {
            val t0 = System.nanoTime()
            var x = 123456789L
            var iters = 0L
            val deadline = t0 + 2_000_000_000L
            while (true) {
                var k = 0
                while (k < 1_000_000) {
                    x = x * 1103515245L + 12345L
                    if (x < 0) x = -x
                    k++
                }
                iters += 1_000_000
                if (System.nanoTime() >= deadline) break
            }
            logBubble("CPU bench: %.2f Г-итераций/2с (интерпретатор; норма 0.1-0.5)".format(iters / 1e9))
          } catch (t: Throwable) {
            logBubble("CPU bench failed: " + (t.message ?: t.javaClass.simpleName))
          }
        }.start()
    }

    // RAG-режим: ответ СТРОГО по найденным фрагментам (антиигаллюцинации)
    private val RAG_SYSTEM = "Ты офлайн-справочник. Ответь, используя ТОЛЬКО текст " +
            "раздела [ИСТОЧНИКИ]. Если ответа там нет - скажи: в базе нет данных " +
            "по этому вопросу. Если опираешься на конкретное место - процитируй " +
            "его дословно в кавычках и укажи номер источника [1] или [2]. " +
            "Кратко, до 5 предложений, ТОЛЬКО на русском (без других языков)."

    private fun buildRagUser(question: String, chunks: List<KbDb.Chunk>): String {
        val sb = StringBuilder("[ИСТОЧНИКИ]\n")
        var budget = 1800   // знаков ~ лимит промпта под 60-секундный бюджет
        chunks.forEachIndexed { i, ch ->
            // усечение до остатка бюджета вместо молчаливого пропуска -
            // иначе длинные фрагменты исчезали из промпта целиком
            var t = ch.text
            if (t.length > budget) t = t.take(budget) + " …"
            budget -= t.length
            if (budget <= 0) return@forEachIndexed
            sb.append(i + 1).append(". (").append(ch.docTitle).append(")\n")
                .append(t).append("\n\n")
        }
        sb.append("ВОПРОС: ").append(question)
        return sb.toString()
    }

    // ---------- Чат ----------

    private fun onSend() {
        var q = input.text.toString().trim()
        if (q.isEmpty() || generating) return
        // Режим "Дословно": галочка или префикс "!" - показываем сырой текст
        // из базы БЕЗ модели: мгновенно, без галлюцинаций, без лимита 60 с
        if (verbatimCheck.isChecked || q.startsWith("!")) {
            if (q.startsWith("!")) q = q.drop(1).trim()
            if (q.isEmpty()) return
            bubble("Вы: " + q + "  [дословно]", assistant = false)
            onVerbatim(q)
            return
        }
        input.setText("")
        bubble("Вы: $q", assistant = false)
        generating = true
        buttonSend.isEnabled = false
        input.isEnabled = false

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "offlineref:chat")
        wake.acquire(10 * 60 * 1000L)
        Thread {
            val thinking = bubble("OfflineRef: думаю...", assistant = true)
            try {
                if (modelHandle == 0L) {
                    runOnUiThread { thinking.text = "OfflineRef: читаю модель в память (до ~минуты, ~2 ГБ)..." }
                    modelHandle = LlamaEngine.nativeLoadModel(
                        mm.modelFile.absolutePath, 2048, threads())
                    if (modelHandle == 0L) {
                        runOnUiThread { thinking.text = "OfflineRef: не смог загрузить модель " +
                                "(файл повреждён или не хватило памяти)" }
                        return@Thread
                    }
                }
                // Поиск по базе знаний: найдено -> отвечаем СТРОГО по тексту,
                // ничего не найдено -> обычный режим (fallback, решение №5)
                // Диагностика видна в чате: почему поиск не сработал - очевидно.
                val kb = KbDb.get(this@MainActivity)
                logBubble("поиск: документов в базе = " + kb.countDocs())
                val chunks = kb.searchSafe(q, 2)
                if (chunks.isEmpty())
                    logBubble("поиск: совпадений нет -> общий режим")
                else
                    logBubble("поиск: фрагментов = " + chunks.size + ", документы: " +
                            chunks.map { it.docTitle }.distinct().joinToString(", "))
                val useRag = chunks.isNotEmpty()
                val sysForGen = if (useRag) RAG_SYSTEM else systemPrompt
                val userForGen = if (useRag) buildRagUser(q, chunks) else q
                val maxForGen = if (useRag) 150 else 200

                val t0 = System.currentTimeMillis()
                val sb = StringBuilder()
                gotFirstToken = false
                // секундомер: пока нет ни одного токена, раз в секунду
                // показываем elapsed - отличие "зависло" от "медленно работает"
                val ticker = object : Runnable {
                    override fun run() {
                        if (!generating || gotFirstToken) return
                        val el = (System.currentTimeMillis() - t0) / 1000
                        thinking.text = "OfflineRef: думаю... (" + el + " с)"
                        thinking.postDelayed(this, 1000)
                    }
                }
                thinking.post(ticker)
                // стриминг: каждый токен дописываем в пузырь сразу
                LlamaEngine.tokenSink = { piece ->
                    gotFirstToken = true
                    sb.append(piece)
                    runOnUiThread { thinking.text = "OfflineRef: " + sb.toString() }
                }
                val ans = LlamaEngine.nativeGenerate(sysForGen, userForGen, maxForGen, 0.1f)
                LlamaEngine.tokenSink = null
                val dt = (System.currentTimeMillis() - t0) / 1000
                val finalText = if (ans.startsWith("ERR:"))
                    "OfflineRef: ошибка генерации $ans"
                else {
                    val src = if (useRag)
                        chunks.map { it.docTitle + " (фрагмент " + (it.ordinal + 1) + "/" + it.total + ")" }
                            .distinct().joinToString(", ")
                    else null
                    "OfflineRef: " + sb.toString() +
                            (if (src != null) "\n\nИсточники: " + src else "\n\n(общие знания модели)") +
                            "\n(" + dt + " с)"
                }
                runOnUiThread {
                    thinking.text = finalText
                    if (useRag) {
                        val seen = mutableSetOf<String>()
                        for (ch in chunks) {
                            if (ch.imgPath.isNotEmpty() && ch.pageNo > 0 &&
                                seen.add(ch.docTitle + ch.pageNo)) {
                                addPageButton(ch.docTitle, ch.pageNo, ch.imgPath)
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                runOnUiThread { thinking.text = "OfflineRef: исключение " + (t.message ?: t.javaClass.simpleName) }
            } finally {
                wake.release()
                runOnUiThread {
                    generating = false
                    buttonSend.isEnabled = true
                    input.isEnabled = true
                }
            }
        }.start()
    }

    // ---------- Скачивание модели (как в шаге 2) ----------

    private fun showDownloadButton() {
        progressBar.isVisible = true
        buttonDownload.isVisible = true
        buttonDownload.setOnClickListener {
            buttonDownload.isEnabled = false
            buttonDownload.text = "Качаю... не закрывайте приложение"
            startDownload()
        }
    }

    private fun startDownload() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "offlineref:download")
        wake.acquire(4 * 60 * 60 * 1000L)
        Thread {
            var lastPct = -1
            try {
                val hash = mm.download { done, total ->
                    val pct = if (total > 0) (done * 100 / total).toInt() else -1
                    if (pct != lastPct) {
                        lastPct = pct
                        val mbTotal = if (total > 0) "${total / 1048576}" else "?"
                        runOnUiThread {
                            progressBar.progress = if (pct >= 0) pct * 10 else 0
                            progressLine.text = "${done / 1048576} / $mbTotal МБ" +
                                    if (pct >= 0) " ($pct%)" else ""
                        }
                    }
                }
                runOnUiThread {
                    if (ModelManager.EXPECTED_SHA256.isBlank())
                        logBubble("модель sha256: " + hash + " - пришлите разработчику")
                    step("Модель скачана и проверена (sha256 совпал с эталоном)")
                    buttonDownload.isVisible = false
                    progressBar.isVisible = false
                    progressLine.text = ""
                    showChat()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    buttonDownload.isEnabled = true
                    buttonDownload.text = "Повторить скачивание"
                    step("Ошибка скачивания: ${t.message ?: t.javaClass.simpleName}")
                }
            } finally {
                wake.release()
            }
        }.start()
    }

    // ---------- Служебное ----------

    // 4 потока: прежние эксперименты с числом потоков были испорчены -O0
    // (весь ggml без оптимизации). С -O3 4 потока ускоряют промпт и генерацию.
    private fun threads() = 4

    override fun onDestroy() {
        try { LlamaEngine.nativeUnload() } catch (_: Throwable) { }
        super.onDestroy()
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
