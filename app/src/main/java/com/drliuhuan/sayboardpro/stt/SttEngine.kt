package com.drliuhuan.sayboardpro.stt

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.MutableLiveData
import com.drliuhuan.sayboardpro.AppPrefs
import com.drliuhuan.sayboardpro.CrashLogger
import com.drliuhuan.sayboardpro.audio.AudioRecorder
import com.drliuhuan.sayboardpro.data.CustomDictionary

/**
 * STT 引擎：编排层（对应 OpenTypeless 的 pipeline.rs 在安卓上的简化版）。
 *
 * 职责：
 * 1. 根据设置选择当前 [SttProvider]（Whisper API / 本地 Sherpa）
 * 2. 管理 [AudioRecorder]，把 PCM 路由给 provider
 * 3. 静音自动结束（"录音灵敏度"）
 * 4. 把识别结果原样交给上层（IME 的 TextManager 再做词典后处理与上屏）
 *
 * 状态通过 LiveData 暴露给 Compose 键盘视图。
 *
 * **进程级单例**（见 [from]）：IME 服务实例随键盘收起/切换输入法被销毁重建，若每次重建都
 * 新建引擎，sherpa recognizer 会随之释放、第二次说话要重新加载模型（~2s）。单例引擎跨
 * IME 服务实例存活，只重绑结果回调，实现"连续两次说话之间 0 加载延迟"（模型常驻内存）。
 * [destroy]（IME onDestroy 调用）只做软清理、保留 recognizer；[hardDestroy] 才是硬释放。
 */
class SttEngine private constructor(
    private val prefs: AppPrefs,
    listener: Listener
) {
    /**
     * 结果回调。进程级单例引擎被新的 IME 服务实例复用时通过 [rebindListener] 重绑
     * （模型常驻跨 IME 服务实例，见 [from]）。@Volatile 保证解码线程/主线程读到最新值。
     */
    @Volatile
    private var listener: Listener = listener

    /** 重绑结果回调：IME 服务实例重建时由 [from] 调用，把结果路由到新的 IME 实例 */
    fun rebindListener(newListener: Listener) {
        listener = newListener
    }

    /** 引擎代际计数：destroy()/hardDestroy() 时 +1，使在途 prepare 回调失效（丢弃旧 IME 的启动请求） */
    @Volatile
    private var engineEpoch = 0

    val stateLD = MutableLiveData(State.IDLE)
    val volumeLD = MutableLiveData(0f)
    val errorMessageLD = MutableLiveData<String>()
    val providerNameLD = MutableLiveData("")

    private val mainHandler = Handler(Looper.getMainLooper())

    private var provider: SttProvider? = null
    private var providerConfigKey: String = ""
    private var providerReady = false

    /** 最近一次词典签名：词库变化时置 providerReady=false 让 prepare 重新执行（热词按会话注入，无需重建 recognizer） */
    private var lastDictSignature = dictSignature()

    private var recorder: AudioRecorder? = null
    private var silenceSince = -1L

    /** 标记这次录音结束时是否要请求 final 结果（避免旧录音 onStopped 误触发） */
    private var stopRequested = false

    /** 长按录音会话进行中（长按手势：按下开始 → 松开立即结束；期间跳过静音自动结束） */
    private var holdPressActive = false

    /** 长按松开时引擎还在准备（模型加载中），标记取消，准备完成后不再自动启动录音 */
    private var cancelPendingStart = false

    // 长按在收尾（PROCESSING）或模型加载（PREPARING）窗口内按下：排队，状态回 READY 后用户仍按着则自动开录
    private var pendingPressDown = false

    val isListening: Boolean
        get() = stateLD.value == State.LISTENING

    /** 点按（短按）：未在听则开始，正在听则立即结束 */
    fun toggle() {
        // 清除可能残留的长按取消标记（单例引擎跨 IME 实例复用后，上次 pressUp 的取消
        // 意图不应吞掉这次新点按）
        cancelPendingStart = false
        CrashLogger.d(TAG, "ENGINE: toggle (listening=$isListening)")
        if (isListening) stop() else start()
    }

    /** 长按：按下开始录音识别（KeyboardView 判定按住超过阈值后触发） */
    fun pressDown() {
        val s = stateLD.value
        if (s == State.LISTENING) {
            CrashLogger.d(TAG, "ENGINE: pressDown ignored (state=$s)")
            return
        }
        holdPressActive = true
        cancelPendingStart = false
        if (s == State.PREPARING || s == State.PROCESSING) {
            // 上一轮收尾/模型加载中：排队，READY 后用户仍按着则自动开录
            pendingPressDown = true
            CrashLogger.d(TAG, "ENGINE: pressDown queued (state=$s)")
            return
        }
        start()
    }

    /**
     * 长按：松开（UP/CANCEL）立即结束并提交识别结果（不等待静音）。
     * 若松开时录音还没真正开始（模型加载中），取消这次准备，避免加载完自动开录。
     */
    fun pressUp() {
        if (!holdPressActive) return
        holdPressActive = false
        // 用户已松手：排队的长按意图作废
        pendingPressDown = false
        if (recorder != null) {
            stop()
        } else if (stateLD.value == State.PREPARING) {
            cancelPendingStart = true
            stateLD.value = State.READY
        }
    }

    // 状态回到 READY/IDLE 后执行排队的长按按下意图；用户已松手则作废
    private fun executePendingPressDown() {
        if (!pendingPressDown) return
        pendingPressDown = false
        if (holdPressActive) {
            CrashLogger.d(TAG, "ENGINE: queued pressDown executing")
            start()
        } else {
            CrashLogger.d(TAG, "ENGINE: queued pressDown cancelled (user released)")
        }
    }

    fun start() {
        when (stateLD.value) {
            State.LISTENING, State.PREPARING, State.PROCESSING -> return
            else -> Unit
        }

        CrashLogger.d(TAG, "ENGINE: start")
        ensureProvider()

        if (!providerReady) {
            stateLD.value = State.PREPARING
            val epoch = engineEpoch
            provider?.prepare { isReady, err ->
                // 准备期间引擎被 destroy（旧 IME 关闭）：丢弃旧 IME 的启动请求，
                // 避免模型加载完成后在一个已销毁的 IME 上误开录音
                if (epoch != engineEpoch) {
                    cancelPendingStart = false
                    return@prepare
                }
                // 长按：松开发生在引擎就绪之前，取消这次启动
                if (cancelPendingStart) {
                    cancelPendingStart = false
                    return@prepare
                }
                if (isReady) {
                    providerReady = true
                    startRecording()
                } else {
                    stateLD.value = State.ERROR
                    errorMessageLD.value = err ?: "识别引擎初始化失败"
                    listener.onError(err ?: "初始化失败")
                }
            }
        } else {
            startRecording()
        }
    }

    fun stop() {
        val rec = recorder ?: return
        CrashLogger.d(TAG, "ENGINE: stop")
        recorder = null
        stopRequested = true
        rec.stop()
        stateLD.value = State.PROCESSING
        // finish 延迟到 onStopped（录音线程完全结束后）调用，避免与读取 final 结果竞态
    }

    /** 取消本次识别（不提交） */
    fun cancel() {
        recorder?.release()
        recorder = null
        stopRequested = false
        provider?.cancel()
        stateLD.value = if (providerReady) State.READY else State.IDLE
        // 状态回到可识别：执行排队的长按按下意图（见 pressDown）
        executePendingPressDown()
    }

    /**
     * 会话清理：停止录音、结束解码线程，但**保留 provider 与 recognizer**（模型常驻内存），
     * 使下一次 start 直接复用、零延迟。真正释放模型资源用 [hardDestroy]。
     */
    fun release() {
        recorder?.release()
        recorder = null
        stopRequested = false
        provider?.release()
        // 用 postValue（异步）：onDestroy 里 lifecycleOwner 已 DESTROYED，
        // 同步 setValue 会立即触发 Compose 重组（observeAsState 向已销毁的 lifecycle 注册 observer，
        // 有 "LifecycleOwner is attempting to register while current state is DESTROYED" 风险）。
        // postValue 在窗口拆除后投递。
        stateLD.postValue(State.IDLE)
    }

    /**
     * IME 服务销毁（onDestroy）时调用：停止录音、结束当前识别，但**保留 provider 与
     * recognizer**（模型常驻内存）。
     *
     * SttEngine 是进程级单例（见 [from]），IME 服务实例可能随键盘收起/切换输入法被销毁重建，
     * 若每次重建都硬释放模型，第二次说话就要重新加载（~2s）。因此这里只做软清理，
     * recognizer 留给下一个 IME 实例复用，实现"连续两次说话之间 0 加载延迟"。
     * 真正释放模型资源走 [hardDestroy]（当前正常流程不调用；provider 类型切换时
     * [ensureProvider] 会对旧类型调用 provider.destroy()）。
     */
    fun destroy() {
        engineEpoch++
        // 释放对旧 IME 实例的引用：单例引擎会在新 IME 打开时 rebind，这里先置 no-op，
        // 避免单例长期持有已销毁的 IME 服务实例
        listener = NOOP_LISTENER
        recorder?.release()
        recorder = null
        stopRequested = false
        provider?.cancel()
        // 保留 provider 与 providerReady：下一个 IME 实例直接复用已加载的 recognizer
        stateLD.postValue(State.IDLE)
    }

    /** 硬释放：真正释放常驻模型资源（recognizer/native 内存）。进程回收前无需手动调用。 */
    fun hardDestroy() {
        engineEpoch++
        listener = NOOP_LISTENER
        recorder?.release()
        recorder = null
        stopRequested = false
        provider?.destroy()
        provider = null
        providerReady = false
        stateLD.postValue(State.IDLE)
    }

    // ── provider 生命周期 ──────────────────────────────────────────

    /**
     * 确保当前 provider 与设置一致。
     *
     * - provider 类型切换（Whisper↔Sherpa）：销毁旧实例重建；
     * - 同类型配置变化（模型路径/热词权重/whisper 端点）：保留实例，仅置 providerReady=false
     *   让 prepare 重新执行——SherpaProvider.prepare 检测到模型路径变化会重建 recognizer，
     *   路径未变则幂等复用（模型常驻内存，二次调用零延迟）；
     * - 词库变化：热词在 createStream 时按会话注入，无需重建 provider/recognizer；
     *   但词库"空↔非空"翻转会影响 recognizer 的 hotwordsScore（防 native 崩溃），
     *   同样置 providerReady=false 让 prepare 重新执行，由 SherpaProvider 判断复用还是重建。
     */
    private fun ensureProvider() {
        val dictSig = dictSignature()
        if (dictSig != lastDictSignature) {
            lastDictSignature = dictSig
            providerReady = false
        }

        // 类型安全判断：原实现用 provider?.javaClass（Class）!= SherpaProvider::class（KClass）
        // 比较，两者恒不相等 → 每次 start() 都销毁重建 provider → recognizer 释放 → 下次
        // 识别重新 buildRecognizer（1-2s 卡顿）。改为 Kotlin 类型检查，并覆盖两个方向：
        // 期望 sherpa 但当前不是 → 重建；期望 whisper 但当前是 sherpa → 也重建。
        val wantSherpa = prefs.activeProvider == AppPrefs.PROVIDER_SHERPA
        val providerIsSherpa = provider is SherpaProvider
        if (provider == null || wantSherpa != providerIsSherpa) {
            CrashLogger.d(TAG, "ENGINE: provider -> ${if (wantSherpa) "sherpa" else "whisper"}")
            provider?.destroy()
            provider = if (wantSherpa) SherpaProvider(prefs, providerListener)
                       else WhisperApiProvider(prefs, providerListener)
            providerConfigKey = ""
            providerNameLD.value = provider?.name ?: ""
        }

        val key = "${prefs.sherpaModelPath}|${prefs.sherpaHotwordsScore}|" +
            "${prefs.whisperBaseUrl}|${prefs.whisperModel}"
        if (providerConfigKey != key) {
            providerConfigKey = key
            providerReady = false
        }
    }

    /** 词典签名：仅启用词条的 `词:启用` 列表，用于检测词库是否变化 */
    private fun dictSignature(): String =
        CustomDictionary(prefs).enabledEntries().joinToString("|") { "${it.word}:${it.enabled}" }

    private fun startRecording() {
        val rec = AudioRecorder(audioListener)
        if (!rec.start()) {
            stateLD.value = State.ERROR
            errorMessageLD.value = "无法启动麦克风，可能正被其他应用占用"
            listener.onError(errorMessageLD.value!!)
            return
        }
        recorder = rec
        stopRequested = false
        silenceSince = -1
        stateLD.value = State.LISTENING
    }

    // ── 录音回调 ────────────────────────────────────────────────────

    private val audioListener = object : AudioRecorder.Listener {
        override fun onData(samples: ShortArray, length: Int) {
            provider?.acceptWaveform(samples, length)
        }

        override fun onVolume(level: Float) {
            // 录音线程回调，LiveData 需用 postValue
            volumeLD.postValue(level)
            checkSilence(level)
        }

        override fun onError(e: Exception) {
            stateLD.value = State.ERROR
            errorMessageLD.value = e.message ?: "录音错误"
            provider?.cancel()
            listener.onError(errorMessageLD.value!!)
        }

        override fun onStopped() {
            // 主线程回调：录音线程已退出，安全读取最终结果
            if (stopRequested) {
                stopRequested = false
                provider?.finish()
            }
        }
    }

    /** 静音自动结束：音量持续低于阈值超过 timeout 就自动 stop() 提交 */
    private fun checkSilence(level: Float) {
        // 长按会话中（holdPressActive）：松开即结束，跳过静音自动结束；
        // 点按会话静音自动结束仍生效。
        if (holdPressActive) {
            silenceSince = -1
            return
        }
        val timeout = prefs.silenceTimeoutMs
        if (timeout <= 0) {
            silenceSince = -1
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (level < prefs.silenceThreshold) {
            if (silenceSince < 0) silenceSince = now
            if (now - silenceSince >= timeout) {
                // 不能在录音线程里直接改 LiveData，切回主线程
                mainHandler.post { stop() }
            }
        } else {
            silenceSince = -1
        }
    }

    // ── provider 事件 → 词典后处理 → 上层 ──────────────────────────

    private val providerListener = object : SttProvider.Listener {
        override fun onPartial(text: String) {
            if (text.isBlank()) return
            listener.onPartial(text)
        }

        override fun onFinal(text: String) {
            stateLD.value = if (providerReady) State.READY else State.IDLE
            // 收尾窗口结束、回到可识别状态：执行 PROCESSING 期间排队的按下意图（见 pressDown）
            executePendingPressDown()
            if (text.isBlank()) return
            listener.onFinal(text)
        }

        override fun onError(message: String) {
            stateLD.value = State.ERROR
            errorMessageLD.value = message
            listener.onError(message)
        }
    }

    enum class State {
        IDLE, PREPARING, READY, LISTENING, PROCESSING, ERROR
    }

    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
    }

    companion object {
        private const val TAG = "SttEngine"

        /** 进程级单例：跨 IME 服务实例复用同一引擎（含 provider 与已加载 recognizer） */
        @Volatile
        private var instance: SttEngine? = null

        private val instanceLock = Any()

        /** 无操作回调：destroy()/hardDestroy() 后、新 IME rebind 前，旧 IME 的结果不再外发 */
        private val NOOP_LISTENER = object : Listener {
            override fun onPartial(text: String) {}
            override fun onFinal(text: String) {}
            override fun onError(message: String) {}
        }

        /**
         * 进程级单例工厂。
         *
         * IME 服务实例可能随键盘收起/切换输入法被销毁重建（switchBack 默认开启，每次收起
         * 键盘都会切到上一个输入法，IME 服务 onDestroy），若每次重建都新建引擎，sherpa
         * recognizer 会随之释放，第二次说话就要重新加载模型（~2s）。这里复用同一引擎，
         * 只把结果回调重绑到新 IME 实例（[rebindListener]），实现"连续两次说话之间
         * 0 加载延迟"（模型常驻内存）。
         */
        fun from(context: Context, listener: Listener): SttEngine {
            synchronized(instanceLock) {
                val existing = instance
                if (existing != null) {
                    existing.rebindListener(listener)
                    return existing
                }
                return SttEngine(AppPrefs(context), listener).also { instance = it }
            }
        }
    }
}
