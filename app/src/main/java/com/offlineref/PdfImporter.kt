package com.offlineref

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream

// Импорт PDF с постраничными изображениями:
// - каждая страница рендерится в JPEG (штатный PdfRenderer, без зависимостей)
// - текст: текстовый слой через PdfBox; если пусто (скан) - OCR через ML Kit
// - фрагменты помнят номер страницы и путь к изображению -> "📷 стр. N" в чате

object PdfImporter {

    data class Result(val docId: Long, val pages: Int)

    fun importPdf(context: Context, bytes: ByteArray, title: String): Result {
        val db = KbDb.get(context)
        val docId = db.createDocument(title)

        // временный файл для PdfRenderer
        val tmp = File(context.cacheDir, "import_$docId.pdf")
        tmp.writeBytes(bytes)
        val pfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)

        val pagesDir = File(context.filesDir, "pages")
        pagesDir.mkdirs()

        // текстовый слой целиком (PdfBox)
        var layerText = ""
        try {
            com.tom_roush.pdfbox.pdmodel.PDDocument.load(bytes.inputStream()).use { doc ->
                layerText = com.tom_roush.pdfbox.text.PDFTextStripper().getText(doc)
            }
        } catch (_: Throwable) { }

        // Tesseract: распаковываем rus.traineddata из assets при первом запуске
        val tessDataDir = File(context.filesDir, "tessdata")
        tessDataDir.mkdirs()
        val trained = File(tessDataDir, "rus.traineddata")
        if (!trained.exists()) {
            context.assets.open("tessdata/rus.traineddata").use { input ->
                trained.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val tess = if (layerText.isBlank()) {
            val t = com.googlecode.tesseract.android.TessBaseAPI()
            if (!t.init(context.filesDir.absolutePath, "rus")) null else t
        } else null

        try {
            for (i in 0 until renderer.pageCount) {
                val page = renderer.openPage(i)
                // рендер с ограничением ширины ~1100px (читаемо, легко)
                val scale = 1100f / page.width
                val w = (page.width * scale).toInt().coerceAtLeast(1)
                val h = (page.height * scale).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                val imgFile = File(pagesDir, "${docId}_p${i + 1}.jpg")
                FileOutputStream(imgFile).use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 62, out)
                }

                var text = ""
                if (layerText.isNotBlank()) {
                    text = pageTextFromLayer(layerText, i, renderer.pageCount)
                } else {
                    // OCR скана (tesseract, on-device)
                    val t = tess
                    if (t != null) {
                        try {
                            t.setImage(bmp)
                            text = t.utF8Text ?: ""
                            t.clear()
                        } catch (_: Throwable) { }
                    }
                }
                page.close()

                if (text.isNotBlank()) {
                    db.addChunks(docId, chunkPageText(text), imgFile.absolutePath, i + 1)
                }
            }
        } finally {
            try { tess?.end() } catch (_: Throwable) { }   // tess-two API: end(), не recycle()
            renderer.close()
            pfd.close()
            tmp.delete()
        }
        db.markHasImages(docId)
        return Result(docId, rendererPageCountSafe(renderer))
    }

    private fun rendererPageCountSafe(r: PdfRenderer): Int = try { r.pageCount } catch (_: Throwable) { 0 }

    // Разрез слоя PdfBox на страницы по форм-фидам; если не получилось - весь текст на стр. 1
    private fun pageTextFromLayer(full: String, pageIdx: Int, pages: Int): String {
        if (pages <= 1) return full
        val parts = full.split(Regex("\\f"))
        return if (pageIdx < parts.size) parts[pageIdx] else ""
    }

    private fun chunkPageText(text: String): List<String> {
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
}
