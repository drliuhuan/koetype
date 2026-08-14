package com.drliuhuan.sayboardpro.stt

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.drliuhuan.sayboardpro.AppPrefs
import com.drliuhuan.sayboardpro.CrashLogger
import com.drliuhuan.sayboardpro.R

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

    // ── 音频接收节流日志统计（按会话清零） ──────────────────────────
    /** 本会话已进入 provider 的音频块数（确认音频是否到达模型进程） */
    private var acceptedAudioBlocks = 0L

    /** 被丢弃（流无效/不匹配/服务未就绪）的音频块数 */
    private var droppedAudioBlocks = 0L

    /** acceptWaveform 节流日志：上一次打点的块数/时间 */
    private var acceptLastLogBlocks = 0L
    private var acceptLastLogMs = 0L

    /** dropped 节流日志：上一次打点的块数/时间（丢弃若持续发生会高频触发，必须节流） */
    private var dropLastLogBlocks = 0L
    private var dropLastLogMs = 0L

    /** 加载互斥：保证同一时刻只开一个加载线程；加载状态与初始化在锁内做 */
    private val loadLock = Object()

    /** 加载状态机：见 [LOAD_IDLE] 等常量；prepare()/加载回调在锁内读写 */
    @Volatile
    private var loadState = LOAD_IDLE

    /** 是否已进入前台服务状态：成功后置位，避免重复建渠道/重复 startForeground */
    @Volatile
    private var foregroundStarted = false

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
            // 新会话：音频接收节流日志统计清零，便于对照 streamId 看音频是否持续到达
            acceptedAudioBlocks = 0
            droppedAudioBlocks = 0
            acceptLastLogBlocks = 0
            dropLastLogBlocks = 0
            acceptLastLogMs = SystemClock.elapsedRealtime()
            dropLastLogMs = SystemClock.elapsedRealtime()
            // 注入本次会话热词：sherpa createStream 用（score 沿用 recognizer config 的
            // prefs.sherpaHotwordsScore，两进程共享同一 prefs，无需在此重建 recognizer）
            p.sessionHotwords = hotwords ?: ""
            CrashLogger.d(TAG, "SERVICE: start stream=$streamId hotwordsLen=${(hotwords ?: "").length} score=$score")
            return true
        }

        override fun feedAudio(streamId: Int, samples: ByteArray?) {
            if (samples == null || samples.isEmpty()) return
            val p = provider
            if (p == null || !ready || streamId != currentStreamId) {
                // 音频到了但当前流无效/服务未就绪：记丢弃（节流，可能高频持续）
                maybeLogDropped(streamId)
                return
            }
            acceptedAudioBlocks++
            maybeLogAccepted(streamId)
            val shorts = bytesToShorts(samples)
            p.acceptWaveform(shorts, shorts.size)
        }

        /** acceptWaveform 节流打点：确认音频持续到达模型进程 */
        private fun maybeLogAccepted(streamId: Int) {
            val now = SystemClock.elapsedRealtime()
            if (now - acceptLastLogMs >= LOG_INTERVAL_MS || acceptedAudioBlocks - acceptLastLogBlocks >= LOG_BLOCK_INTERVAL) {
                CrashLogger.d(TAG, "SERVICE: acceptWaveform blocks=$acceptedAudioBlocks stream=$streamId")
                acceptLastLogBlocks = acceptedAudioBlocks
                acceptLastLogMs = now
            }
        }

        /** 音频被丢弃（流不匹配/服务未就绪）的节流 W 打点 */
        private fun maybeLogDropped(streamId: Int) {
            droppedAudioBlocks++
            val now = SystemClock.elapsedRealtime()
            if (now - dropLastLogMs >= LOG_INTERVAL_MS || droppedAudioBlocks - dropLastLogBlocks >= LOG_BLOCK_INTERVAL) {
                CrashLogger.w(
                    TAG,
                    "SERVICE: acceptWaveform dropped (no active stream) dropped=$droppedAudioBlocks " +
                        "stream=$streamId current=$currentStreamId ready=$ready"
                )
                dropLastLogBlocks = droppedAudioBlocks
                dropLastLogMs = now
            }
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
        // 前台化：服务实例创建即进入前台保活，模型进程在 IME 死后不会被系统 1-2s 回收
        startForegroundIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CrashLogger.d(TAG, "SERVICE: onStartCommand")
        // 前台化（幂等）：startService 路径也走一遍，覆盖 onCreate 时受限失败、
        // 之后 IME 重新 startService 时再试的场景
        startForegroundIfNeeded()
        // 常驻：不 stopSelf；被系统回收后不自动重启（下次 bind 重新创建、模型重新加载）
        return Service.START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    // ── 前台服务 ────────────────────────────────────────────────────

    /**
     * 进入前台服务状态（通知栏常驻），使 :stt 进程获得前台优先级，系统不随 IME 死后回收。
     * 幂等：成功后 [foregroundStarted] 置位，后续调用直接返回。
     * 设置项 [AppPrefs.sttForegroundKeepAlive] 关闭时直接跳过（普通服务，无常驻通知）。
     * 降级：后台启动受限（API 26+ / Android 14+ 类型限制等）抛异常时降级为普通服务，
     * 识别功能不受影响，只是模型进程可能被系统回收（下次 bind 重新加载）。
     */
    private fun startForegroundIfNeeded() {
        if (foregroundStarted) return
        // 前台保活是设置项（task49f）：用户关闭时完全不走前台化路径，普通 bind/start 服务行为。
        // :stt 进程读 prefs 是跨进程，开关变更后模型进程下次启动时读取新值（常驻期间不热更新）。
        if (!AppPrefs(this).sttForegroundKeepAlive) {
            CrashLogger.d(TAG, "SERVICE: foreground keep-alive disabled by user, running as normal service")
            return
        }
        try {
            val channelId = "stt_service"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId, "语音识别服务", NotificationManager.IMPORTANCE_LOW
                )
                channel.description = "KoeType 语音识别模型常驻服务（保持模型已加载）"
                getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(channel)
            }
            val notification = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("KoeType 语音识别")
                .setContentText("模型服务运行中")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            startForeground(1, notification)
            foregroundStarted = true
            // type 由 manifest 的 foregroundServiceType 声明决定（specialUse），startForeground 不传 type
            CrashLogger.d(TAG, "SERVICE: foreground started (type=specialUse)")
        } catch (e: Exception) {
            // 前台服务启动受限（后台启动限制等）：降级为普通服务，功能不受影响（模型可能被回收）
            CrashLogger.w(TAG, "SERVICE: startForeground failed: ${e.message}")
        }
    }

    override fun onDestroy() {
        CrashLogger.d(TAG, "SERVICE: onDestroy")
        releaseProvider()
        super.onDestroy()
    }

    // ── 预热加载 ────────────────────────────────────────────────────

    /** 后台加载 recognizer（不在 binder 线程上做 buildRecognizer，避免阻塞 IPC） */
    private fun startLoadInBackground() {
        val p = SherpaProvider(this, AppPrefs(this), providerListener)
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

        /** 节流日志时间间隔：5 秒（feedAudio 高频，按时间/块数双阈值打点） */
        private const val LOG_INTERVAL_MS = 5_000L

        /** 节流日志：距上次打点累计 50 块（50×0.2s=10s 音频）也打一次 */
        private const val LOG_BLOCK_INTERVAL = 50L

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
