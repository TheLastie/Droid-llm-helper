# OfflineRef — Offline Android Reference

[Русский](README.ru.md) | **English**

An offline Android reference assistant backed by a local language model and a bundled document collection. Answers are grounded in retrieved passages and include source locations.

## Included in this repository

- Includes a chat-based reference search, verbatim lookup mode, saved conversations, book-page images, and import of TXT, Markdown, and PDF files.
- The bundled knowledge base contains survival, first-aid, vehicle, home, and mushroom-reference material.
- The project is an Android application in `app/`; architecture and usage notes are in `docs/`.

## Getting started

Install the APK linked in the Russian README. On first launch the app downloads the Qwen2.5-3B model (about 1.9 GB); after setup, reference queries work offline.

## Notes

Answers are restricted to the bundled or imported material. Check original sources for safety-critical decisions.
