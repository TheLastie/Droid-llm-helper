#!/usr/bin/env python3
"""Смоук-тест встроенной базы: сборка КРАСНЕЕТ, если в APK попала деградировавшая база.
Эталон регресса: 79 фрагментов (v2, без грибного справочника). Цель: ~1600."""
import re
import sys
import zipfile

KB = "app/src/main/assets/kb_base.zip"
try:
    zf = zipfile.ZipFile(KB)
except Exception as e:
    sys.exit(f"FAIL: не могу открыть {KB}: {e}")

docs = [n for n in zf.namelist() if n.endswith(".txt") and "база_полная" not in n]
total_frags = 0
mushroom_pages = 0
mushroom_found = False

for nm in docs:
    t = zf.read(nm).decode("utf-8", errors="replace")
    if "гриб" in nm:
        mushroom_found = True
        mushroom_pages = t.count("[стр.")
    for sec in t.split("\n\n"):
        if len(sec.strip()) > 60:
            total_frags += 1

# book pages: страницы-картинки для кнопок "стр. N"
pages = 0
try:
    zpages = zipfile.ZipFile("app/src/main/assets/book_pages.zip")
    pages = len([n for n in zpages.namelist() if n.endswith(".jpg")])
except Exception:
    pass

errors = []
if pages < 100:
    errors.append(f"FAIL: страниц-картинок {pages} (<100) - book_pages.zip отсутствует или пуст")
if len(docs) < 15:
    errors.append(f"FAIL: документов {len(docs)} (<15)")
if total_frags < 500:
    errors.append(f"FAIL: фрагментов {total_frags} (<500) - регрессия к старой базе?")
if not mushroom_found:
    errors.append("FAIL: грибной справочник отсутствует")
elif mushroom_pages < 300:
    errors.append(f"FAIL: меток страниц {mushroom_pages} (<300) - нет привязки к фото")

if errors:
    print("\n".join(errors))
    sys.exit(1)
print(f"OK: {len(docs)} документов, {total_frags} фрагментов, {mushroom_pages} меток страниц, {pages} страниц-картинок")
