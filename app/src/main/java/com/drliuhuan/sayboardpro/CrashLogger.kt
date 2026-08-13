package com.drliuhuan.sayboardpro

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 应用内崩溃日志捕获 + 导出。
 *
 * 用途：用户崩溃后不需要 adb/logcat，直接从设置页把日志分享出来。
 *
 * 组成：
 * 1. 全局未捕获异常处理器（[install]，在 Application.onCreate 里调用）：
 *    崩溃时把堆栈、时间戳、机型、Android 版本、是否 IME 进程写入
 *    getExternalFilesDir(null)/crash_logs/crash_<timestamp>.txt（外部私有目录，无需权限），
 *    并保留原 handler（不吞崩溃，系统仍会弹"反复停止运行"）。
 * 2. 运行日志环形缓冲：通过 [d]/[w]/[e] 代替 Log.* 的记录，最近 [RING_CAPACITY] 条
 *    定期落盘到 crash_logs/app_log.txt。
 * 3. **全量持久日志（native 崩溃的关键）**：Java 崩溃能靠 uncaught handler 兜住，但
 *    native 崩溃（sherpa/onnxruntime 的 .so 崩溃）进程直接死，Java 层 handler 收不到，
 *    内存环形缓冲也会丢失。因此 [d]/[w]/[e] 在写环形缓冲的**同时**逐条 append 到
 *    crash_logs/app_log_persist.txt（每条 flush，崩溃场景尽量不丢），这样 native 崩溃
 *    前最后执行的日志/心跳都落在磁盘上。
 * 4. 心跳 + watchdog：[heartbeat] 在关键节点记录"当前执行到哪一步"（写进 persist 日志）；
 *    watchdog 线程每 2s 检查心跳是否长时间未更新（检测主线程卡死/ANR）；
 *    导出时对比"最后一条心跳"与导出时刻，若间隔过大，头部注明"检测到异常终止"——
 *    这正是 native 崩溃的指纹（进程死前最后一步一目了然）。
 * 5. 设置页导出：[readAll] / [clear] / [buildExportFile]。
 */
object CrashLogger {

    private const val TAG = "CrashLogger"
    private const val DIR_NAME = "crash_logs"
    private const val APP_LOG_FILE = "app_log.txt"
    private const val APP_LOG_PERSIST_FILE = "app_log_persist.txt"
    private const val APP_LOG_PERSIST_OLD_FILE = "app_log_persist.old.txt"
    private const val RING_CAPACITY = 200
    private const val FLUSH_INTERVAL_MS = 1_000L

    /** 持久日志单文件上限：超过则滚动为 app_log_persist.old.txt（防止无限增长） */
    private const val MAX_PERSIST_BYTES = 512L * 1024L

    /** watchdog 检查间隔 */
    private const val WATCHDOG_INTERVAL_MS = 2_000L

    /** 心跳中断超过此时长视为"进行中步骤卡死"（30s 覆盖慢设备上 90MB 模型冷加载） */
    private const val WATCHDOG_STALL_MS = 30_000L

    /** 导出时，最后一条心跳距当前超过此时长判定为"异常终止" */
    private const val ABNORMAL_TERMINATION_GRACE_MS = 15_000L

    /**
     * watchdog 关注的"短时步骤"：这些步骤正常应在几十秒内完成，卡住超过
     * [WATCHDOG_STALL_MS] 才值得报警。注意：
     * - 不含 decode-loop 步骤——一次录音可能持续很久，心跳停在 decode-loop-start
     *   是正常状态，不能算卡死；
     * - 不含 IME 生命周期步骤——进程空闲时最后心跳就是它们，也会长时间不变。
     */
    private val WATCHDOG_TRACKED_STEPS = setOf(
        "sherpa:prepare-validate-ok",
        "sherpa:buildRecognizer-start",
        "sherpa:buildRecognizer-done",
        "sherpa:prepare-ready",
        "sherpa:createStream-start",
        "sherpa:createStream-done"
    )

    /** 最近 [RING_CAPACITY] 条日志（时间 线程 级别 标签 消息） */
    private val ring = ArrayDeque<String>(RING_CAPACITY + 1)

    /** 持久日志写入锁：跨线程 append 时保证行不交错 */
    private val persistLock = Any()

    /** 安装过则不再重复包装，避免二次替换默认 handler 时环形引用 */
    @Volatile
    private var installed = false

    /** Application 上下文，用于日志落盘；install() 时写入 */
    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var lastFlushMs = 0L

    // ── 心跳状态（进程内共享；native 崩溃时值随进程消失，但 persist 日志里留有最后一条） ──
    @Volatile
    private var lastHeartbeatStep = "(无)"

    @Volatile
    private var lastHeartbeatMs = 0L

    @Volatile
    private var watchdogReportedStall = false

    /**
     * 本次进程是否跑过 IME（SayboardProIME.onCreate 里置位）。
     * 用于崩溃文件里标注"是否 IME 进程"——本应用单进程，IME 启动链崩溃
     * 时该标志为 true。
     */
    @Volatile
    var imeProcessStarted = false
        private set

    /** 记录本次进程确实运行了 IME 服务（SayboardProIME.onCreate 调用） */
    fun notifyImeCreated() {
        imeProcessStarted = true
    }

    /**
     * 安装默认未捕获异常处理器。幂等。
     * 必须在 Application.onCreate（或任何进程最早入口）调用一次。
     */
    fun install(context: Context) {
        if (installed) return
        installed = true
        appContext = context.applicationContext

        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashFile(context, thread, throwable)
            } catch (_: Exception) {
                // 崩溃处理器自身不能再崩，否则连系统通知都没有
            }
            prevHandler?.uncaughtException(thread, throwable)
        }

        // 初始化 app_log.txt，即使从未打过日志也能导出（含空文件头）
        flushAppLog(context)
        startWatchdog()
    }

    // ── 日志 wrapper：Log.* 之外同步写入环形缓冲 + 持久日志 ─────────────

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        append('D', tag, msg)
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        append('W', tag, msg)
    }

    fun e(tag: String, msg: String) {
        Log.e(tag, msg)
        append('E', tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable) {
        Log.e(tag, msg, tr)
        append('E', tag, "$msg\n${Log.getStackTraceString(tr)}")
    }

    private fun append(level: Char, tag: String, msg: String) {
        val line = String.format(
            Locale.US, "%s %s %s %s %s",
            timestamp(), Thread.currentThread().name, level, tag, msg
        )
        synchronized(ring) {
            if (ring.size >= RING_CAPACITY) ring.removeFirst()
            ring.addLast(line)
        }
        // 关键：逐条写盘。native 崩溃进程直接死，只有已落盘的日志能带出来。
        appendPersistent(line)
        maybeFlush()
    }

    /** 逐条追加到 app_log_persist.txt，每条 flush；文件超限时滚动到 .old */
    private fun appendPersistent(line: String) {
        val ctx = appContext ?: return
        val dir = crashLogDir(ctx) ?: return
        synchronized(persistLock) {
            try {
                dir.mkdirs()
                val f = File(dir, APP_LOG_PERSIST_FILE)
                if (f.exists() && f.length() > MAX_PERSIST_BYTES) {
                    val old = File(dir, APP_LOG_PERSIST_OLD_FILE)
                    if (old.exists()) old.delete()
                    f.renameTo(old)
                }
                // FileWriter append=true + flush：崩溃场景尽量不丢，日志量小性能可接受
                FileWriter(f, true).use { w ->
                    w.write(line)
                    w.write("\n")
                    w.flush()
                }
            } catch (_: Exception) {
                // 外部存储不可用等情况下静默失败
            }
        }
    }

    /** 节流落盘：最多每 [FLUSH_INTERVAL_MS] 写一次，避免每次 Log 都刷盘 */
    private fun maybeFlush() {
        val ctx = appContext ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastFlushMs < FLUSH_INTERVAL_MS) return
        lastFlushMs = now
        flushAppLog(ctx)
    }

    /** 把环形缓冲写入 app_log.txt（最近 [RING_CAPACITY] 条） */
    fun flushAppLog(context: Context) {
        val dir = crashLogDir(context) ?: return
        dir.mkdirs()
        val f = File(dir, APP_LOG_FILE)
        val sb = StringBuilder()
        synchronized(ring) {
            ring.forEach { sb.append(it).append('\n') }
        }
        try {
            f.writeText(sb.toString())
        } catch (_: Exception) {
            // 外部存储不可用等情况下静默失败
        }
    }

    // ── 心跳 / watchdog ─────────────────────────────────────────────

    /**
     * 关键节点埋点：更新"当前执行到哪一步"，并写一条带时间戳的心跳到持久日志。
     * native 崩溃后，导出日志时 [detectAbnormalTermination] 用这条心跳定位崩溃前最后一步。
     */
    fun heartbeat(step: String) {
        lastHeartbeatStep = step
        lastHeartbeatMs = SystemClock.elapsedRealtime()
        appendPersistent("[HEARTBEAT] ${timestamp()} step=$step")
    }

    /**
     * watchdog 线程：每 [WATCHDOG_INTERVAL_MS] 检查一次心跳是否长时间未更新。
     * 只对 [WATCHDOG_TRACKED_STEPS] 里的短时关键步骤报警——进程空闲
     * （最后心跳是 IME 生命周期步骤）或正在长时间录音（decode-loop）都不误报。
     * 目的：模型加载 / createStream 等初始化步骤卡死（ANR 前兆）能在日志里留下痕迹。
     */
    private fun startWatchdog() {
        val t = Thread({
            while (true) {
                try {
                    Thread.sleep(WATCHDOG_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
                val last = lastHeartbeatMs
                if (last <= 0) continue
                val step = lastHeartbeatStep
                if (!isInFlightStep(step)) continue
                val gap = SystemClock.elapsedRealtime() - last
                if (gap > WATCHDOG_STALL_MS && !watchdogReportedStall) {
                    watchdogReportedStall = true
                    appendPersistent("[WATCHDOG] STALL: 心跳中断 gap=${gap}ms lastStep=$step")
                }
            }
        }, "CrashLogger-watchdog")
        t.isDaemon = true
        t.start()
    }

    private fun isInFlightStep(step: String): Boolean =
        WATCHDOG_TRACKED_STEPS.contains(step)

    /**
     * 导出时检测异常终止：取持久日志里最后一条心跳，若其时间戳距当前超过
     * [ABNORMAL_TERMINATION_GRACE_MS]，说明进程在上次心跳之后就没再更新就死掉了
     * （典型 native 崩溃特征）。返回描述文字，正常则 null。
     */
    fun detectAbnormalTermination(context: Context): String? {
        val f = persistentLogFile(context) ?: return null
        val lines = try {
            f.readLines()
        } catch (_: Exception) {
            return null
        }
        var lastStep: String? = null
        var lastTs: Long? = null
        for (line in lines) {
            if (!line.startsWith("[HEARTBEAT]")) continue
            val stepIdx = line.indexOf("step=")
            if (stepIdx < 0) continue
            lastStep = line.substring(stepIdx + "step=".length)
            val tsStr = line.removePrefix("[HEARTBEAT]").substringBefore(" step=").trim()
            lastTs = try {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).parse(tsStr)?.time
            } catch (_: Exception) {
                null
            }
        }
        if (lastStep == null || lastTs == null) return null
        val gapMs = System.currentTimeMillis() - lastTs
        if (gapMs > ABNORMAL_TERMINATION_GRACE_MS) {
            return "检测到异常终止（最后心跳 ${gapMs / 1000} 秒前）：步骤=$lastStep"
        }
        return null
    }

    // ── 目录与崩溃文件 ───────────────────────────────────────────────

    /** 崩溃日志目录：getExternalFilesDir(null)/crash_logs，外部存储不可用时为 null */
    fun crashLogDir(context: Context): File? {
        val base = context.getExternalFilesDir(null) ?: return null
        return File(base, DIR_NAME)
    }

    private fun writeCrashFile(context: Context, thread: Thread, throwable: Throwable) {
        val dir = crashLogDir(context) ?: return
        dir.mkdirs()

        // 先把环形缓冲落盘，保证 app_log.txt 与崩溃文件内容一致
        flushAppLog(context)

        val sb = StringBuilder()
        sb.append("========== KoeType Crash Report ==========\n")
        sb.append("Time: ").append(timestamp()).append('\n')
        sb.append("Thread: ").append(thread.name).append('\n')
        sb.append("Device: ")
            .append(Build.MANUFACTURER).append(" / ")
            .append(Build.BRAND).append(" / ")
            .append(Build.MODEL).append('\n')
        sb.append("Android: ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(')').append('\n')
        sb.append("PID: ").append(Process.myPid()).append('\n')
        sb.append("IME process: ").append(imeProcessStarted).append('\n')
        sb.append("Last heartbeat: ").append(lastHeartbeatStep)
        if (lastHeartbeatMs > 0) {
            val gapMs = SystemClock.elapsedRealtime() - lastHeartbeatMs
            sb.append(" (").append(gapMs).append("ms ago)")
        }
        sb.append('\n')
        sb.append('\n')
        sb.append("---- Stack trace ----\n")
        sb.append(Log.getStackTraceString(throwable)).append('\n')
        sb.append('\n')
        sb.append("---- Recent app log (last ").append(RING_CAPACITY).append(" lines) ----\n")
        synchronized(ring) {
            ring.forEach { sb.append(it).append('\n') }
        }

        val name = "crash_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".txt"
        File(dir, name).writeText(sb.toString())
    }

    // ── 设置页接口 ───────────────────────────────────────────────────

    /**
     * 崩溃文件列表（不含 app_log.txt / 持久日志），按修改时间倒序（最新在前）。
     * 注意：native 崩溃不产生 crash_*.txt（Java handler 收不到），线索都在持久日志里。
     */
    fun readAll(context: Context): List<File> {
        val dir = crashLogDir(context) ?: return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".txt") &&
                it.name != APP_LOG_FILE &&
                it.name != APP_LOG_PERSIST_FILE &&
                it.name != APP_LOG_PERSIST_OLD_FILE }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /** app_log.txt 文件（环形缓冲快照，可能不存在） */
    fun appLogFile(context: Context): File? {
        val dir = crashLogDir(context) ?: return null
        val f = File(dir, APP_LOG_FILE)
        return if (f.exists()) f else null
    }

    /** app_log_persist.txt 全量持久日志（native 崩溃分析的主依据，可能不存在） */
    fun persistentLogFile(context: Context): File? {
        val dir = crashLogDir(context) ?: return null
        val f = File(dir, APP_LOG_PERSIST_FILE)
        return if (f.exists()) f else null
    }

    /** 滚动前的持久日志（app_log_persist.old.txt，可能不存在） */
    fun persistentOldLogFile(context: Context): File? {
        val dir = crashLogDir(context) ?: return null
        val f = File(dir, APP_LOG_PERSIST_OLD_FILE)
        return if (f.exists()) f else null
    }

    /** 是否存在任何可导出的日志（环形快照或持久日志） */
    fun hasAnyLog(context: Context): Boolean =
        appLogFile(context) != null || persistentLogFile(context) != null

    /** 清空所有日志（崩溃文件 + app_log.txt + 持久日志 + 导出目录） */
    fun clear(context: Context) {
        val dir = crashLogDir(context) ?: return
        try {
            dir.deleteRecursively()
        } catch (_: Exception) {
            // 忽略
        }
    }

    /**
     * 打包导出文件：设备信息 + 异常终止检测头 + app_log.txt + 全量持久日志 + 全部崩溃文件，
     * 合并为一个 txt，供 ACTION_SEND 分享。返回生成的文件，失败返回 null。
     */
    fun buildExportFile(context: Context): File? {
        val dir = crashLogDir(context) ?: return null
        dir.mkdirs()
        val exportDir = File(dir, "export")
        exportDir.mkdirs()

        val out = File(
            exportDir,
            "koetype_logs_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
        )
        val sb = StringBuilder()
        sb.append("========== KoeType Log Export ==========\n")
        // native 崩溃的指纹放在最前：最后心跳中断 = 进程在加载/识别过程中直接死亡
        detectAbnormalTermination(context)?.let { sb.append("!! ").append(it).append('\n') }
        sb.append("Last heartbeat step: ").append(lastHeartbeatStep).append('\n')
        sb.append("Device: ")
            .append(Build.MANUFACTURER).append(" / ")
            .append(Build.BRAND).append(" / ")
            .append(Build.MODEL).append('\n')
        sb.append("Android: ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(')').append('\n')
        sb.append("IME process: ").append(imeProcessStarted).append('\n')
        sb.append("Exported at: ").append(timestamp()).append('\n')
        sb.append('\n')

        appLogFile(context)?.let { appLog ->
            sb.append("========== app_log.txt（环形缓冲快照） ==========\n")
            sb.append(appLog.readText()).append('\n')
        }

        persistentLogFile(context)?.let { persist ->
            sb.append("========== app_log_persist.txt（全量持久日志，native 崩溃分析主依据） ==========\n")
            sb.append(persist.readText()).append('\n')
        }

        persistentOldLogFile(context)?.let { old ->
            sb.append("\n========== app_log_persist.old.txt（滚动前的持久日志） ==========\n")
            sb.append(old.readText()).append('\n')
        }

        for (f in readAll(context)) {
            sb.append("\n========== ").append(f.name).append(" ==========\n")
            sb.append(f.readText()).append('\n')
        }

        out.writeText(sb.toString())
        return out
    }

    // ── 工具 ─────────────────────────────────────────────────────────

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
}
