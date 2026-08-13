package com.drliuhuan.sayboardpro.stt

import android.content.Context
import com.drliuhuan.sayboardpro.AppPrefs
import com.drliuhuan.sayboardpro.CrashLogger
import com.drliuhuan.sayboardpro.downloader.SherpaModelDownloader
import com.k2fsa.sherpa.onnx.OfflinePunctuation
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 本地标点恢复（断句加标点），基于 sherpa-onnx 的 [OfflinePunctuation]。
 *
 * 流式 ASR（Sherpa 本地）天生不输出标点：识别 final 后调用 [punctuate] 本地加标点，
 * 不依赖 LLM；LLM 纠错作为二次润色（成功时覆盖标点结果，见 SayboardProIME.onFinal）。
 *
 * 生命周期：**进程级单例**（与 [SttEngine] 一致），跨 IME 服务实例常驻内存——
 * 首次 [ensureLoaded] 在后台线程加载模型；[punctuate] 在独立单线程池执行 native，
 * 调用方（decode 线程）最多等 [PUNCT_TIMEOUT_MS]，超时/异常立即降级为原文。
 * 模型未下载 / 未加载完成 / 加载失败时 [punctuate] 原样返回（静默跳过，保持现状）。
 *
 * 模型文件：model.int8.onnx + tokens.json（sherpa 自动在模型同目录读取词表），
 * 下载与校验见 [SherpaModelDownloader]。
 */
class PunctProvider private constructor(private val prefs: AppPrefs) {

    /** 已加载的 native 对象；null=未加载/加载失败 */
    @Volatile
    private var punct: OfflinePunctuation? = null

    // 标点模型是否已加载（IME 的 STATE 总览日志用，只读）
    val isLoaded: Boolean get() = punct != null

    /** 是否已触发后台加载；加载完成/失败后复位（用户可能之后下载/删除模型） */
    @Volatile
    private var loadScheduled = false

    private val loadLock = Object()

    /**
     * 后台加载标点模型（幂等）。模型未下载时 no-op（不 spawn 线程）。
     * 建议在 IME onCreate 预热，保证首句识别时模型已加载、加标点立即生效。
     */
    fun ensureLoaded() {
        if (punct != null || loadScheduled) return
        val dir = installedDir() ?: return
        synchronized(loadLock) {
            if (punct != null || loadScheduled) return
            loadScheduled = true
        }
        Thread {
            try {
                val model = OfflinePunctuation(
                    assetManager = null,
                    config = buildConfig(dir)
                )
                synchronized(loadLock) {
                    // 竞态兜底：若已有实例（理论上不会），释放新建的
                    if (punct == null) {
                        punct = model
                    } else {
                        model.release()
                    }
                    loadScheduled = false
                }
                CrashLogger.d(TAG, "PUNCT: loaded from ${dir.name} (${dir.absolutePath})")
            } catch (e: Throwable) {
                // 模型损坏/缺 .so：记日志并复位 loadScheduled，下句话可重试；
                // 但 [punct] 保持 null，本次及期间调用静默返回原文
                CrashLogger.w(TAG, "PUNCT: load failed: ${e.message}")
                synchronized(loadLock) {
                    loadScheduled = false
                }
            }
        }.start()
    }

    /**
     * 给识别文本加标点。模型未下载 / 未加载完成 / 加载失败时原样返回（静默跳过）。
     *
     * 硬约束：native addPunctuation 在**独立单线程池**执行，调用方（decode 线程）最多等
     * [PUNCT_TIMEOUT_MS]；超时 / native 挂起 / 抛异常一律立即返回原文——
     * 绝不让标点链路把识别结果吞掉，上层 onFinal 依赖本方法返回非空文本上屏。
     * 返回结果 trim 掉模型可能带的首尾空白；addPunctuation 返回空串时也降级为原文。
     *
     * 并发说明：单线程池串行执行，连续两句同时调用时排队等待；一旦某次 native 挂起，
     * 后续调用也会因排在其后而超时降级（每个 final 最多等 [PUNCT_TIMEOUT_MS] 后原文上屏），
     * 属预期内的最坏情况，可接受。
     */
    fun punctuate(text: String): String {
        if (text.isBlank()) return text
        ensureLoaded()
        val p = punct ?: return text
        var future: Future<String>? = null
        return try {
            future = punctExecutor.submit(Callable { p.addPunctuation(text) })
            val result = future.get(PUNCT_TIMEOUT_MS, TimeUnit.MILLISECONDS).trim()
            if (result.isBlank()) text else result
        } catch (e: TimeoutException) {
            // native 挂起：尽力取消任务（native 可能不响应中断），立即返回原文，decode 线程绝不被卡死
            future?.cancel(true)
            CrashLogger.w(TAG, "PUNCT: timeout, using original")
            text
        } catch (e: Throwable) {
            // 涵盖 ExecutionException（任务内部异常）与 cancel 后的 CancellationException
            CrashLogger.w(TAG, "PUNCT: addPunctuation failed: ${e.message}")
            text
        }
    }

    /** 释放 native 资源并复位。进程级常驻时一般不调用；仅模型被删除/切换等明确需要时调用 */
    fun destroy() {
        synchronized(loadLock) {
            punct?.release()
            punct = null
            loadScheduled = false
        }
    }

    private fun installedDir(): File? {
        val path = prefs.punctModelPath
        if (path.isBlank()) return null
        val dir = File(path)
        return if (SherpaModelDownloader.validatePunctDir(dir)) dir else null
    }

    private fun buildConfig(dir: File): OfflinePunctuationConfig {
        // OfflinePunctuationModelConfig 只有 ctTransformer（model.int8.onnx 路径）字段，
        // 词表（tokens.json）由 sherpa 自动在模型同目录读取，无需单独传
        val modelConfig = OfflinePunctuationModelConfig(
            ctTransformer = File(dir, SherpaModelDownloader.PUNCT_MODEL_FILE).absolutePath,
            numThreads = 2,
            debug = false,
            provider = "cpu"
        )
        return OfflinePunctuationConfig(model = modelConfig)
    }

    companion object {
        private const val TAG = "PunctProvider"

        /** native 调用超时上限：decode 线程最多等待时长，超时立即降级为原文 */
        private const val PUNCT_TIMEOUT_MS = 2500L

        /**
         * 单线程池：所有 native addPunctuation 统一在此线程串行执行，调用方（decode 线程）
         * 通过 Future.get(timeout) 等待；native 挂起最多拖住调用方 [PUNCT_TIMEOUT_MS]。
         * 进程级常驻（daemon 线程），跨 IME 服务实例复用；destroy 时不关闭——
         * 进程存续期间空闲不占资源，也避免 shutdown 后 submit 抛 RejectedExecutionException。
         */
        private val punctExecutor: ExecutorService by lazy {
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "punct-worker").apply { isDaemon = true }
            }
        }

        @Volatile
        private var instance: PunctProvider? = null

        private val instanceLock = Any()

        /** 进程级单例：跨 IME 服务实例复用，标点模型常驻内存（与 SttEngine 一致） */
        fun get(context: Context): PunctProvider {
            synchronized(instanceLock) {
                val existing = instance
                if (existing != null) return existing
                return PunctProvider(AppPrefs(context.applicationContext)).also { instance = it }
            }
        }
    }
}
