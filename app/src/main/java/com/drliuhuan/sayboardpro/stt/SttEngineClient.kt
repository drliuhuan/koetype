package com.drliuhuan.sayboardpro.stt

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.MutableLiveData
import com.drliuhuan.sayboardpro.AppPrefs
import com.drliuhuan.sayboardpro.CrashLogger
import com.drliuhuan.sayboardpro.audio.AudioRecorder
import com.drliuhuan.sayboardpro.data.CustomDictionary

/**
 * STT 引擎客户端（IME 进程侧适配层）：实现原 SttEngine 的公开接口，
 * KeyboardView / TextManager / IME 的调用方式不变。
 *
 * 双进程架构第一里程碑：识别引擎拆到 :stt 进程（模型常驻）。本类把状态机、录音
 * 与 AIDL 通信封装在 IME 进程：
 * - sherpa 模式：录音在 IME 进程（[AudioRecorder]），PCM 经 AIDL feedAudio 喂给
 *   :stt 进程的 [SttService]（SherpaProvider）；结果经 [ISttCallback] 回传。
 * - whisper 模式：无常驻模型，WhisperApiProvider 仍在 IME 进程本地跑（原逻辑照搬）。
 *
 * 与旧 SttEngine 的进程级单例不同：IME 每次重建都 new 一个新 client + 重新 bind，
 * 模型进程（:stt）复用。IME onDestroy 杀进程自杀后，模型进程继续存活、recognizer 常驻。
 *
 * 预加载：IME onCreate 即异步 bind 模型进程，bind 成功后自动 prepare()（后台加载
 * recognizer），用户按下麦克风时通常已就绪，不是按下才加载。
 */
class SttEngineClient private constructor(
    private val context: Context,
    private val prefs: AppPrefs,
    listener: Listener
) {

    /**
     * 结果回调。IME 进程实例销毁（onDestroy）后置 no-op，防在途回调打到已销毁的 IME。
     */
    @Volatile
    private var listener: Listener = listener

    /** 引擎代际计数：destroy() 时 +1，使在途 prepare 回调失效（丢弃旧 IME 的启动请求） */
    @Volatile
    private var engineEpoch = 0

    val stateLD = MutableLiveData(State.IDLE)
    val volumeLD = MutableLiveData(0f)
    val errorMessageLD = MutableLiveData<String>()
    val providerNameLD = MutableLiveData("")

    /** 模型进程加载状态：IDLE(未开始)/LOADING(加载中)/READY(就绪)/FAILED(失败)，KeyboardView 观察渲染状态条 */
    val modelStateLD = MutableLiveData(ModelState.IDLE)

    private val mainHandler = Handler(Looper.getMainLooper())

    /** provider 抽象：sherpa 走 [RemoteSherpaProvider]（AIDL 转发），whisper 走本地 WhisperApiProvider */
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

    /** 长按在收尾（PROCESSING）或模型加载（PREPARING）窗口内按下：排队，状态回 READY 后用户仍按着则自动开录 */
    private var pendingPressDown = false

    // ── :stt 进程 AIDL 连接状态 ──────────────────────────────────────
    @Volatile
    private var service: ISttService? = null

    @Volatile
    private var bindRequested = false

    private var serviceConnection: ServiceConnection? = null

    /** 会话 streamId 自增（IME 侧管理，模型进程按它路由回调） */
    private var nextStreamId = 1

    /** 当前会话 streamId；录音线程/AIDL 回调线程都会读，需 volatile */
    @Volatile
    private var currentStreamId = -1

    /** 远程 prepare 轮询的失败信号：模型进程经 onError(-1, ...) 通知（模型加载失败） */
    @Volatile
    private var remotePrepareError: String? = null

    /** 预加载轮询进行中：ensureReady/pollModelReady 并发调用时只开一轮，保证幂等 */
    @Volatile
    private var readyPolling = false

    val isListening: Boolean
        get() = stateLD.value == State.LISTENING

    val isReady: Boolean
        get() = providerReady

    init {
        CrashLogger.d(TAG, "CLIENT: init provider=${prefs.activeProvider}")
        providerNameLD.value = if (prefs.activeProvider == AppPrefs.PROVIDER_SHERPA) "Sherpa 本地" else "Whisper API"
        // 预加载：键盘弹出（IME onCreate）即 bind 模型进程；bind 成功后自动 prepare()
        if (prefs.activeProvider == AppPrefs.PROVIDER_SHERPA) {
            bindService()
        }
    }

    // ── 手势状态机（与 SttEngine 一致，留在 IME 侧）─────────────────

    /** 点按（短按）：未在听则开始，正在听则立即结束 */
    fun toggle() {
        cancelPendingStart = false
        CrashLogger.d(TAG, "CLIENT: toggle (listening=$isListening)")
        if (isListening) stop() else start()
    }

    /** 长按：按下开始录音识别（KeyboardView 判定按住超过阈值后触发） */
    fun pressDown() {
        val s = stateLD.value
        if (s == State.LISTENING) {
            CrashLogger.d(TAG, "CLIENT: pressDown ignored (state=$s)")
            return
        }
        holdPressActive = true
        cancelPendingStart = false
        if (s == State.PREPARING || s == State.PROCESSING) {
            // 上一轮收尾/模型加载中：排队，READY 后用户仍按着则自动开录
            pendingPressDown = true
            CrashLogger.d(TAG, "CLIENT: pressDown queued (state=$s)")
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
            CrashLogger.d(TAG, "CLIENT: queued pressDown executing")
            start()
        } else {
            CrashLogger.d(TAG, "CLIENT: queued pressDown cancelled (user released)")
        }
    }

    fun start() {
        when (stateLD.value) {
            State.LISTENING, State.PREPARING, State.PROCESSING -> return
            else -> Unit
        }

        CrashLogger.d(TAG, "CLIENT: start")
        ensureProvider()

        if (!providerReady) {
            stateLD.value = State.PREPARING
            val epoch = engineEpoch
            provider?.prepare { isReady, err ->
                // 准备期间引擎被 destroy（旧 IME 关闭）：丢弃旧 IME 的启动请求
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
                    modelStateLD.value = ModelState.READY
                    startRecording()
                } else {
                    stateLD.value = State.ERROR
                    errorMessageLD.value = err ?: "识别引擎初始化失败"
                    modelStateLD.value = ModelState.FAILED
                    listener.onError(err ?: "初始化失败")
                }
            }
        } else {
            startRecording()
        }
    }

    fun stop() {
        val rec = recorder ?: return
        CrashLogger.d(TAG, "CLIENT: stop")
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
     * 会话清理：停止录音、结束解码线程，但**保留 provider 与模型进程 recognizer**。
     * 供上层软清理用；IME 真正销毁走 [destroy]。
     */
    fun release() {
        recorder?.release()
        recorder = null
        stopRequested = false
        provider?.release()
        stateLD.postValue(State.IDLE)
    }

    /**
     * IME 服务销毁（onDestroy）时调用：停止会话、解除 :stt 绑定。
     * 模型进程继续存活（常驻），下次 IME bind 直接复用已加载 recognizer（零加载延迟）。
     * 注意：不调用 ISttService.destroy()——那会释放 recognizer，破坏"模型常驻"目标；
     * destroy() 保留给未来显式释放场景。
     */
    fun destroy() {
        engineEpoch++
        // 释放对旧 IME 实例的引用
        listener = NOOP_LISTENER
        recorder?.release()
        recorder = null
        stopRequested = false
        try {
            provider?.cancel()
        } catch (_: Exception) {
        }
        unbindService()
        stateLD.postValue(State.IDLE)
    }

    // ── provider 选择（与 SttEngine.ensureProvider 一致，sherpa 换远程）──

    /**
     * 确保当前 provider 与设置一致：sherpa 用 [RemoteSherpaProvider]（AIDL 转发到 :stt 进程），
     * whisper 用本地 WhisperApiProvider。provider 类型/配置变化时置 providerReady=false
     * 让 prepare 重新执行。
     */
    private fun ensureProvider() {
        val dictSig = dictSignature()
        if (dictSig != lastDictSignature) {
            lastDictSignature = dictSig
            providerReady = false
        }

        val wantSherpa = prefs.activeProvider == AppPrefs.PROVIDER_SHERPA
        val providerIsSherpa = provider is RemoteSherpaProvider
        if (provider == null || wantSherpa != providerIsSherpa) {
            CrashLogger.d(TAG, "CLIENT: provider -> ${if (wantSherpa) "sherpa(remote)" else "whisper(local)"}")
            provider?.destroy()
            provider = if (wantSherpa) RemoteSherpaProvider()
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

    /** 热词字符串：每行一个词，无权重（与 SherpaProvider.buildHotwords 一致） */
    private fun buildHotwords(): String {
        val terms = CustomDictionary(prefs).enabledEntries()
        if (terms.isEmpty()) return ""
        return terms.joinToString("\n") { it.word }
    }

    private fun startRecording() {
        // sherpa 模式：先让模型进程建立本次会话（注入热词），再开麦，避免首帧音频竞态
        var remoteStarted = false
        if (provider is RemoteSherpaProvider) {
            currentStreamId = nextStreamId++
            remoteStarted = beginRemoteSession()
            if (!remoteStarted) {
                CrashLogger.w(TAG, "CLIENT: remote session start failed (service not ready)")
                stateLD.value = State.ERROR
                errorMessageLD.value = "识别服务未就绪"
                listener.onError(errorMessageLD.value!!)
                return
            }
        }

        val rec = AudioRecorder(audioListener)
        if (!rec.start()) {
            if (remoteStarted) {
                try {
                    provider?.cancel()
                } catch (_: Exception) {
                }
            }
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

    /** 让模型进程建立本次会话（注入热词）；服务未就绪返回 false */
    private fun beginRemoteSession(): Boolean {
        val s = service ?: return false
        return try {
            s.start(currentStreamId, buildHotwords(), prefs.sherpaHotwordsScore.toDouble())
        } catch (e: Exception) {
            CrashLogger.e(TAG, "CLIENT: service.start failed", e)
            false
        }
    }

    // ── 录音回调（与 SttEngine 一致，onData 转发给 provider）─────────

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
        // 长按会话中（holdPressActive）：松开即结束，跳过静音自动结束
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

    // ── provider 事件 → 上层（与 SttEngine 一致）─────────────────────

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

    // ── :stt 进程 bind / prepare 管理 ────────────────────────────────

    /**
     * 异步 bind 模型进程。幂等（[bindRequested] 防重复）。绑定成功后自动 prepare()。
     * startService 保活模型进程（IME 断开后继续常驻）；受 API 26+ 后台启动限制时
     * 回退为 bind-only（模型随 IME 断开被系统回收，可接受）。
     */
    private fun bindService(): Boolean {
        if (bindRequested) return true
        bindRequested = true
        val intent = Intent(context, SttService::class.java)
        try {
            context.startService(intent)
        } catch (e: Exception) {
            // API 26+ 后台启动限制：bindService 仍可用（BIND_AUTO_CREATE 会创建服务）
            CrashLogger.w(TAG, "CLIENT: startService failed (${e.message}), bind-only")
        }
        serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                CrashLogger.d(TAG, "CLIENT: service connected")
                if (binder == null) return
                val s = ISttService.Stub.asInterface(binder)
                service = s
                try {
                    s.setListener(callback)
                } catch (e: Exception) {
                    CrashLogger.e(TAG, "CLIENT: setListener failed", e)
                }
                // 预加载：bind 成功即 prepare（后台加载 recognizer，用户按麦时已就绪）
                if (prefs.activeProvider == AppPrefs.PROVIDER_SHERPA) {
                    modelStateLD.value = ModelState.LOADING
                    try {
                        s.prepare()
                    } catch (e: Exception) {
                        CrashLogger.e(TAG, "CLIENT: prepare failed", e)
                    }
                    // 状态驱动：后台轮询 prepare 直到就绪/失败，键盘状态条据此显示"模型加载中"
                    ensureReady()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                CrashLogger.w(TAG, "CLIENT: service disconnected")
                service = null
                providerReady = false
                modelStateLD.value = ModelState.FAILED
                errorMessageLD.value = "识别服务已断开"
                handleServiceLoss()
            }
        }
        return try {
            context.bindService(intent, serviceConnection!!, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            CrashLogger.e(TAG, "CLIENT: bindService failed", e)
            bindRequested = false
            serviceConnection = null
            modelStateLD.value = ModelState.FAILED
            errorMessageLD.value = "识别服务绑定失败"
            false
        }
    }

    private fun unbindService() {
        val conn = serviceConnection
        serviceConnection = null
        bindRequested = false
        if (conn != null) {
            try {
                context.unbindService(conn)
            } catch (_: Exception) {
            }
        }
        service = null
    }

    /**
     * 预加载驱动（幂等）：键盘弹出/绑定完成时调用，确保模型进程绑定与 prepare 流程已启动，
     * 并把加载状态驱动到 [modelStateLD]（LOADING→READY/FAILED），键盘据此显示模型加载进度条。
     * 已就绪（[providerReady]）时 no-op；whisper 模式无模型进程，直接返回。
     * 注意：现有 bind 在 onCreate 发起（onServiceConnected 后自动 prepare），本方法只是兜底驱动 +
     * 状态联动，不重复 bind/prepare（[pollModelReady] 的 prepare 轮询是既有就绪检测机制，幂等）。
     */
    fun ensureReady() {
        if (prefs.activeProvider != AppPrefs.PROVIDER_SHERPA) return
        if (providerReady) {
            modelStateLD.value = ModelState.READY
            return
        }
        // 绑定未完成则触发 bind（幂等）；bind 成功后 onServiceConnected 会自动 prepare
        if (!bindRequested) bindService()
        val s = service
        if (s != null) {
            ensureProvider()
            pollModelReady()
        }
    }

    /** 后台轮询模型进程 prepare 直到就绪/失败，更新 [modelStateLD]。并发调用只开一轮轮询 */
    private fun pollModelReady() {
        if (readyPolling) return
        val p = provider ?: return
        readyPolling = true
        modelStateLD.value = ModelState.LOADING
        p.prepare { ok, err ->
            readyPolling = false
            if (ok) {
                providerReady = true
                modelStateLD.value = ModelState.READY
            } else {
                modelStateLD.value = ModelState.FAILED
                if (err != null) errorMessageLD.value = err
            }
        }
    }

    /**
     * 服务断开/不可用：正在会话（LISTENING/PROCESSING）则结束本次会话并置 ERROR，
     * 避免卡死在 PROCESSING 等不到 onFinal。下次 start 会重新 bind + prepare。
     * 仅在主线程调用（onServiceDisconnected）。
     */
    private fun handleServiceLoss() {
        val s = stateLD.value
        if (s == State.LISTENING || s == State.PROCESSING) {
            stopRequested = false
            recorder?.release()
            recorder = null
            stateLD.value = State.ERROR
            errorMessageLD.value = "识别服务已断开"
            listener.onError("识别服务已断开")
        }
    }

    // ── AIDL 回调（模型进程 → IME）──────────────────────────────────

    private val callback = object : ISttCallback.Stub() {
        override fun onPartial(streamId: Int, text: String) {
            mainHandler.post {
                if (streamId == currentStreamId) providerListener.onPartial(text)
            }
        }

        override fun onFinal(streamId: Int, text: String) {
            mainHandler.post {
                if (streamId == currentStreamId) providerListener.onFinal(text)
            }
        }

        override fun onError(streamId: Int, message: String) {
            if (streamId < 0) {
                // 全局/预热错误（模型加载失败）：记下来，RemoteSherpaProvider.prepare 轮询时读取
                remotePrepareError = message
                CrashLogger.w(TAG, "CLIENT: remote prepare error: $message")
                return
            }
            mainHandler.post {
                if (streamId == currentStreamId) providerListener.onError(message)
            }
        }
    }

    // ── 远程 sherpa provider（AIDL 转发，实现 SttProvider 供状态机复用）──

    /**
     * sherpa provider 的 IME 侧代理：把 prepare/acceptWaveform/finish/cancel 转发到
     * :stt 进程的 [SttService]。不做模型常驻管理——模型在 :stt 进程常驻，本类只是
     * 字节流转发与回调适配。
     */
    private inner class RemoteSherpaProvider : SttProvider {

        override val name: String = "Sherpa 本地"
        override val supportsPartialResults: Boolean = true

        /**
         * 预热：确保 :stt 已 bind 并轮询其 prepare() 直到 recognizer 就绪。
         * 加载本身在 :stt 进程后台做；这里在后台线程轮询（binder 调用），
         * 完成/失败都 post 到主线程回调 onReady（状态机在主线程跑）。
         */
        override fun prepare(onReady: (Boolean, String?) -> Unit) {
            Thread {
                try {
                    if (!waitForBind(BIND_TIMEOUT_MS)) {
                        mainHandler.post { onReady(false, "识别服务连接失败") }
                        return@Thread
                    }
                    val s = service
                    if (s == null) {
                        mainHandler.post { onReady(false, "识别服务连接失败") }
                        return@Thread
                    }
                    // 启动/复用加载（幂等），然后轮询直到就绪或失败
                    remotePrepareError = null
                    try {
                        s.prepare()
                    } catch (e: Exception) {
                        mainHandler.post { onReady(false, "识别服务预热失败：${e.message}") }
                        return@Thread
                    }
                    val deadline = SystemClock.elapsedRealtime() + PREPARE_TIMEOUT_MS
                    while (SystemClock.elapsedRealtime() < deadline) {
                        val prepareErr = remotePrepareError
                        if (prepareErr != null) {
                            mainHandler.post { onReady(false, prepareErr) }
                            return@Thread
                        }
                        val ok = try {
                            s.prepare()
                        } catch (e: Exception) {
                            mainHandler.post { onReady(false, "识别服务预热失败：${e.message}") }
                            return@Thread
                        }
                        if (ok) {
                            mainHandler.post { onReady(true, null) }
                            return@Thread
                        }
                        Thread.sleep(PREPARE_POLL_MS)
                    }
                    mainHandler.post { onReady(false, "模型加载超时") }
                } catch (e: Throwable) {
                    CrashLogger.e(TAG, "CLIENT: remote prepare failed", e)
                    mainHandler.post { onReady(false, "识别服务预热失败") }
                }
            }.start()
        }

        override fun acceptWaveform(samples: ShortArray, length: Int) {
            val s = service ?: return
            val bytes = ByteArray(length * 2)
            for (i in 0 until length) {
                val v = samples[i].toInt()
                bytes[i * 2] = (v and 0xFF).toByte()
                bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
            try {
                s.feedAudio(currentStreamId, bytes)
            } catch (e: Exception) {
                CrashLogger.w(TAG, "CLIENT: feedAudio failed: ${e.message}")
            }
        }

        override fun finish() {
            try {
                service?.stop(currentStreamId)
            } catch (e: Exception) {
                CrashLogger.w(TAG, "CLIENT: stop failed: ${e.message}")
            }
        }

        override fun cancel() {
            try {
                service?.cancel(currentStreamId)
            } catch (e: Exception) {
                CrashLogger.w(TAG, "CLIENT: cancel failed: ${e.message}")
            }
        }

        override fun release() {
            cancel()
        }

        override fun destroy() {
            // 模型进程常驻：不在这里销毁服务（client.destroy 只解绑，保留 recognizer）
        }
    }

    /** 等待 bind 完成（轮询 service != null）。未发起 bind 则先发起 */
    private fun waitForBind(timeoutMs: Long): Boolean {
        if (service != null) return true
        if (!bindRequested) {
            if (!bindService()) return false
        }
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (service != null) return true
            Thread.sleep(50)
        }
        return service != null
    }

    enum class State {
        IDLE, PREPARING, READY, LISTENING, PROCESSING, ERROR
    }

    /** 模型进程加载状态：IDLE(未开始)/LOADING(加载中)/READY(就绪)/FAILED(失败) */
    enum class ModelState { IDLE, LOADING, READY, FAILED }

    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
    }

    companion object {
        private const val TAG = "SttEngineClient"

        /** 等待 :stt bind 的最长时间（bindService 失败/服务不可用时快速失败） */
        private const val BIND_TIMEOUT_MS = 3_000L

        /** 等待模型加载完成的超时（90MB 模型冷加载慢设备约 2~10s，30s 足够兜底） */
        private const val PREPARE_TIMEOUT_MS = 30_000L

        /** 远程 prepare 轮询间隔 */
        private const val PREPARE_POLL_MS = 200L

        /** 无操作回调：destroy() 后、新 IME bind 前，旧 IME 的结果不再外发 */
        private val NOOP_LISTENER = object : Listener {
            override fun onPartial(text: String) {}
            override fun onFinal(text: String) {}
            override fun onError(message: String) {}
        }

        /**
         * 工厂：每次 new 一个新 client（IME 每次重建都新建 client + 重新 bind，
         * 模型进程复用）。不做进程级单例。
         */
        fun from(context: Context, listener: Listener): SttEngineClient =
            SttEngineClient(context.applicationContext, AppPrefs(context), listener)
    }
}
