cmake_minimum_required(VERSION 3.22.1)
project(llamajni CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# КЛЮЧЕВОЙ ФИКС (диагноз: "decode 0 зависает" при ЛЮБОЙ модели/потоках):
# AGP собирает debug-APK с CMAKE_BUILD_TYPE=Debug, а NDK для Debug ставит
# -O0 = БЕЗ оптимизации. Весь ggml/llama.cpp работал в 20-50 раз медленнее
# положенного. На том же чипе у Alibaba 3.8B давала первый токен за 3.9 с.
# Принудительно добавляем -O3 в обе конфигурации.
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -O3")
set(CMAKE_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_DEBUG} -O3")

# llama.cpp b4353 - версия, которую llama.rn обкатал на тысячах Android.
# v0.5.0 отвергнута опытом: первый decode не завершался 200+ сек даже
# на холодном телефоне при 1 потоке (подозрение на новый memory-sched).
# GGML_NATIVE OFF: не применять флаги хоста (-march=native) к ARM-кросс-сборке.
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(llamacpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG b4353
    GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(llamacpp)

# --- Tesseract 5.3.4 + Leptonica: собираем сами NDK 27 (16KB-страницы) ---
# tess-two (2021) на Android 15/16 может не загрузиться (8KB-aligned .so).
# Своя сборка: capi-интерфейс, статическая линковка в наш .so.
# Кросс-сборка под Android: try_compile не должен ЛИНКОВАТЬ (иначе линкер
# тянет чужой sysroot: crtbegin_dynamic.o ELF 40). Только компилируем.
set(CMAKE_TRY_COMPILE_TARGET_TYPE STATIC_LIBRARY)

set(SW_BUILD OFF CACHE BOOL "" FORCE)
set(BUILD_PROG OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# cpu_features НЕ тянем: его CMake разворачивает вложенную тест-сборку со
# своим тулчейном (armv7 по умолчанию) и падает. Детекция CPU нам не нужна -
# подкладываем пустой интерфейс под ожидаемый таргет (ниже).

include(FetchContent)
FetchContent_Declare(leptonica
    GIT_REPOSITORY https://github.com/DanBloomberg/leptonica.git
    GIT_TAG 1.84.1
    GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(leptonica)

# tesseract ищет Leptonica через find_package(CONFIG), а библиотека уже
# собрана в этом же билде (in-tree таргет "leptonica"). Подсовываем шим:
# конфиг без include() чужих Targets + алиас под ожидаемое имя.
if(NOT TARGET Leptonica::leptonica AND TARGET leptonica)
    add_library(Leptonica::leptonica ALIAS leptonica)
endif()
# tesseract штатно ищет Leptonica find_package-ом (его собственный Find-модуль,
# который чужие in-tree таргеты не видит). Библиотека уже собрана в этом билде -
# отключаем поиск и объявляем её найденной сами.
# заголовки leptonica (<allheaders.h> без префикса) - глобально до tesseract
include_directories(${leptonica_SOURCE_DIR}/src ${leptonica_BINARY_DIR}/src)

set(CMAKE_DISABLE_FIND_PACKAGE_Leptonica TRUE)
set(Leptonica_FOUND TRUE CACHE BOOL "" FORCE)
set(LEPTONICA_FOUND TRUE CACHE BOOL "" FORCE)
set(Leptonica_VERSION "1.84.1" CACHE STRING "" FORCE)

# То же для CpuFeaturesNdkCompat (cpu_features уже собран in-tree; в v0.9.0
# таргет ndk_compat + алиас с именем пакета)
if(NOT TARGET CpuFeaturesNdkCompat::cpu_features_ndk_compat AND TARGET cpu_features_ndk_compat)
    add_library(CpuFeaturesNdkCompat::cpu_features_ndk_compat ALIAS cpu_features_ndk_compat)
endif()
file(WRITE "${CMAKE_BINARY_DIR}/shims/CpuFeaturesNdkCompatConfig.cmake"
"set(CpuFeaturesNdkCompat_FOUND TRUE)\nif(NOT TARGET CpuFeaturesNdkCompat::cpu_features_ndk_compat AND TARGET cpu_features_ndk_compat)\n  add_library(CpuFeaturesNdkCompat::cpu_features_ndk_compat ALIAS cpu_features_ndk_compat)\nendif()\n")
set(CpuFeaturesNdkCompat_DIR "${CMAKE_BINARY_DIR}/shims" CACHE PATH "" FORCE)
# tesseract линкует CpuFeatures::ndk_compat; cpu_features может не создать его
# (условия в его CMake). Детекция CPU-признаков нам не критична - пустой интерфейс.
if(NOT TARGET CpuFeatures::ndk_compat)
    if(TARGET ndk_compat)
        add_library(CpuFeatures::ndk_compat ALIAS ndk_compat)
    else()
        add_library(CpuFeatures::ndk_compat INTERFACE IMPORTED)
    endif()
endif()
set(BUILD_TRAINING_TOOLS OFF CACHE BOOL "" FORCE)
set(BUILD_TESSERACT_BINARY OFF CACHE BOOL "" FORCE)
set(BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(ENABLE_LTO OFF CACHE BOOL "" FORCE)
set(DISABLE_CURL ON CACHE BOOL "" FORCE)
set(OPENMP_BUILD OFF CACHE BOOL "" FORCE)
set(USE_SYSTEM_ICU FALSE CACHE BOOL "" FORCE)

FetchContent_Declare(tesseract
    GIT_REPOSITORY https://github.com/tesseract-ocr/tesseract.git
    GIT_TAG 5.3.4
    GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(tesseract)

add_library(llamajni SHARED jni_bridge.cpp)
# llama.h живёт в include/ или src/ в зависимости от версии - указываем оба
target_include_directories(llamajni PRIVATE
    ${llamacpp_SOURCE_DIR}/include
    ${llamacpp_SOURCE_DIR}/src
    ${tesseract_SOURCE_DIR}/include
    ${tesseract_BINARY_DIR}/include
    ${leptonica_SOURCE_DIR}/src
    ${leptonica_BINARY_DIR}/src)
target_link_libraries(llamajni PRIVATE llama libtesseract leptonica android log)
