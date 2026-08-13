# Top-level CMake for the SayboardPro native code.
#
# Builds two shared libraries that get packaged into the APK:
#   - libllama.so          from the bundled llama.cpp source (ships its own ggml)
#   - libllama_android.so  our JNI bridge, linked against llama + common
#
# The required cmake arguments (LLAMA_BUILD_COMMON=ON, GGML_LLAMAFILE=OFF, ...)
# are supplied from app/build.gradle.
cmake_minimum_required(VERSION 3.22.1)

project("sayboardpro-llama")

# Embed llama.cpp. Must come before our JNI target so the `llama` and `common`
# targets exist. build-llama is the binary subdirectory.
set(LLAMA_SRC ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)
add_subdirectory(${LLAMA_SRC} build-llama)

# JNI bridge that exposes llama.cpp to Kotlin via com.drliuhuan.sayboardpro.llm.LlamaInferenceEngine.
add_library(llama_android SHARED
        llama_jni.cpp)

target_include_directories(llama_android PRIVATE
        ${LLAMA_SRC}
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/ggml/src)

target_link_libraries(llama_android
        llama
        common
        android
        log)
