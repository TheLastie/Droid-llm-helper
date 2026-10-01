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

add_library(llamajni SHARED jni_bridge.cpp)
# llama.h живёт в include/ или src/ в зависимости от версии - указываем оба
target_include_directories(llamajni PRIVATE
    ${llamacpp_SOURCE_DIR}/include
    ${llamacpp_SOURCE_DIR}/src)
target_link_libraries(llamajni PRIVATE llama android log)
