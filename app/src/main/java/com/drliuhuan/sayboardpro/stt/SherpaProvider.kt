package com.drliuhuan.sayboardpro.stt

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.drliuhuan.sayboardpro.AppPrefs
import com.drliuhuan.sayboardpro.Constants
import com.drliuhuan.sayboardpro.CrashLogger
import com.drliuhuan.sayboardpro.data.CustomDictionary
import com.drliuhuan.sayboardpro.downloader.SherpaModelDownloader
import com.k2fsa.sherpa.onnx.OnlineLMConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig
import java.io.File
import java.util.ArrayDeque

/**
 * 本地 sherpa-onnx provider（离线流式识别）。
 *
 * 替代 SayboardNeo 的 VoskProvider，基于官方 AAR（com.k2fsa.sherpa.onnx）：
 * - [prepare] 在后台线程用 [OnlineRecognizer] 加载 zipformer int8 模型（文件路径，非 assets）；
 * - [acceptWaveform] 把 16kHz PCM 转成 float[-1,1] 缓冲，解码线程轮询 `decode()`；
 * - [finish] 喂 `inputFinished()` 后做最后一次解码，回调 [SttProvider.Listener.onFinal]；
 *
 * ## 热词（核心价值）
 * 把用户词库（[CustomDictionary]）中 enabled 词条映射为 sherpa-onnx hotwords，
 * 每次会话 `createStream(hotwords)` 注入。格式为**每行一个词、只含词本身**
 * （换行分隔，如 `"示例医院\n示例人名"`），**不含权重**——权重由
 * OnlineRecognizerConfig.hotwordsScore 全局统一控制（见 buildHotwords 说明）。
 * 词库变更**不重建 recognizer**：热词在每次会话 `createStream(hotwords)` 时按当前词库
 * 重新注入（见 [ensureSession]）。[prepare] 幂等复用——仅当模型路径变化或热词启用状态
 * 翻转（词库 空↔非空，防止"空热词+hotwordsScore>0"原生崩溃）时才重建 recognizer。
 * [prepare] 带**加载互斥**（[loadLock]）：并发调用时只有一个加载线程，其余等待复用，
 * 杜绝同时 buildRecognizer 的竞争；final 提交前有**连续重复块去重**兜底（热词高分值时
 * modified_beam_search 可能反复命中热词路径产生"示例人名示例人名示例人名"这类输出，见 [dedupeRepeated]）。
 *
 * 注意：
 * - 热词只在 `modified_beam_search` 解码方式下生效（见 sherpa 文档），故强制该解码方式；
 * - **热词为空时必须彻底关闭热词**：某些模型（尤其 int8 zipformer2）在
 *   hotwordsScore>0 且热词为空字符串时可能 native 崩溃。因此词库为空时
 *   [buildRecognizer] 把 config.hotwordsScore 置 0，[ensureSession] 走
 *   `createStream("")`（无热词）。
 * - 中文模型 modelingUnit = "cjkchar"；英文/中英混排需 bpe + bpeVocab；
 * - 关闭 sherpa 内置 endpoint（enableEndpoint=false）：静音自动结束由 SttEngine 的
 *   录音灵敏度负责，避免"段内静音被提前切开"与引擎重复结束。
 */
class SherpaProvider(
    private val context: Context,
    private val prefs: AppPrefs,
    private val listener: SttProvider.Listener
) : SttProvider {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var recognizer: OnlineRecognizer? = null

    /** recognizer 加载时的模型路径；[prepare] 据此判断是否需要重建 */
    private var loadedModelPath: String? = null

    /** recognizer 构建时词库是否非空（决定 buildRecognizer 注入的 hotwordsScore）；与当前词库空/非空状态一致才能安全复用 */
    private var hotwordsEnabledAtBuild = false

    /** destroy() 后标记，防止 prepare 加载线程完成后再赋值导致泄漏 */
    @Volatile
    private var destroyed = false

    /**
     * 加载互斥：同一时刻只允许一个加载线程（[prepare] 的加载决策与加载完成后的赋值都
     * 在 loadLock 内做；并发 prepare 会走 [waitForLoadThenReady] 等待复用，绝不开第二个
     * 加载线程，杜绝"Thread-37/Thread-38 同时 buildRecognizer"的竞争）。
     */
    private val loadLock = Object()

    /** 是否有加载线程在跑；加载完成/失败时在 loadLock 内置 false 并 notifyAll */
    @Volatile
    private var loading = false

    /**
     * 本次会话热词覆盖（模型进程经 AIDL ISttService.start 注入；为空时回退到 [buildHotwords]，
     * 即 prefs 词库）。与 recognizer 重建判断（[hotwordsEnabledAtBuild]）解耦：重建判断以
     * prefs 词库为准，两进程共享同一 prefs，注入值与 prefs 一致。会话结束由下一次 start 覆盖。
     */
    @Volatile
    var sessionHotwords: String = ""

    /**
     * 是否有进行中的识别会话（stream/解码线程未清理）。服务进程在新会话
     * （ISttService.start）前调用，用于清理 IME 中途死亡残留的旧会话。
     */
    fun hasActiveSession(): Boolean = sessionActive

    // ── 一次"录音-识别"会话状态 ──────────────────────────────────────
    private var stream: OnlineStream? = null
    private var sessionActive = false
    private val pendingAudio = ArrayDeque<FloatArray>()
    private val audioLock = Object()

    @Volatile
    private var decodeRunning = false

    @Volatile
    private var inputFinished = false

    /** true=finish 时提交 final；false=cancel 时丢弃 */
    @Volatile
    private var wantFinal = false

    private var decodeThread: Thread? = null

    /** 已上报的 partial 文本（去重，仅在解码线程访问） */
    private var lastEmittedText = ""

    override val name: String = "Sherpa 本地"
    override val supportsPartialResults: Boolean = true

    override fun prepare(onReady: (Boolean, String?) -> Unit) {
        var modelPath = prefs.sherpaModelPath
        if (modelPath.isBlank() || !File(modelPath).isDirectory || !SherpaModelDownloader.validateModelDir(File(modelPath))) {
            // 自动找回：prefs 路径为空/失效时扫描默认目录（与 ensureModelDownloaded 的
            // scanInstalled 判断一致），找到完整模型则写回 prefs 并加载——解决"文件在但
            // 配置丢失/被清空"时引擎起不来、点麦克风无反应的问题
            val found = SherpaModelDownloader.scanInstalled(context).firstOrNull()
            if (found != null) {
                CrashLogger.w(TAG, "SHERPA: 配置路径无效(${modelPath.ifBlank { "<空>" }})，自动找回模型: ${found.absolutePath}")
                prefs.sherpaModelPath = found.absolutePath
                modelPath = found.absolutePath
            } else if (modelPath.isBlank()) {
                CrashLogger.w(TAG, "SHERPA: prepare - 未配置模型路径")
                onReady(false, "未配置 Sherpa 模型，请到设置页下载")
                return
            } else {
                CrashLogger.w(TAG, "SHERPA: prepare - 模型目录不完整: $modelPath")
                onReady(false, "模型目录不完整，请到设置页重新下载：$modelPath")
                return
            }
        }
        val dir = File(modelPath)

        val hotwords = buildHotwords()

        // ── 加载互斥：复用判断 / 是否已有人加载 / 销毁旧 recognizer 全在 loadLock 内做 ──
        // - 已加载且复用条件满足 → 直接 ready，二次调用零延迟（模型常驻内存）；
        // - 已有加载线程在跑 → 后台等待其完成，完成后走复用（绝不开第二个加载线程）；
        // - 否则本线程取得加载权，负责本次构建。
        // 允许 destroy() 后的防御性重新 prepare（正常流程下 destroy 后实例即被丢弃）。
        val shouldWait: Boolean
        synchronized(loadLock) {
            destroyed = false
            // 幂等复用：recognizer 已加载、模型路径未变、热词启用状态未变 → 直接复用。
            // 热词内容是 createStream 时按当前词库注入的，与 recognizer 无关，无需重建。
            if (recognizer != null && loadedModelPath == modelPath && hotwordsEnabledAtBuild == hotwords.isNotEmpty()) {
                CrashLogger.d(TAG, "SHERPA: reuse existing recognizer ($modelPath)")
                mainHandler.post { onReady(true, null) }
                return
            }
            if (loading) {
                // 已有线程在加载：等待其完成，不重复加载
                shouldWait = true
            } else {
                shouldWait = false
                loading = true
                // 需要（重新）加载：模型路径变了（下载新模型/切换预设），或热词启用状态翻转
                // （词库 空↔非空——recognizer 的 hotwordsScore 必须与当前状态一致，否则
                //  createStream 空热词 + score>0 可能 native 崩溃）。先销毁旧 recognizer。
                if (recognizer != null) {
                    CrashLogger.d(
                        TAG,
                        "SHERPA: rebuild recognizer (modelChanged=${loadedModelPath != modelPath}, " +
                            "hotwordsFlip=${hotwordsEnabledAtBuild != hotwords.isNotEmpty()})"
                    )
                    destroyRecognizerLocked()
                }
            }
        }

        if (shouldWait) {
            // 并发 prepare：等待已有加载完成后复用，绝不在加载中再开一个加载线程
            Thread { waitForLoadThenReady(onReady, modelPath) }.start()
            return
        }

        Thread {
            try {
                CrashLogger.heartbeat("sherpa:prepare-validate-ok")

                // 模型文件完整性二次校验：大小异常（0 字节/截断）直接报错，
                // 不进入 native 加载——native 加载遇到损坏文件可能直接崩进程。
                verifyModelFiles(dir)?.let { error ->
                    CrashLogger.e(TAG, "SHERPA: 模型文件完整性校验失败: $error")
                    synchronized(loadLock) {
                        loading = false
                        loadLock.notifyAll()
                    }
                    mainHandler.post { onReady(false, "模型文件异常：$error") }
                    return@Thread
                }
                CrashLogger.heartbeat("sherpa:buildRecognizer-start")

                val rec = buildRecognizer(dir)

                CrashLogger.heartbeat("sherpa:buildRecognizer-done")
                CrashLogger.d(TAG, "SHERPA: recognizer built OK (${dir.name})")
                var releasedDuringLoad = false
                synchronized(loadLock) {
                    loading = false
                    if (destroyed) {
                        // 加载完成前已被 destroy：直接释放，避免泄漏
                        releasedDuringLoad = true
                    } else {
                        recognizer = rec
                        loadedModelPath = modelPath
                        hotwordsEnabledAtBuild = hotwords.isNotEmpty()
                    }
                    loadLock.notifyAll()
                }
                if (releasedDuringLoad) {
                    rec.release()
                    CrashLogger.d(TAG, "SHERPA: recognizer released (destroyed during load)")
                    mainHandler.post { onReady(false, "引擎已销毁") }
                } else {
                    CrashLogger.heartbeat("sherpa:prepare-ready")
                    mainHandler.post { onReady(true, null) }
                }
            } catch (e: Throwable) {
                // native 崩溃在这里抓不到（进程直接死，心跳日志是唯一线索）；
                // 这里兜住 Java 层异常，包括 UnsatisfiedLinkError（.so 缺失/不匹配，
                // 属 Error 而非 Exception，Exception 分支抓不到，故用 Throwable）。
                synchronized(loadLock) {
                    loading = false
                    loadLock.notifyAll()
                }
                CrashLogger.e(TAG, "SHERPA: 模型加载失败", e)
                mainHandler.post { onReady(false, "模型加载失败：${e.message}") }
            }
        }.let { thread ->
            try {
                thread.start()
            } catch (e: Throwable) {
                // Thread.start 失败（OOM 等）：必须复位 loading，否则后续 prepare 永远等待
                synchronized(loadLock) {
                    loading = false
                    loadLock.notifyAll()
                }
                CrashLogger.e(TAG, "SHERPA: 无法启动加载线程", e)
                mainHandler.post { onReady(false, "无法启动模型加载：${e.message}") }
            }
        }
    }

    /**
     * 并发 prepare 的等待路径：已有加载线程在跑时，本调用在后台等待其完成，
     * 完成后复用已加载的 recognizer（复用条件满足时）或回传失败。
     * 不新开加载线程——加载中的并发由 [loadLock]/[loading] 互斥。
     */
    private fun waitForLoadThenReady(onReady: (Boolean, String?) -> Unit, modelPath: String) {
        synchronized(loadLock) {
            while (loading && !destroyed) {
                try {
                    loadLock.wait(LOAD_WAIT_TIMEOUT_MS)
                } catch (_: InterruptedException) {
                    return
                }
            }
            if (destroyed) {
                mainHandler.post { onReady(false, "引擎已销毁") }
                return
            }
            val hotwords = buildHotwords()
            val reuseOk = recognizer != null && loadedModelPath == modelPath &&
                hotwordsEnabledAtBuild == hotwords.isNotEmpty()
            if (reuseOk) {
                CrashLogger.d(TAG, "SHERPA: reuse after concurrent load ($modelPath)")
                mainHandler.post { onReady(true, null) }
            } else {
                mainHandler.post { onReady(false, "模型加载失败") }
            }
        }
    }

    override fun acceptWaveform(samples: ShortArray, length: Int) {
        if (length <= 0) return
        ensureSession()
        val floats = FloatArray(length) { samples[it] / 32768.0f }
        synchronized(audioLock) {
            pendingAudio.addLast(floats)
            audioLock.notifyAll()
        }
    }

    override fun finish() {
        if (!sessionActive || stream == null) {
            // 没有任何音频：直接给空结果，避免 SttEngine 卡在 PROCESSING
            mainHandler.post { listener.onFinal("") }
            return
        }
        wantFinal = true
        inputFinished = true
        synchronized(audioLock) { audioLock.notifyAll() }
    }

    override fun cancel() {
        wantFinal = false
        inputFinished = true
        synchronized(audioLock) { audioLock.notifyAll() }
        waitDecodeExit()
    }

    override fun release() {
        // 会话清理：结束解码线程、释放 stream，但**不销毁 recognizer**——
        // 模型常驻内存，下一次 prepare 幂等复用，减少二次调用延迟。
        // destroy()（IME onDestroy）时才真正 release recognizer。
        cancel()
    }

    override fun destroy() {
        // 顺序：先置 destroyed 并唤醒等待加载的线程，再停解码线程（cancel 会 join），
        // 最后才 release recognizer——避免解码线程仍在用已释放的 native 对象。
        synchronized(loadLock) {
            destroyed = true
            loadLock.notifyAll()
        }
        cancel()
        synchronized(loadLock) {
            destroyRecognizerLocked()
            loadLock.notifyAll()
        }
    }

    /** 释放 recognizer 及其加载状态（模型路径、热词启用状态）。必须在持有 loadLock 时调用。 */
    private fun destroyRecognizerLocked() {
        recognizer?.release()
        recognizer = null
        loadedModelPath = null
        hotwordsEnabledAtBuild = false
    }

    // ── 会话管理 ────────────────────────────────────────────────────

    /** 首次收到音频时创建 OnlineStream（注入热词）并启动解码线程 */
    private fun ensureSession() {
        if (sessionActive) return
        val rec = recognizer ?: return
        // 会话热词优先取 AIDL start 注入值（服务进程场景），否则回退到 prefs 词库
        val hotwords = if (sessionHotwords.isNotEmpty()) sessionHotwords else buildHotwords()
        val s = try {
            CrashLogger.heartbeat("sherpa:createStream-start")
            // 词库为空：传空字符串（buildRecognizer 已把 hotwordsScore 置 0，
            // score=0 是安全性主保证——绝不用 createStream("") + hotwordsScore>0 的组合）。
            // 非空时传 buildHotwords() 结果：每行一个词、无权重（权重由 config.hotwordsScore 统一控制）
            rec.createStream(if (hotwords.isEmpty()) "" else hotwords)
        } catch (e: Exception) {
            CrashLogger.e(TAG, "SHERPA: createStream failed", e)
            mainHandler.post { listener.onError("创建识别流失败：${e.message}") }
            return
        }
        CrashLogger.heartbeat("sherpa:createStream-done")
        CrashLogger.d(TAG, "SHERPA: stream created")
        stream = s
        sessionActive = true
        inputFinished = false
        wantFinal = false
        lastEmittedText = ""
        synchronized(audioLock) { pendingAudio.clear() }

        decodeRunning = true
        decodeThread = Thread { decodeLoop() }.also { it.start() }
    }

    /**
     * 解码线程：消费音频 → decode() → 轮询 getResult() → partial/final。
     *
     * native 调用（acceptWaveform / isReady / decode / getResult / inputFinished）
     * 全部包在 try-catch 里：native 段错误进程直接死抓不到，但 Java 层抛出的
     * UnsatisfiedLinkError / IllegalStateException 等必须记入日志且不把线程挂死。
     */
    private fun decodeLoop() {
        val rec = recognizer ?: return
        val s = stream ?: return
        CrashLogger.heartbeat("sherpa:decode-loop-start")
        while (decodeRunning) {
            try {
                // 1. 取音频（阻塞等新数据；inputFinished 后不再等新数据）
                var drained = false
                synchronized(audioLock) {
                    while (pendingAudio.isEmpty() && !inputFinished) {
                        try {
                            audioLock.wait(100)
                        } catch (_: InterruptedException) {
                            return
                        }
                    }
                    while (pendingAudio.isNotEmpty()) {
                        s.acceptWaveform(pendingAudio.removeFirst(), Constants.SAMPLE_RATE)
                        drained = true
                    }
                }

                // 2. 解码并上报 partial
                if (drained) {
                    while (rec.isReady(s)) rec.decode(s)
                    // 部分 zipformer 模型的输出带前导空白：partial 实时显示 trim 掉前导空格
                    //（不 trim 尾部——流式阶段英文词间/句尾空格是正常中间态）
                    val text = rec.getResult(s).text.trimStart()
                    if (text != lastEmittedText) {
                        lastEmittedText = text
                        if (text.isNotBlank()) {
                            mainHandler.post { listener.onPartial(text) }
                        }
                    }
                }

                // 3. 输入结束且音频全部消费完 → 退出循环
                // 注：synchronized 是 inline lambda，不能在里面 break；先算布尔值再退出
                val exitRequested = synchronized(audioLock) {
                    inputFinished && pendingAudio.isEmpty()
                }
                if (exitRequested) {
                    decodeRunning = false
                    break
                }
            } catch (e: Throwable) {
                // 解码链路任一 native 调用抛异常：记日志（含 LinkageError），终止本次会话
                CrashLogger.e(TAG, "SHERPA: decode loop failed", e)
                decodeRunning = false
                mainHandler.post { listener.onError("识别解码异常：${e.message}") }
                break
            }
        }

        // 收尾：请求 final 结果（wantFinal 时）；cancel 直接丢弃
        if (wantFinal) {
            try {
                CrashLogger.heartbeat("sherpa:final-inputFinished")
                s.inputFinished()
                while (rec.isReady(s)) rec.decode(s)
                // 热词 boost 过高时（尤其历史高分值）modified_beam_search 会反复选中热词路径，
                // 产生"示例人名示例人名示例人名""示例医院医院"这类重复输出。final 提交前做去重兜底；
                // 不动 partial（流式阶段重复是正常中间态）。
                // 部分 zipformer 模型输出带前导空白（尤其中文，如 " 你好我是…"）：
                // final 统一 trim 首尾空白。中文无词间空格、英文保留内部空格，
                // trim 只去首尾，不会动内部空格。
                val finalText = dedupeRepeated(rec.getResult(s).text).trim()
                CrashLogger.d(TAG, "SHERPA: final result len=${finalText.length}")
                mainHandler.post { listener.onFinal(finalText) }
            } catch (e: Throwable) {
                CrashLogger.e(TAG, "SHERPA: final result error", e)
                mainHandler.post { listener.onError("获取最终结果失败：${e.message}") }
            }
        }

        // 清理本次会话
        try {
            s.release()
            CrashLogger.d(TAG, "SHERPA: stream released")
        } catch (_: Exception) {
        }
        stream = null
        sessionActive = false
        lastEmittedText = ""
        synchronized(audioLock) { pendingAudio.clear() }
        CrashLogger.heartbeat("sherpa:decode-loop-end")
    }

    /** 等待解码线程退出（cancel/release 时调用），最多 [WAIT_EXIT_MS] */
    private fun waitDecodeExit() {
        val t = decodeThread
        if (t != null && t !== Thread.currentThread()) {
            try {
                t.join(WAIT_EXIT_MS)
            } catch (_: InterruptedException) {
            }
        }
        decodeThread = null
    }

    // ── 输出去重兜底 ────────────────────────────────────────────────

    /**
     * 输出去重兜底：把连续重复的 2+ 字块折叠为 1 份。
     *
     * 背景：热词 boost 过高时（尤其历史遗留的高分值），modified_beam_search 可能反复选中
     * 热词路径，产生"示例人名示例人名示例人名""示例医院医院"这类重复输出。这里在 final 提交给
     * listener 之前做兜底；**只处理 final，不动 partial**——流式阶段的重复是正常中间态，
     * partial 需要实时反映当前解码路径。
     *
     * 规则：`(\p{L}{2,})(\1)+` → `$1`。
     * - 1 字叠词（好好/刚刚）不匹配 `{2,}`，不受影响；
     * - 2 字词合法重复（休息休息）会被合并——热词场景下宁可合并，损失可接受。
     */
    private fun dedupeRepeated(text: String): String =
        if (text.isEmpty()) text
        else REPEATED_BLOCK_REGEX.replace(text) { it.groupValues[1] }

    // ── 热词注入 ────────────────────────────────────────────────────

    /**
     * 把词库 enabled 词条映射为 hotwords 字符串：
     *   示例医院
     *   示例人名
     *   木乱
     *
     * sherpa-onnx 官方 hotwords 格式（https://k2-fsa.github.io/sherpa/onnx/hotwords/index.html）：
     * **每行一个热词，只含词本身**，不含权重、不含冒号——权重由
     * OnlineRecognizerConfig.hotwordsScore 全局统一控制，createStream 的字符串里
     * 不允许出现任何 `词:分` 形式。
     * 词库为空时返回空串——调用方必须据此**完全关闭热词**
     * （buildRecognizer 把 hotwordsScore 置 0，见类注释）。
     */
    private fun buildHotwords(): String {
        val terms = CustomDictionary(prefs).enabledEntries()
        if (terms.isEmpty()) return ""
        return terms.joinToString("\n") { it.word }
    }

    // ── recognizer 构建 ─────────────────────────────────────────────

    private fun buildRecognizer(dir: File): OnlineRecognizer {
        val config = SherpaModelDownloader.configForDir(dir)
            ?: throw IllegalArgumentException("Unknown sherpa model layout in $dir")

        val modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = File(dir, config.encoder).absolutePath,
                decoder = File(dir, config.decoder).absolutePath,
                joiner = File(dir, config.joiner).absolutePath
            ),
            tokens = File(dir, config.tokens).absolutePath,
            numThreads = 2,
            provider = "cpu",
            modelType = config.modelType,
            modelingUnit = config.modelingUnit,
            bpeVocab = config.bpeVocab
        )

        // 热词只在 modified_beam_search 下生效（sherpa 文档明确要求）。
        // 关键：词库为空时 hotwordsScore 必须为 0——某些模型（尤其 int8 zipformer2）
        // 在 hotwordsScore>0 且热词为空时可能 native 崩溃。用 buildHotwords() 决定
        // 是否启用热词，避免"有分值无热词"的组合。
        val hotwords = buildHotwords()
        val hotwordsScore = if (hotwords.isBlank()) 0f else prefs.sherpaHotwordsScore
        // 只记词条数与 score，不落全量词库明文（脱敏）
        CrashLogger.d(
            TAG,
            "SHERPA: hotwordsCount=${if (hotwords.isBlank()) 0 else hotwords.split('\n').size} score=$hotwordsScore"
        )

        val recognizerConfig = OnlineRecognizerConfig(
            featConfig = getFeatureConfig(Constants.SAMPLE_RATE, 80),
            modelConfig = modelConfig,
            lmConfig = OnlineLMConfig(),
            endpointConfig = com.k2fsa.sherpa.onnx.EndpointConfig(),
            enableEndpoint = false,
            // 热词只在 modified_beam_search 下生效（sherpa 文档明确要求）
            decodingMethod = "modified_beam_search",
            maxActivePaths = 4,
            hotwordsScore = hotwordsScore
        )

        // assetManager = null → newFromFile 从绝对路径加载（模型在私有目录，非 assets）
        return OnlineRecognizer(assetManager = null, config = recognizerConfig)
    }

    // ── 模型文件完整性校验 ──────────────────────────────────────────

    /**
     * 二次校验模型文件大小，防止 0 字节/截断文件直接进入 native 加载。
     * 返回错误描述（发现异常时），全部正常则返回 null。
     */
    private fun verifyModelFiles(dir: File): String? {
        val config = SherpaModelDownloader.configForDir(dir)
            ?: return "无法识别模型布局"
        val encoder = File(dir, config.encoder)
        val decoder = File(dir, config.decoder)
        val joiner = File(dir, config.joiner)
        val tokens = File(dir, config.tokens)

        if (!encoder.exists() || !decoder.exists() || !joiner.exists() || !tokens.exists()) {
            return "模型文件缺失（存在性校验失败）"
        }
        if (encoder.length() < MIN_ENCODER_BYTES) {
            return "encoder 文件过小（${encoder.length()}B < ${MIN_ENCODER_BYTES}B），模型可能损坏"
        }
        if (decoder.length() < MIN_DECODER_BYTES) {
            return "decoder 文件过小（${decoder.length()}B < ${MIN_DECODER_BYTES}B），模型可能损坏"
        }
        if (joiner.length() < MIN_JOINER_BYTES) {
            return "joiner 文件过小（${joiner.length()}B < ${MIN_JOINER_BYTES}B），模型可能损坏"
        }
        if (tokens.length() < MIN_TOKENS_BYTES) {
            return "tokens.txt 过小（${tokens.length()}B < ${MIN_TOKENS_BYTES}B），模型可能损坏"
        }
        return null
    }

    companion object {
        private const val TAG = "SherpaProvider"
        private const val WAIT_EXIT_MS = 1500L

        /** 并发 prepare 等待加载完成的单次最大等待；超时后重查 loading，不会提前失败 */
        private const val LOAD_WAIT_TIMEOUT_MS = 10_000L

        /** 连续重复块折叠正则：`(\p{L}{2,})(\1)+` → `$1`（见 [dedupeRepeated]） */
        private val REPEATED_BLOCK_REGEX = Regex("(\\p{L}{2,})(\\1)+")

        /** int8 encoder 应 >1MB（0 字节/截断下载的明显信号） */
        private const val MIN_ENCODER_BYTES = 1_048_576L

        /** decoder / joiner 最小尺寸（zipformer 系列远大于此，仅做损坏兜底） */
        private const val MIN_DECODER_BYTES = 1_024L
        private const val MIN_JOINER_BYTES = 1_024L

        /** tokens.txt 应 >1KB */
        private const val MIN_TOKENS_BYTES = 1_024L
    }
}
