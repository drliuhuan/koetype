package com.drliuhuan.sayboardpro.stt

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import com.drliuhuan.sayboardpro.AppPrefs
import com.drliuhuan.sayboardpro.CrashLogger

/**
 * 识别模型进程（:stt）服务：常驻，持有 [SherpaProvider] 与已加载 recognizer。
 *
 * 双进程架构：IME 进程（default 进程）每次键盘收起直接自杀，本服务通过
 * startService + bindService 双重保活——IME 断开 bind 后服务继续存活
 * （系统可能回收，可接受），下次 IME bind 直接复用已加载 recognizer（零加载延迟）。
 *
 * 录音不在本服务：麦克风留在 IME 进程，本服务只收 [ISttService.feedAudio] 的
 * PCM 字节流，转 short[] 后喂给 sherpa。结果经 [ISttCallback] 发回 IME。
 *
 * 单会话假设：IME 同一时刻只有一个识别会话，故只维护一个 [currentStreamId]，
 * 回调按它路由；streamId<0 表示全局/预热错误（模型加载失败等）。
 *
 * [prepare] 幂等且不阻塞 binder 线程：加载在后台线程做（loadLock 保证只开一个
 * 加载线程），prepare() 立即返回——IME 侧轮询直到返回 true。
 */
class SttService : Service() {

    /** 当前 provider；AIDL 方法跑在 binder 线程、provider 回调跑在主线程，需 volatile */
    @Volatile
    private var provider: SherpaProvider? = null

    /** IME 注册的结果回调；setListener 在 binder 线程，回调发放在主线程，需 volatile */
    @Volatile
    private var callback: ISttCallback? = null

    /** recognizer 是否已就绪（prepare 成功且未被 destroy） */
    @Volatile
    private var ready = false

    /** 当前识别会话 streamId（IME 侧自增）；回调按它路由 */
    @Volatile
    private var currentStreamId = -1

    /** 加载互斥：保证同一时刻只开一个加载线程；加载状态与初始化在锁内做 */
    private val loadLock = Object()

    /** 加载状态机：见 [LOAD_IDLE] 等常量；prepare()/加载回调在锁内读写 */
    @Volatile
    private var loadState = LOAD_IDLE

    private val binder = object : ISttService.Stub() {
        override fun prepare(): Boolean = synchronized(loadLock) {
            when (loadState) {
                LOAD_READY -> true
                LOAD_FAILED -> false
                LOAD_LOADING -> false
                else -> {
                    // 首次/重建：开后台加载线程，不阻塞 binder 线程
                    loadState = LOAD_LOADING
                    startLoadInBackground()
                    false
                }
            }
        }

        override fun start(streamId: Int, hotwords: String?, score: Double): Boolean {
            val p = provider
            if (p == null || !ready) return false
            // 前一会话残留（IME 中途死亡未正常 stop/cancel）：先清理，避免新音频进旧流、
            // 旧解码线程处理新会话导致结果丢失
            if (p.hasActiveSession()) {
                CrashLogger.w(TAG, "SERVICE: canceling stale session before start")
                p.cancel()
            }
            currentStreamId = streamId
            // 注入本次会话热词：sherpa createStream 用（score 沿用 recognizer config 的
            // prefs.sherpaHotwordsScore，两进程共享同一 prefs，无需在此重建 recognizer）
            p.sessionHotwords = hotwords ?: ""
            CrashLogger.d(TAG, "SERVICE: start stream=$streamId hotwordsLen=${(hotwords ?: "").length} score=$score")
            return true
        }

        override fun feedAudio(streamId: Int, samples: ByteArray?) {
            val p = provider
            if (p == null || !ready || streamId != currentStreamId || samples == null) return
            if (samples.isEmpty()) return
            val shorts = bytesToShorts(samples)
            p.acceptWaveform(shorts, shorts.size)
        }

        override fun stop(streamId: Int) {
            if (streamId != currentStreamId) return
            provider?.finish()
        }

        override fun cancel(streamId: Int) {
            if (streamId != currentStreamId) return
            provider?.cancel()
        }

        override fun setListener(cb: ISttCallback?) {
            val old = callback
            if (old != null && old.asBinder().isBinderAlive) {
                try {
                    old.asBinder().unlinkToDeath(deathRecipient, 0)
                } catch (_: Exception) {
                }
            }
            callback = cb
            if (cb != null) {
                try {
                    cb.asBinder().linkToDeath(deathRecipient, 0)
                } catch (_: Exception) {
                }
            }
            CrashLogger.d(TAG, "SERVICE: setListener")
        }

        override fun destroy() {
            CrashLogger.d(TAG, "SERVICE: destroy (IME requested)")
            releaseProvider()
        }
    }

    /** IME 进程死亡（自杀/被杀）时清回调引用并取消进行中的会话；模型进程继续活，recognizer 保留 */
    private val deathRecipient = IBinder.DeathRecipient {
        CrashLogger.w(TAG, "SERVICE: IME callback died, canceling session")
        callback = null
        try {
            provider?.cancel()
        } catch (e: Exception) {
            CrashLogger.w(TAG, "SERVICE: cancel on death failed: ${e.message}")
        }
        currentStreamId = -1
    }

    override fun onCreate() {
        super.onCreate()
        CrashLogger.d(TAG, "SERVICE: onCreate pid=${Process.myPid()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CrashLogger.d(TAG, "SERVICE: onStartCommand")
        // 常驻：不 stopSelf；被系统回收后不自动重启（下次 bind 重新创建、模型重新加载）
        return Service.START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        CrashLogger.d(TAG, "SERVICE: onDestroy")
        releaseProvider()
        super.onDestroy()
    }

    // ── 预热加载 ────────────────────────────────────────────────────

    /** 后台加载 recognizer（不在 binder 线程上做 buildRecognizer，避免阻塞 IPC） */
    private fun startLoadInBackground() {
        val p = SherpaProvider(AppPrefs(this), providerListener)
        provider = p
        val thread = Thread {
            try {
                p.prepare { ok, err ->
                    val stale: Boolean
                    synchronized(loadLock) {
                        // 加载期间被 destroy/替换：忽略这次结果（provider 已换新实例）
                        stale = provider !== p
                        if (!stale) {
                            loadState = if (ok) LOAD_READY else LOAD_FAILED
                            ready = ok
                        }
                    }
                    if (stale) {
                        CrashLogger.d(TAG, "SERVICE: prepare result ignored (provider replaced)")
                        return@prepare
                    }
                    if (!ok) {
                        CrashLogger.w(TAG, "SERVICE: prepare failed: $err")
                        // 加载失败经全局错误回调通知 IME 侧（轮询 prepare() 永远 false，
                        // IME 需要这条回调才能拿到失败原因而非干等到超时）
                        val cb = callback
                        if (cb != null) {
                            try {
                                cb.onError(-1, err ?: "模型加载失败")
                            } catch (e: Exception) {
                                CrashLogger.w(TAG, "SERVICE: onError callback failed: ${e.message}")
                            }
                        }
                    } else {
                        CrashLogger.d(TAG, "SERVICE: prepare done ok")
                    }
                }
            } catch (e: Throwable) {
                // native 崩溃抓不到（进程直接死），这里兜住 Java 层异常并复位状态
                CrashLogger.e(TAG, "SERVICE: prepare crashed", e)
                synchronized(loadLock) {
                    loadState = LOAD_FAILED
                    ready = false
                }
            }
        }
        try {
            thread.start()
        } catch (e: Throwable) {
            CrashLogger.e(TAG, "SERVICE: cannot start load thread", e)
            synchronized(loadLock) {
                loadState = LOAD_FAILED
                ready = false
            }
        }
    }

    /** 释放 provider 与 recognizer（destroy/服务销毁时调用）；之后可再次 prepare 重建 */
    private fun releaseProvider() {
        val p: SherpaProvider?
        synchronized(loadLock) {
            // provider 替换/清空与 loadState 一起在锁内做，避免与 prepare() 的加载线程竞态
            p = provider
            provider = null
            loadState = LOAD_IDLE
        }
        ready = false
        currentStreamId = -1
        p?.destroy()
    }

    // ── provider 事件 → AIDL 回调 → IME ─────────────────────────────

    private val providerListener = object : SttProvider.Listener {
        override fun onPartial(text: String) {
            val cb = callback
            if (cb != null) {
                try {
                    cb.onPartial(currentStreamId, text)
                } catch (e: Exception) {
                    CrashLogger.w(TAG, "SERVICE: onPartial callback failed: ${e.message}")
                }
            }
        }

        override fun onFinal(text: String) {
            val cb = callback
            if (cb != null) {
                try {
                    cb.onFinal(currentStreamId, text)
                } catch (e: Exception) {
                    CrashLogger.w(TAG, "SERVICE: onFinal callback failed: ${e.message}")
                }
            }
        }

        override fun onError(message: String) {
            val cb = callback
            if (cb != null) {
                try {
                    cb.onError(currentStreamId, message)
                } catch (e: Exception) {
                    CrashLogger.w(TAG, "SERVICE: onError callback failed: ${e.message}")
                }
            }
        }
    }

    companion object {
        private const val TAG = "SttService"

        // 加载状态机
        private const val LOAD_IDLE = 0
        private const val LOAD_LOADING = 1
        private const val LOAD_READY = 2
        private const val LOAD_FAILED = 3

        /** 把 AIDL 传来的 little-endian 字节视图转回 short[]（16kHz 16bit PCM） */
        private fun bytesToShorts(bytes: ByteArray): ShortArray {
            val shorts = ShortArray(bytes.size / 2)
            for (i in shorts.indices) {
                val lo = bytes[i * 2].toInt() and 0xFF
                val hi = bytes[i * 2 + 1].toInt() and 0xFF
                shorts[i] = ((hi shl 8) or lo).toShort()
            }
            return shorts
        }
    }
}
