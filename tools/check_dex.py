#!/usr/bin/env python3
"""Правило 1 playbook: после каждой сборки проверяем, что MainActivity
реально попал в dex. Если Kotlin-плагин потерялся - AGP соберёт APK
успешно, но класса не будет, и приложение упадёт мгновенно на запуске."""
import glob
import sys
import zipfile

apks = glob.glob("app/build/outputs/apk/**/*.apk", recursive=True)
if not apks:
    sys.exit("APK не найден - сборка не сработала")

apk = apks[0]
with zipfile.ZipFile(apk) as zf:
    found = any(b"MainActivity" in zf.read(n)
                for n in zf.namelist() if n.endswith(".dex"))

if found:
    print(f"OK: MainActivity в dex ({apk})")
else:
    sys.exit("КЛАССА НЕТ В DEX - Kotlin не компилировался! "
             "Проверь org.jetbrains.kotlin.android в build.gradle")
