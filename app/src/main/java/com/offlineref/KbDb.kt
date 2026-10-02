package com.offlineref

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri

// БАЗА ЗНАНИЙ v1: SQLite + FTS5 (unicode61).
// Ограничение v1 (осознанное): unicode61 не склоняет русские слова -
// "аптечка" не найдёт "аптечке". Лечится в v2 эмбеддингами (гибридный поиск).
// Размер фрагмента ~500-600 знаков НЕ случаен: промпт на этом чипе
// стоит ~160 мс/токен, русский ~2.5 знака/токен -> большой контекст
// не влезает в 60-секундный бюджет.

class KbDb private constructor(private val appContext: Context) :
    SQLiteOpenHelper(appContext, "kb", null, 3) {

    data class Chunk(val docTitle: String, val text: String,
                     val ordinal: Int = 0, val total: Int = 0,
                     val pageNo: Int = 0, val imgPath: String = "")

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE documents(id INTEGER PRIMARY KEY, title TEXT, added INTEGER)")
        db.execSQL("CREATE TABLE chunks(id INTEGER PRIMARY KEY, doc_id INTEGER, ordinal INTEGER, text TEXT)")
        // FTS5 НЕ используем: модуль отсутствует на части прошивок
        // (Nothing Phone 2a, Android 16 - "no such module: fts5").
        // Поиск - скан по подстрокам в Kotlin (см. search()).
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        // v1->v2: удаляем FTS-таблицу, если она создавалась на прошивке с fts5
        try { db.execSQL("DROP TABLE IF EXISTS chunks_fts") } catch (_: Throwable) { }
        // v2->v3: позиция страницы и путь к изображению страницы (B19)
        if (oldV < 3) {
            try { db.execSQL("ALTER TABLE chunks ADD COLUMN page_no INTEGER DEFAULT 0") } catch (_: Throwable) { }
            try { db.execSQL("ALTER TABLE chunks ADD COLUMN img_path TEXT DEFAULT ''") } catch (_: Throwable) { }
            try { db.execSQL("ALTER TABLE documents ADD COLUMN has_images INTEGER DEFAULT 0") } catch (_: Throwable) { }
        }
    }

    fun countDocs(): Long {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM documents", null).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else 0
        }
    }

    fun hasDocuments(): Boolean {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM documents", null).use { c ->
            return c.moveToFirst() && c.getLong(0) > 0
        }
    }

    fun import(uri: Uri, fallbackTitle: String) {
        val bytes = appContext.contentResolver.openInputStream(uri)
            ?.use { it.readBytes() }
            ?: throw IllegalStateException("не удалось открыть файл")
        // PDF -> постраничный импорт с изображениями (B19)
        if (bytes.size > 4 && bytes[0] == 0x25.toByte() && bytes[1] == 0x50.toByte()) {
            PdfImporter.importPdf(appContext, bytes, fallbackTitle.substringAfterLast('/').substringAfterLast(':'))
            return
        }
        importPlainText(bytes.toString(Charsets.UTF_8),
            fallbackTitle.substringAfterLast('/').substringAfterLast(':'))
    }

    fun importPlainText(text: String, title: String) {
        if (text.isBlank()) throw IllegalStateException("файл пустой или текст не извлекается")
        val docId = createDocument(title)
        addChunks(docId, chunkText(text), "", 0)
    }

    // ---------- API для PdfImporter ----------
    fun createDocument(title: String): Long {
        val cv = ContentValues()
        cv.put("title", title)
        cv.put("added", System.currentTimeMillis())
        return writableDatabase.insert("documents", null, cv)
    }

    fun addChunks(docId: Long, texts: List<String>, imgPath: String, pageNo: Int) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            texts.forEachIndexed { i, ch ->
                val ccv = ContentValues()
                ccv.put("doc_id", docId)
                ccv.put("ordinal", i)
                ccv.put("text", ch)
                ccv.put("page_no", pageNo)
                ccv.put("img_path", imgPath)
                db.insert("chunks", null, ccv)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun markHasImages(docId: Long) {
        val cv = ContentValues()
        cv.put("has_images", 1)
        writableDatabase.update("documents", cv, "id=?", arrayOf(docId.toString()))
    }

    // Режет абзац длиннее ~2x target по границам слов: секции OCR-книг
    // (страница без пустых строк) иначе проходили целиком и ломали бюджет промпта.
    private fun hardSplit(p: String, target: Int): List<String> {
        if (p.length <= target * 2) return listOf(p)
        val out = mutableListOf<String>()
        var i = 0
        while (i < p.length) {
            var j = minOf(i + target, p.length)
            if (j < p.length) {
                val sp = p.lastIndexOf(' ', j)
                if (sp > i + target / 2) j = sp
            }
            out.add(p.substring(i, j).trim())
            i = j
            while (i < p.length && p[i] == ' ') i++
        }
        return out.filter { it.isNotEmpty() }
    }

    private fun chunkText(text: String): List<String> {
        val paragraphs = text.split(Regex("\\n\\s*\\n")).flatMap { hardSplit(it.trim(), 550) }
        val chunks = mutableListOf<String>()
        val sb = StringBuilder()
        val target = 550
        for (p in paragraphs) {
            val clean = p.trim()
            if (clean.isEmpty()) continue
            if (sb.isNotEmpty() && sb.length + clean.length > target) {
                chunks.add(sb.toString())
                val last = sb.toString().substringAfterLast('\n').trim()
                sb.clear()
                if (last.isNotEmpty() && last.length < 200) sb.append(last).append('\n')
            }
            sb.append(clean).append('\n')
        }
        if (sb.isNotBlank()) chunks.add(sb.toString().trim())
        return chunks
    }

    private data class Row(val docId: Long, val title: String, val ordinal: Int,
                           val text: String, val pageNo: Int, val imgPath: String)

    private fun search(query: String, k: Int): List<Chunk> {
        return try {
            if (!hasDocuments()) return emptyList()
            val words = query.lowercase()
                .replace(Regex("[^a-zа-яё0-9 ]"), " ")
                .split(Regex("\\s+"))
                .filter { it.length >= 3 }
            if (words.isEmpty()) return emptyList()
            val rows = mutableListOf<Row>()
            readableDatabase.rawQuery(
                "SELECT c.doc_id, d.title, c.ordinal, c.text, c.page_no, c.img_path " +
                "FROM chunks c JOIN documents d ON d.id = c.doc_id", null)
                .use { c ->
                    while (c.moveToNext())
                        rows.add(Row(c.getLong(0), c.getString(1), c.getInt(2), c.getString(3),
                                     c.getInt(4), c.getString(5)))
                }
            val totals = rows.groupingBy { it.docId }.eachCount()
            rows.map { row ->
                val lower = row.text.lowercase()
                var score = 0
                for (w in words) if (lower.contains(w)) score++
                score to Chunk(row.title, row.text, row.ordinal, totals[row.docId] ?: 0,
                               row.pageNo, row.imgPath)
            }.filter { it.first > 0 }
                .sortedByDescending { it.first }
                .take(k).map { it.second }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun searchSafe(query: String, k: Int = 2): List<Chunk> =
        try { search(query, k) } catch (t: Throwable) { emptyList() }

    fun listDocs(): List<Pair<Long, String>> {
        val out = mutableListOf<Pair<Long, String>>()
        readableDatabase.rawQuery(
            "SELECT d.id, d.title, COUNT(c.id) FROM documents d " +
            "LEFT JOIN chunks c ON c.doc_id = d.id GROUP BY d.id ORDER BY d.added DESC", null)
            .use { c ->
                while (c.moveToNext()) out.add(c.getLong(0) to (c.getString(1) + " (" + c.getLong(2) + " фрагментов)"))
            }
        return out
    }

    fun deleteDoc(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM chunks WHERE doc_id=?", arrayOf(id))
            db.execSQL("DELETE FROM documents WHERE id=?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---------- бэкап / восстановление (JSON во внешней папке приложения) ----------

    // ПРЕДЗАГРУЗКА: база зашита в APK (assets/kb_base.zip, кладёт CI из ветки apk).
    // При пустой базе распаковываем и импортируем сами - пользователь в поле
    // не должен ничего распаковывать и импортировать вручную. Плюс самолечение:
    // если базу когда-нибудь снова снесёт, при следующем старте она восстановится.
    fun clearAll() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM chunks")
            db.execSQL("DELETE FROM documents")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun preloadFromAssets(): Boolean {
        // версионирование: смена версии = пересоздание базы из assets,
        // иначе фиксы чанкования не доходят до уже заполненной БД
        val prefs = appContext.getSharedPreferences("kbmeta", Context.MODE_PRIVATE)
        val cur = prefs.getInt("asset_version", 0)
        if (hasDocuments() && cur == KB_ASSET_VERSION) return false
        return try {
            var count = 0
            appContext.assets.open("kb_base.zip").use { input ->
                java.util.zip.ZipInputStream(input).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        val name = e.name
                        if (!e.isDirectory && name.endsWith(".txt") &&
                            !name.contains("база_полная")) {
                            val text = zis.readBytes().toString(Charsets.UTF_8)
                            if (text.isNotBlank()) {
                                importPlainText(text, name.substringAfterLast('/'))
                                count++
                            }
                        }
                        zis.closeEntry()
                        e = zis.nextEntry
                    }
                }
            }
            count > 0
        } catch (t: Throwable) {
            false
        }
    }

    fun stats(): String {
        val docs = countDocs()
        var chunks = 0L
        readableDatabase.rawQuery("SELECT COUNT(*) FROM chunks", null).use { c ->
            if (c.moveToFirst()) chunks = c.getLong(0)
        }
        return docs.toString() + " документов, " + chunks + " фрагментов"
    }

    fun exportBackup(): String {
        val arr = org.json.JSONArray()
        readableDatabase.rawQuery(
            "SELECT id, title FROM documents ORDER BY id", null).use { dc ->
            while (dc.moveToNext()) {
                val docObj = org.json.JSONObject()
                docObj.put("title", dc.getString(1))
                val chunksArr = org.json.JSONArray()
                readableDatabase.rawQuery(
                    "SELECT text FROM chunks WHERE doc_id=? ORDER BY ordinal",
                    arrayOf(dc.getLong(0).toString())).use { cc ->
                    while (cc.moveToNext()) chunksArr.put(cc.getString(0))
                }
                docObj.put("chunks", chunksArr)
                arr.put(docObj)
            }
        }
        val dir = appContext.getExternalFilesDir("backups") ?: throw IllegalStateException("нет папки backups")
        dir.mkdirs()
        val f = java.io.File(dir, "kb_backup.json")
        f.writeText(arr.toString())
        return f.absolutePath
    }

    // Восстановление: дополняет базу (не дублируя уже существующие документы)
    fun restoreBackup(): Int {
        val dir = appContext.getExternalFilesDir("backups") ?: return -1
        val f = java.io.File(dir, "kb_backup.json")
        if (!f.exists()) return -1
        val arr = org.json.JSONArray(f.readText())
        val existing = mutableSetOf<String>()
        readableDatabase.rawQuery("SELECT title FROM documents", null).use { c ->
            while (c.moveToNext()) existing.add(c.getString(0))
        }
        var restored = 0
        for (i in 0 until arr.length()) {
            val docObj = arr.getJSONObject(i)
            val title = docObj.getString("title")
            if (title in existing) continue
            val chunksArr = docObj.getJSONArray("chunks")
            val texts = (0 until chunksArr.length()).map { chunksArr.getString(it) }
            insertDocWithChunks(title, texts)
            restored++
        }
        return restored
    }

    private fun insertDocWithChunks(title: String, texts: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cv = ContentValues()
            cv.put("title", title)
            cv.put("added", System.currentTimeMillis())
            val docId = db.insert("documents", null, cv)
            texts.forEachIndexed { i, ch ->
                val ccv = ContentValues()
                ccv.put("doc_id", docId)
                ccv.put("ordinal", i)
                ccv.put("text", ch)
                db.insert("chunks", null, ccv)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        const val KB_ASSET_VERSION = 2

        @Volatile private var instance: KbDb? = null
        fun get(context: Context): KbDb =
            instance ?: synchronized(this) {
                instance ?: KbDb(context.applicationContext).also { instance = it }
            }
    }
}
