package com.offlineref

// Тонкий JNI-мост к собственной сборке tesseract (ветка tess-ndk27).
// Заменяет tess-two; .so собраны NDK 27 -> совместимость с 16KB-страницами.

object TessApi {
    init { System.loadLibrary("llamajni") }

    // 0 = ошибка инициализации (нет данных, битый datapath)
    external fun nativeTessInit(datapath: String, lang: String): Long

    // pixels: RGBA_8888, 4 байта/пиксель, длина = w*h*4
    external fun nativeTessSetImage(handle: Long, pixels: ByteArray, width: Int, height: Int)

    external fun nativeTessGetText(handle: Long): String

    external fun nativeTessEnd(handle: Long)
}
