cmake_minimum_required(VERSION 3.22.1)
project(llamajni CXX)

# ШАГ 3a: JNI-мост без llama.cpp.
# Бисекция (playbook 4.5): сначала доказываем контур NDK->CMake->.so->загрузка
# на Android 16 (16KB-страницы!), потом подключаем сам llama.cpp (шаг 3b).

add_library(llamajni SHARED jni_bridge.cpp)

find_library(log-lib log)
target_link_libraries(llamajni ${log-lib})
