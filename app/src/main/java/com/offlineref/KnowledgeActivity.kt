package com.offlineref

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

// Экран базы знаний: импорт текстовых документов, список, удаление.
// Импорт идёт через SAF (Storage Access Framework) - никаких разрешений
// в манифесте не нужно.

class KnowledgeActivity : Activity() {

    private lateinit var listBox: LinearLayout
    private lateinit var db: KbDb

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = KbDb.get(this)
        val title = TextView(this).apply {
            text = "База знаний (текстовые документы, .txt / .md)"
            textSize = 18f
            setPadding(48, 40, 48, 16)
        }
        val importBtn = Button(this).apply {
            text = "+ Импортировать документ"
            setOnClickListener { pickFile() }
        }
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(importBtn)
            addView(ScrollView(this@KnowledgeActivity).apply { addView(listBox) })
        }
        setContentView(root)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::listBox.isInitialized && ::db.isInitialized) refresh()
    }

    private fun refresh() {
        listBox.removeAllViews()
        val docs = db.listDocs()
        if (docs.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = "База пуста. Импортируйте документы - ответы будут идти строго по их тексту."
                setTextColor(Color.GRAY)
                setPadding(48, 24, 48, 0)
            })
            return
        }
        for ((id, label) in docs) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val tv = TextView(this).apply {
                text = label
                textSize = 15f
                setPadding(48, 16, 16, 16)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val del = Button(this).apply {
                text = "Удалить"
                setOnClickListener {
                    db.deleteDoc(id)
                    refresh()
                    toast("Документ удалён")
                }
            }
            row.addView(tv)
            row.addView(del)
            listBox.addView(row)
        }
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/*", "text/plain", "text/markdown", "application/octet-stream"))
        }
        startActivityForResult(intent, 42)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 42 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Thread {
            try {
                db.import(uri, uri.lastPathSegment ?: "документ")
                runOnUiThread {
                    refresh()
                    toast("Документ импортирован")
                }
            } catch (t: Throwable) {
                runOnUiThread { toast("Ошибка импорта: " + (t.message ?: t.javaClass.simpleName)) }
            }
        }.start()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
