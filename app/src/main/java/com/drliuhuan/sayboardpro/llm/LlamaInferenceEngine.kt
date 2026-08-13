package com.drliuhuan.sayboardpro.llm

import com.drliuhuan.sayboardpro.CrashLogger
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * JNI bridge to the bundled llama.cpp build.
 *
 * The native library (`libllama_android.so`, plus `libllama.so` it depends on)
 * is compiled for arm64-v8a and packaged with the APK. Every native call is
 * serialized onto a single dedicated thread because the llama context and
 * sampler are not safe for concurrent access, and the official llama.android
 * binding keeps native work on one thread too.
 */
object LlamaInferenceEngine {
    private const val TAG = "LlamaInferenceEngine"

    @Volatile
    private var available = false

    init {
        try {
            System.loadLibrary("llama_android")
            available = true
        } catch (t: Throwable) {
            // The .so is only packaged for arm64-v8a; on other ABIs loading fails.
            CrashLogger.e(TAG, "Failed to load native llama library (unsupported ABI?)", t)
            available = false
        }
    }

    /** True when the native library was packaged for this device's ABI. */
    fun isAvailable(): Boolean = available

    // Serializes all native calls so the llama session is only touched from one thread.
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "llama-inference").apply { isDaemon = true }
    }

    private fun <T> runNative(block: () -> T): T {
        if (!available) {
            throw IllegalStateException("Native llama library is not available on this device")
        }
        return executor.submit(Callable { block() }).get()
    }

    /**
     * Loads a GGUF model and creates an inference session.
     * @param modelPath absolute path to the .gguf file
     * @param nThreads number of CPU threads for decode
     * @return an opaque session handle, to be passed to [generate] and [free]
     */
    fun loadModel(modelPath: String, nThreads: Int): Long =
        runNative { nativeLoadModel(modelPath, nThreads) }

    /**
     * Runs a completion on a loaded session. Blocks until the model has produced
     * up to [maxTokens] tokens or hit an end-of-generation token.
     */
    fun generate(handle: Long, prompt: String, maxTokens: Int): String =
        runNative { nativeGenerate(handle, prompt, maxTokens) }

    /** Releases a session previously returned by [loadModel]. */
    fun free(handle: Long) {
        if (handle == 0L) return
        runCatching {
            runNative { nativeFree(handle) }
        }.onFailure { CrashLogger.e(TAG, "Failed to free model handle", it) }
    }

    @JvmStatic
    private external fun nativeLoadModel(modelPath: String, nThreads: Int): Long

    @JvmStatic
    private external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int): String

    @JvmStatic
    private external fun nativeFree(handle: Long)
}
