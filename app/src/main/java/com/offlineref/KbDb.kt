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
    SQLiteOpenHelper(appContext, "kb", null, 2) {

    data class Chunk(val docTitle: String, val text: String,
                     val ordinal: Int = 0, val total: Int = 0)

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
        val text: String
        if (bytes.size > 4 && bytes[0] == 0x25.toByte() && bytes[1] == 0x50.toByte()) {
            // PDF (магические байты "%P") - извлекаем текст через PdfBox
            text = try {
                com.tom_roush.pdfbox.pdmodel.PDDocument.load(bytes.inputStream()).use { doc ->
                    com.tom_roush.pdfbox.text.PDFTextStripper().getText(doc)
                }
            } catch (t: Throwable) {
                throw IllegalStateException("не удалось извлечь текст из PDF")
            }
        } else {
            text = bytes.toString(Charsets.UTF_8)
        }
        if (text.isBlank()) throw IllegalStateException("файл пустой или текст не извлекается")
        val title = fallbackTitle.substringAfterLast('/').substringAfterLast(':')
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cv = ContentValues()
            cv.put("title", title)
            cv.put("added", System.currentTimeMillis())
            val docId = db.insert("documents", null, cv)
            chunkText(text).forEachIndexed { i, ch ->
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

    private fun chunkText(text: String): List<String> {
        val paragraphs = text.split(Regex("\\n\\s*\\n"))
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

    private data class Row(val docId: Long, val title: String, val ordinal: Int, val text: String)

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
                "SELECT c.doc_id, d.title, c.ordinal, c.text " +
                "FROM chunks c JOIN documents d ON d.id = c.doc_id", null)
                .use { c ->
                    while (c.moveToNext())
                        rows.add(Row(c.getLong(0), c.getString(1), c.getInt(2), c.getString(3)))
                }
            val totals = rows.groupingBy { it.docId }.eachCount()
            rows.map { row ->
                val lower = row.text.lowercase()
                var score = 0
                for (w in words) if (lower.contains(w)) score++
                score to Chunk(row.title, row.text, row.ordinal, totals[row.docId] ?: 0)
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

    companion object {
        @Volatile private var instance: KbDb? = null
        fun get(context: Context): KbDb =
            instance ?: synchronized(this) {
                instance ?: KbDb(context.applicationContext).also { instance = it }
            }
    }
}
