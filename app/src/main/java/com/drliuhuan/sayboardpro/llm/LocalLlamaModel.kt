package com.drliuhuan.sayboardpro.llm

import android.util.Log
import com.drliuhuan.sayboardpro.CrashLogger

/**
 * Process-wide holder for the on-device GGUF model.
 *
 * The IME builds a fresh [TextCorrector] for every utterance, so if the model
 * handle lived inside the corrector it would be re-loaded for every correction
 * (3-10 seconds each). Instead the native session is cached here and reused as
 * long as the same model path is requested. All native access is serialized on
 * [lock]; [release] is meant to be called from IME.onDestroy.
 */
object LocalLlamaModel {
    private const val TAG = "LocalLlamaModel"

    /** Threads for llama.cpp decode. Kept modest to leave the UI responsive. */
    const val N_THREADS = 4

    /** Default cap for a single completion. */
    const val MAX_TOKENS = 256

    private val lock = Any()

    @Volatile
    private var handle: Long = 0L

    @Volatile
    private var loadedPath: String? = null

    /** Absolute path of the model currently loaded, or null. */
    val currentModelPath: String? get() = loadedPath

    fun isLoaded(): Boolean = synchronized(lock) { handle != 0L }

    /**
     * Ensures [modelPath] is loaded, then runs a completion and returns the raw
     * generated text, or null when loading/generation fails. Lazy-loads the model
     * on first use.
     */
    fun correct(modelPath: String, prompt: String, maxTokens: Int = MAX_TOKENS): String? {
        if (modelPath.isBlank()) return null
        synchronized(lock) {
            if (!ensureLoadedLocked(modelPath)) return null
            return try {
                LlamaInferenceEngine.generate(handle, prompt, maxTokens)
            } catch (t: Throwable) {
                CrashLogger.e(TAG, "Generation failed", t)
                null
            }
        }
    }

    /**
     * Unloads the model (if any). Safe to call repeatedly; typically invoked from
     * IME.onDestroy so the several-hundred-MB model does not stay resident after
     * the keyboard process is closed.
     */
    fun release() {
        synchronized(lock) {
            if (handle != 0L) {
                LlamaInferenceEngine.free(handle)
                handle = 0L
                loadedPath = null
                Log.i(TAG, "On-device model unloaded")
            }
        }
    }

    /** Callers must hold [lock]. */
    private fun ensureLoadedLocked(modelPath: String): Boolean {
        if (handle != 0L && loadedPath == modelPath) return true
        if (handle != 0L) {
            LlamaInferenceEngine.free(handle)
            handle = 0L
            loadedPath = null
        }
        handle = try {
            Log.i(TAG, "Loading on-device model $modelPath ...")
            LlamaInferenceEngine.loadModel(modelPath, N_THREADS)
        } catch (t: Throwable) {
            CrashLogger.e(TAG, "Failed to load on-device model $modelPath", t)
            0L
        }
        loadedPath = if (handle != 0L) modelPath else null
        return handle != 0L
    }
}
