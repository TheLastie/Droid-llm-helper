cmake_minimum_required(VERSION 3.22.1)
project(llamajni CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp v0.5.0 (пинованный тег). Тянется в CI - там есть сеть и git.
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
    GIT_TAG v0.5.0
    GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(llamacpp)

add_library(llamajni SHARED jni_bridge.cpp)
# llama.h живёт в include/ или src/ в зависимости от версии - указываем оба
target_include_directories(llamajni PRIVATE
    ${llamacpp_SOURCE_DIR}/include
    ${llamacpp_SOURCE_DIR}/src)
target_link_libraries(llamajni PRIVATE llama android log)
