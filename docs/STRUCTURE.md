# Структура проекта OfflineRef

## Общая схема

```
Вопрос пользователя
  → KbDb.searchSafe()        поиск: стоп-слова + стеммер + фразы + веса (SQLite, scan)
  → buildRagUser()           топ-2 фрагмента, бюджет 1300 знаков, метки [стр. N]
  → LlamaEngine.nativeGenerate  llama.cpp (b4353): sampler-chain, temp 0.1
  → ответ + источники + кнопки "📷 стр. N"
```

## Модули (app/src/main/java/com/offlineref/)

| Файл | Роль |
|---|---|
| `MainActivity.kt` | UI чата, RAG-оркестрация, дословный режим, сохранение чата, диагностика |
| `LlamaEngine.kt` | JNI-мост к llama.cpp: load/generate/unload + лог-сокет |
| `KbDb.kt` | SQLite: документы/фрагменты/метки страниц, поиск со стеммером, предзагрузка из APK |
| `PdfImporter.kt` | Импорт PDF: рендер страниц (ARGB_8888) + OCR (tess-two) |
| `ModelManager.kt` | Скачивание GGUF: докачка Range, sha256-эталон, атомарный rename |
| `KnowledgeActivity.kt` | Экран базы знаний: импорт, список, удаление, восстановление из бэкапа |
| `TessApi.kt` | (резерв) JNI к собственной сборке tesseract — не используется, tess-two работает |

## Нативная часть (app/src/main/cpp/)

- `jni_bridge.cpp` — JNI-мост: llama (load/generate/sampler/log-bridge)
- `CMakeLists.txt` — llama.cpp b4353 через FetchContent, **-O3 принудительно** (debug-сборка иначе -O0)

## Данные

| Хранилище | Содержимое |
|---|---|
| `assets/kb_base.zip` | 19 txt-документов (в т.ч. грибной справочник с метками `[стр. N]`) |
| `assets/book_pages.zip` | 468 страниц-картинок (JPEG 780px) |
| `assets/tessdata/` | rus.traineddata для OCR |
| `files/models/*.gguf` | LLM (~1,9 ГБ, качается при первом запуске) |
| `files/pages/*.jpg` | страницы, извлечённые из assets при первом запуске |
| SQLite `kb` | documents, chunks (page_no, img_path), бэкап JSON во внешней папке |

## Версионирование базы

`KB_ASSET_VERSION` в KbDb.kt. Смена версии → `clearAll()` → повторная предзагрузка из assets. Защищает установки от устаревших структур фрагментов.

## CI (GitHub Actions)

1. Fetch KB v3 → assets (без кэша)
2. Fetch tessdata, assemble book_pages (15 частей → zip)
3. **Smoke test** (структура базы) + **Self-test** (5 слоёв: поиск 20/20, RAG, негатив, скорость)
4. `generateDebugAssets --rerun-tasks` (иначе APK получает кэш старой базы)
5. Сборка, dex-проверка, sha256, зеркалирование в ветку `apk` (retry на гонку)

Деградировавшая база или сломанный поиск физически не доходят до APK: сборка падает на шаге 3.
