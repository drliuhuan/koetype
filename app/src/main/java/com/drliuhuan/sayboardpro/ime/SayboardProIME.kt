package com.drliuhuan.sayboardpro.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.lifecycle.MutableLiveData
import com.drliuhuan.sayboardpro.AppPrefs
import com.drliuhuan.sayboardpro.Constants
import com.drliuhuan.sayboardpro.CrashLogger
import com.drliuhuan.sayboardpro.R
import com.drliuhuan.sayboardpro.SettingsActivity
import com.drliuhuan.sayboardpro.data.CustomDictionary
import com.drliuhuan.sayboardpro.data.DictionaryPostProcessor
import com.drliuhuan.sayboardpro.downloader.SherpaModelDownloader
import com.drliuhuan.sayboardpro.llm.LocalLlamaModel
import com.drliuhuan.sayboardpro.llm.TextCorrectorFactory
import com.drliuhuan.sayboardpro.stt.PunctProvider
import com.drliuhuan.sayboardpro.stt.SttEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 语音输入法主类（InputMethodService）。
 * 架构参考 Sayboard 的 IME.kt：
 * - onCreateInputView 返回 [KeyboardView]（Compose）
 * - [SttEngine] 负责识别编排，结果经 [TextManager] 上屏
 */
class SayboardProIME : InputMethodService(), SttEngine.Listener {

    private lateinit var prefs: AppPrefs
    private lateinit var keyboardView: KeyboardView
    private lateinit var textManager: TextManager
    private lateinit var engine: SttEngine

    /** 本地标点恢复（断句加标点）：进程级单例，常驻内存，跨 IME 服务实例复用 */
    private lateinit var punctProvider: PunctProvider

    private var hasMicPermission = false

    // 键盘本次绑定到的编辑器包名（onStartInput/onStartInputView 记录）；onFinal 提交前据此检测焦点漂移（幽灵提交）
    private var boundEditorPackage: String? = null

    // 键盘窗口是否已创建（onCreateInputView 置 true）。实例字段，新 IME 实例自动 false。
    // 用于识别"窗口未重建的焦点抢占"：窗口已显示后绑定包变化到 KoeType 自己 = 设置页词库输入框后台抢焦点
    private var inputViewCreated = false

    // 设置页禁用自身输入法（用户决策 2026-08-13）：已检测到设置页焦点并发起切换。
    // 实例字段，进程内同一 IME 实例只切一次，防 switchToPreviousInputMethod 的 restarting 回调反复触发。
    private var settingsImeSwitchDone = false

    /**
     * Compose 需要宿主 LifecycleOwner（参考 Sayboard 的 IMELifecycleOwner）。
     * InputMethodService 没有宿主 Activity，必须手动把 owner 挂到 IME 窗口的 decorView，
     * 否则 AbstractComposeView 组合时会因找不到 ViewTreeLifecycleOwner 而失败/崩溃，
     * 键盘永远显示不出来。
     */
    private val lifecycleOwner = IMELifecycleOwner()

    /** 纠错协程作用域：随 IME 生命周期取消，避免残留的纠错越过 onDestroy 上屏 */
    private val correctionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 纠错状态（正在纠错/完成/失败），KeyboardView 观察渲染；含耗时与 token 统计 */
    private val correctionStateLD = MutableLiveData(CorrectionUiState())

    /** 键盘语言（zh/en），KeyboardView 观察渲染符号全半角；切换时由 [languageClick] 驱动 */
    private val languageLD = MutableLiveData(AppPrefs.LANG_ZH)

    /** LLM 纠错开关状态，KeyboardView 观察渲染按钮上的 ✓ 打钩 */
    private val llmEnabledLD = MutableLiveData(false)

    /** 键盘长按循环（退格连删等）协程作用域：随 IME 生命周期取消 */
    private val imeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 连续退格循环任务；长按退格期间活跃，松开取消 */
    private var backspaceRepeatJob: Job? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val resetCorrectionRunnable = Runnable {
        correctionStateLD.value = CorrectionUiState()
    }

    /** 最近一次"切回上一个输入法"的时间（防抖，见 [switchBackToPreviousIme]） */
    private var lastSwitchBackMs = 0L

    override fun onCreate() {
        super.onCreate()
        CrashLogger.notifyImeCreated()
        CrashLogger.d(TAG, "onCreate")
        CrashLogger.heartbeat("ime:onCreate")

        lifecycleOwner.onCreate()

        prefs = AppPrefs(this)
        // 从 prefs 同步语言与 LLM 开关（字段默认值只是占位；构造阶段 service 未 attach，不能读 prefs）
        languageLD.value = prefs.keyboardLanguage
        llmEnabledLD.value = prefs.llmCorrectionEnabled

        // 本地标点模型预热：后台加载，保证首句识别 final 时已就绪、加标点立即生效。
        // 进程级单例常驻（与 SttEngine 的 recognizer 一样），跨 IME 服务实例复用。
        punctProvider = PunctProvider.get(this)
        punctProvider.ensureLoaded()

        val postProcessor = DictionaryPostProcessor(this, CustomDictionary(prefs))
        textManager = TextManager(this, postProcessor)

        // STT 引擎构造很轻量（模型加载/网络连接都推迟到 start() 之后），不阻塞 IME 启动；
        // AudioRecorder 也只在实际点麦克风时才创建。
        // 键盘视图不在这里构造，推迟到 onCreateInputView（见下），确保 attach lifecycle owner
        // 严格先于 Compose 组合发生。
        engine = SttEngine.from(this, this)
    }

    override fun onCreateInputView(): View {
        CrashLogger.d(TAG, "onCreateInputView")
        CrashLogger.heartbeat("ime:onCreateInputView")
        // 键盘窗口已显示：此后 onStartInput 里绑定包变化即可判定为后台焦点抢占（见 FOCUS STOLEN 检测）
        inputViewCreated = true
        // 在这里构造键盘视图，并先把 Lifecycle/ViewModel/SavedStateRegistry owner 挂到
        // IME 窗口 decorView，再返回给框架 attach。Compose 组合发生在视图 attach 到窗口之后
        // （AbstractComposeView.onAttachedToWindow），此时 decorView 上已有 ViewTreeLifecycleOwner，
        // 避免 "ViewTreeLifecycleOwner not found" 崩溃。参考 Sayboard 的 IME.kt。
        keyboardView = KeyboardView(
            this, engine, correctionStateLD, languageLD, llmEnabledLD, keyboardListener
        )
        lifecycleOwner.attachToDecorView(window?.window?.decorView)
        return keyboardView
    }

    override fun onWindowShown() {
        super.onWindowShown()
        lifecycleOwner.onResume()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        lifecycleOwner.onPause()
    }

    override fun onStartInput(editorInfo: EditorInfo?, restarting: Boolean) {
        super.onStartInput(editorInfo, restarting)
        CrashLogger.d(TAG, "onStartInput: pkg=${editorInfo?.packageName} restarting=$restarting switchDone=$settingsImeSwitchDone inputViewCreated=$inputViewCreated boundBefore=$boundEditorPackage")
        // ── 设置页禁用自身输入法（用户决策 2026-08-13）──
        // 设置页输入（词条/数字/API key）不适合语音输入；且设置页 TextField 后台抢焦点
        // 会导致幽灵提交（commit 进设置页死连接，聊天 app 不上屏）。检测到焦点是 KoeType
        // 自己时自动让位给其他输入法，KoeType 与设置页永不绑定。
        if (editorInfo?.packageName == "com.drliuhuan.koetype") {
            switchAwayFromSettings()
        }
        // 键盘窗口已显示时切换编辑器（如按 Home 回桌面、焦点被其他输入框抢占）系统只调
        // onStartInput 不调 onStartInputView——这里同步更新绑定包，保证 FOCUS DRIFT 检测基准最新
        val prev = boundEditorPackage
        boundEditorPackage = editorInfo?.packageName
        // FOCUS STOLEN：键盘窗口已显示（inputViewCreated=true）时绑定包发生变化 = 焦点在后台被切换/抢占，
        // 典型：设置页词库输入框在后台重新抢焦点（用户切回微信但键盘还显示着，提交进死连接）。
        // 用户主动切 app 时键盘会收起重建（onCreateInputView 再次调用、inputViewCreated 重置），
        // 因此同一实例内包名变化基本就是后台抢占。只拦截绑定从其他包切到 KoeType 自己，正常使用不受影响。
        if (inputViewCreated && prev != null && prev != boundEditorPackage &&
            boundEditorPackage == "com.drliuhuan.koetype"
        ) {
            CrashLogger.w(TAG, "FOCUS STOLEN: settings input grabbed focus without window rebuild: $prev -> $boundEditorPackage")
            Toast.makeText(this, "检测到设置页抢占输入焦点，请重新点击输入框", Toast.LENGTH_SHORT).show()
        }
    }

    /** 设置页禁用自身输入法：切换到其他输入法并提示。返回是否执行了切换。 */
    private fun switchAwayFromSettings(): Boolean {
        if (settingsImeSwitchDone) return false
        settingsImeSwitchDone = true
        CrashLogger.w(TAG, "SETTINGS INPUT detected (pkg=koetype), switching to other IME")
        val switched = switchToPreviousInputMethod()
        if (!switched) {
            val defaultIme = Settings.Secure.getString(
                contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD
            )
            if (!defaultIme.isNullOrBlank() && !defaultIme.contains("com.drliuhuan.koetype")) {
                switchInputMethod(defaultIme)
                CrashLogger.w(TAG, "SETTINGS INPUT: switched to default IME $defaultIme")
                return true
            }
            CrashLogger.w(TAG, "SETTINGS INPUT: no other IME available, keep current")
            return false
        }
        return true
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        CrashLogger.d(TAG, "onStartInputView")
        CrashLogger.heartbeat("ime:onStartInputView")
        checkMicrophonePermission()
        textManager.onResume()
        // 点按/长按手势同时生效，无需从设置页刷新录音方式
        // 每次键盘弹出时从 prefs 同步语言与 LLM 开关（设置页可能改过），驱动按钮状态重渲染
        languageLD.value = prefs.keyboardLanguage
        llmEnabledLD.value = prefs.llmCorrectionEnabled
        // 记录本次绑定到的编辑器包：onFinal 提交前据此检测焦点漂移（幽灵提交见 SettingsScreen 焦点管理）
        boundEditorPackage = info.packageName
        // 呼出应用 + 输入框类型：定位"为什么上屏到错误的应用/键盘没响应"
        CrashLogger.d(TAG, "IME-START pkg=${info.packageName} inputType=${info.inputType} imeOptions=${info.imeOptions} restarting=$restarting")
        // 键盘弹出时的引擎状态：定位"收起键盘再弹出后第一次长按无效"——engine.destroy 的 postValue 异步复位，
        // 新 IME 实例复用单例引擎时 state 可能仍残留旧值（LISTENING/PROCESSING 等），长按按下被吞或排队后已松手
        CrashLogger.d(TAG, "KEYBOARD SHOW: engineState=${engine.stateLD.value} inputViewCreated=$inputViewCreated bound=$boundEditorPackage")
        // 模型/服务状态总览：一行看清 provider/LLM/标点/代理/热词配置
        val llmMode = when {
            !prefs.llmCorrectionEnabled -> "off"
            prefs.llmCorrectionMode == AppPrefs.LLM_MODE_LOCAL -> "local"
            else -> "online"
        }
        val llmDesc = when {
            llmMode == "off" -> "-"
            llmMode == "local" ->
                prefs.llmLocalModelPath.substringAfterLast('/').take(40) +
                    if (LocalLlamaModel.isLoaded()) "(loaded)" else "(not-loaded)"
            else ->
                "${prefs.llmOnlineBaseUrl.take(40)}/${prefs.llmOnlineModel} key=" +
                    if (prefs.llmOnlineApiKey.isNotBlank()) "set" else "blank"
        }
        val proxyDesc = if (!prefs.proxyEnabled) "off"
        else "${prefs.proxyProtocol}:${prefs.proxyHost}:${prefs.proxyPort}(dl=${prefs.proxyForDownload},stt=${prefs.proxyForStt},llm=${prefs.proxyForLlm})"
        val provider = if (prefs.activeProvider == AppPrefs.PROVIDER_SHERPA) "sherpa" else "whisper"
        CrashLogger.d(TAG, "STATE: provider=$provider llmMode=$llmMode llm=$llmDesc punct=${if (punctProvider.isLoaded) "loaded" else "none"} proxy=$proxyDesc hotwords=${prefs.enabledHotwordsCount()}")
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        CrashLogger.d(TAG, "onFinishInputView")
        CrashLogger.heartbeat("ime:onFinishInputView")
        // 键盘关闭时如果还在听，停止并提交已识别内容
        if (engine.isListening) {
            engine.stop()
        }
        // finishingInput=true 是用户真正收起键盘（按系统返回键），此时切回上一个输入法
        // （如 Gboard）；finishingInput=false 是切换输入法窗口的中间态，不要切。
        if (finishingInput && prefs.keyboardSwitchBack) {
            switchBackToPreviousIme()
        }
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd
        )
        textManager.onUpdateSelection(newSelStart, newSelEnd)
    }

    override fun onDestroy() {
        CrashLogger.d(TAG, "onDestroy")
        CrashLogger.heartbeat("ime:onDestroy")
        correctionScope.cancel()
        imeScope.cancel()
        mainHandler.removeCallbacks(resetCorrectionRunnable)
        lifecycleOwner.onDestroy()
        // 进程/服务销毁：彻底释放常驻模型资源（recognizer + 本地 LLM 模型）
        engine.destroy()
        LocalLlamaModel.release()
        super.onDestroy()
    }

    // ── SttEngine.Listener：识别结果上屏 ────────────────────────────

    override fun onPartial(text: String) {
        // 流式预览节点留痕（截断 20 字符）：排查"有识别但不上屏/预览卡住"
        CrashLogger.d(TAG, "PARTIAL: ${text.take(20)}")
        textManager.onText(text, TextManager.Mode.PARTIAL)
    }

    override fun onFinal(text: String) {
        // 兜底日志：任何问题（final 未到达/卡死/超时/降级）都能从这一行定位到 final 是否进来、长度多少
        CrashLogger.d(TAG, "onFinal len=${text.length}")
        // 本地标点恢复（断句加标点）：仅 sherpa 本地流式识别——流式 ASR 天生不输出标点，
        // 用本地 OfflinePunctuation 模型加标点（独立线程池执行，超时降级原文，见 PunctProvider.punctuate）；
        // Whisper 在线接口自带标点，不再重复加。
        // 硬约束：标点模型不可用（未下载/加载失败/返回空串/抛异常/超时）时降级为原始识别文本，
        // 保证 final 结果一定上屏，绝不让标点链路把识别结果吞掉。
        val toCommit = if (prefs.activeProvider == AppPrefs.PROVIDER_SHERPA) {
            runCatching { punctProvider.punctuate(text) }
                .getOrNull()
                .takeUnless { it.isNullOrBlank() }
                ?: text
        } else {
            text
        }
        // 焦点漂移检测：onStartInputView 绑定的编辑器包（boundEditorPackage）与当前 InputConnection
        // 指向的包不一致 = 幽灵提交（设置页输入框偷走焦点后，commit 会写进后台隐藏的 KoeType 自己输入框）。
        // 命中时直接丢弃本次 commit 并提示，不再走 commitWithFallback 兜底链（避免把结果写进错误连接）。
        val nowPkg = currentInputEditorInfo?.packageName
        // 诊断：检查瞬间 currentInputEditorInfo 是 null、旧值还是漂移值（上次漏检的关键日志）
        CrashLogger.d(TAG, "COMMIT-CHECK: bound=$boundEditorPackage now=$nowPkg")
        if (boundEditorPackage != null && nowPkg != null && nowPkg != boundEditorPackage) {
            CrashLogger.w(TAG, "FOCUS DRIFT: bound=$boundEditorPackage now=$nowPkg, drop commit to avoid ghost input")
            Toast.makeText(this, "输入焦点已切换到其他界面，请重新点击输入框后再说", Toast.LENGTH_SHORT).show()
            return
        }
        // 提交时刻拦截（下沉兜底）：三星系统后台抢焦点可能不触发 onStartInput 直接改
        // currentInputEditorInfo——提交前再查一次，指向 KoeType 自己就拦截并让位输入法。
        if (nowPkg == "com.drliuhuan.koetype") {
            CrashLogger.w(TAG, "COMMIT BLOCKED: editor is KoeType settings (bound=$boundEditorPackage), switching away")
            switchAwayFromSettings()
            Toast.makeText(this, "输入焦点在设置页，已切换到系统输入法", Toast.LENGTH_SHORT).show()
            // 焦点被设置页占死：收起键盘强制用户重新点击输入框，重新发起输入会话以恢复绑定
            runCatching { requestHideSelf(0) }.onFailure { }
            return
        }
        // 兜底日志：确认实际要上屏的文本（截断），"上屏了但不对/没上屏"可直接对账。
        // 注意：此日志在调用 onText 之前打，只代表 onFinal 到达，不代表 commitText 已执行；
        // 是否真正上屏以 TextManager 内的 commitText 返回值/兜底链日志为准。
        CrashLogger.d(TAG, "commit->onText(pkg=${currentInputEditorInfo?.packageName}): ${toCommit.take(20)}")
        val committed = textManager.onText(toCommit, TextManager.Mode.FINAL)
        startCorrection(committed)
    }

    override fun onError(message: String) {
        // 状态已经显示在键盘上，无需额外处理
        CrashLogger.w(TAG, "STT error: $message")
    }

    // ── LLM 纠错：说完后异步纠错断句，失败降级为原文 ────────────────

    /**
     * 先上屏原始识别文本，随后异步请求 LLM 纠错，结果回来后原位替换。
     * 对比报告要点：
     * - 在线请求带 max_tokens（512~1024），见 TextCorrector.buildRequestBody
     * - 不再 "结果与原文相同就静默丢弃"（D3）：无差异也走 applyCorrection（内部 no-op 安全）
     * - 失败（LLM 返回 null / 网络异常）降级为原文，不做额外处理
     * - 全程把纠错状态暴露给键盘（正在纠错/完成/失败），并累计 token 与耗时统计
     */
    private fun startCorrection(committed: String) {
        if (committed.isBlank()) {
            // 上屏链路异常时留痕（正常情况下 onFinal 已降级保证 non-blank），便于排查
            CrashLogger.w(TAG, "Committed text is blank, nothing to correct")
            return
        }
        if (!prefs.llmCorrectionEnabled) {
            CrashLogger.d(TAG, "LLM correction disabled, keeping original")
            return
        }

        val corrector = TextCorrectorFactory.fromPrefs(prefs)
        if (corrector == null) {
            CrashLogger.d(TAG, "LLM correction not configured (missing baseUrl/apiKey/model)")
            return
        }

        val capturedId = textManager.pendingCommitId
        val startTime = SystemClock.elapsedRealtime()
        correctionStateLD.value = CorrectionUiState(CorrectionStatus.CORRECTING)
        // 取消上一轮纠错遗留的自动复位回调，避免本轮进行中被打回 IDLE
        mainHandler.removeCallbacks(resetCorrectionRunnable)

        correctionScope.launch {
            val result = withContext(Dispatchers.IO) {
                // 词典词条（词汇+词性）以 "必须原样保留" 注入 prompt，防止 LLM 改掉用户自定义词
                val dictionary = CustomDictionary(prefs)
                val terms = dictionary.enabledEntries()
                // LLM 纠错启动：模式/原文长度/词库条数/自定义 prompt 长度
                CrashLogger.d(
                    TAG,
                    "LLM: start mode=${if (prefs.llmCorrectionMode == AppPrefs.LLM_MODE_LOCAL) "local" else "online"} textLen=${committed.length} dict=${terms.size} customPromptLen=${prefs.llmCustomPrompt.length}"
                )
                // 用户自述背景（职业/场景等）附加到 system prompt 末尾，帮助模型理解语境；空串则跳过
                corrector.correct(committed, terms, prefs.llmCustomPrompt)
            }
            val durationMs = SystemClock.elapsedRealtime() - startTime
            // LLM 纠错完成：耗时/是否成功/token 统计（与 start 成对，供全链路排查）
            CrashLogger.d(
                TAG,
                "LLM: done durationMs=$durationMs ok=${result != null && result.text.isNotBlank()} promptTokens=${result?.promptTokens ?: 0} completionTokens=${result?.completionTokens ?: 0}"
            )

            if (result == null || result.text.isBlank()) {
                CrashLogger.d(TAG, "LLM correction returned nothing, keeping original")
                prefs.addCorrectionAttempt(durationMs, 0, 0)
                showCorrectionResult(CorrectionUiState(CorrectionStatus.FAILED, durationMs), RESET_FAILED_MS)
                return@launch
            }

            // token 统计：无论是否过期都计入（请求已实际发生）
            prefs.addCorrectionAttempt(durationMs, result.promptTokens, result.completionTokens)

            // 期间用户又提交了新内容：本次纠错已过期，丢弃（不展示"完成"）
            if (capturedId != textManager.pendingCommitId) {
                CrashLogger.d(TAG, "Stale LLM correction, skipping: ${result.text}")
                correctionStateLD.value = CorrectionUiState()
                return@launch
            }

            val totalTokens = result.promptTokens + result.completionTokens
            showCorrectionResult(
                CorrectionUiState(CorrectionStatus.DONE, durationMs, totalTokens),
                RESET_DONE_MS
            )

            if (textManager.applyCorrection(result.text.trim(), capturedId)) {
                CrashLogger.d(TAG, "Applied LLM correction: ${result.text}")
            } else {
                CrashLogger.d(TAG, "LLM correction not applied in place: ${result.text}")
            }
        }
    }

    /** 展示纠错结果状态条，并在 [delayMs] 后自动回到 IDLE */
    private fun showCorrectionResult(state: CorrectionUiState, delayMs: Long) {
        correctionStateLD.value = state
        mainHandler.removeCallbacks(resetCorrectionRunnable)
        mainHandler.postDelayed(resetCorrectionRunnable, delayMs)
    }

    // ── 键盘按钮回调 ────────────────────────────────────────────────

    private val keyboardListener = object : KeyboardView.Listener {
        override fun micClick() {
            if (!hasMicPermission) {
                // 无权限：打开设置页申请
                openSettings()
                return
            }
            engine.toggle()
        }

        // 长按模式：按下开始录音（松开由 micPressUp 立即结束）
        override fun micPressDown() {
            if (!hasMicPermission) {
                openSettings()
                return
            }
            engine.pressDown()
        }

        override fun micPressUp() {
            engine.pressUp()
        }

        // 收起键盘（等价于宿主应用 hideSoftInputFromWindow），并切回上一个输入法
        override fun hideKeyboardClick() {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(window?.window?.decorView?.windowToken, 0)
            // 收起后切回上一个输入法（如 Gboard），下次点击输入框弹出上一个输入法。
            // 若当前就是默认输入法或没有上一个输入法，switchToPreviousInputMethod 返回 false，忽略即可。
            if (prefs.keyboardSwitchBack) {
                switchBackToPreviousIme()
            }
        }

        override fun backspaceClick() {
            textManager.backspace()
        }

        // 长按退格：开始连续删字（协程循环，初始延迟见手势阈值，之后每 60ms 删一个）
        override fun backspaceLongPressStart() {
            backspaceRepeatJob?.cancel()
            backspaceRepeatJob = imeScope.launch {
                while (isActive) {
                    textManager.backspace()
                    delay(Constants.BACKSPACE_REPEAT_DELAY_MS)
                }
            }
        }

        override fun backspaceLongPressStop() {
            backspaceRepeatJob?.cancel()
            backspaceRepeatJob = null
        }

        override fun enterClick() {
            if (prefs.smartEnter) {
                // 智能回车：跟随输入框声明的 IME_ACTION（搜索/发送/下一行/完成等）
                val action = (currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
                if (action == EditorInfo.IME_ACTION_UNSPECIFIED || action == EditorInfo.IME_ACTION_NONE) {
                    // 输入框未声明动作（多行文本等）：回退为固定换行
                    currentInputConnection?.commitText("\n", 1)
                } else {
                    // InputConnection.performEditorAction(Int) 是确定存在的公开 API，返回 Boolean 忽略；
                    // 连接为 null 时无法执行动作，回退为固定换行
                    val ic = currentInputConnection
                    if (ic != null) {
                        ic.performEditorAction(action)
                    } else {
                        currentInputConnection?.commitText("\n", 1)
                    }
                }
            } else {
                // 固定换行（默认）：直接插入换行符
                currentInputConnection?.commitText("\n", 1)
            }
        }

        override fun settingsClick() {
            openSettings()
        }

        override fun dictionaryClick() {
            openSettings(SettingsActivity.SECTION_DICTIONARY)
        }

        // LLM 按钮：点按切换纠错开关（未配置则弹配置），长按进 LLM 设置
        override fun llmClick() {
            if (!prefs.llmConfigured) {
                openSettings(SettingsActivity.SECTION_LLM)
                return
            }
            val enabled = !prefs.llmCorrectionEnabled
            prefs.llmCorrectionEnabled = enabled
            llmEnabledLD.value = enabled
        }

        override fun llmLongPress() {
            openSettings(SettingsActivity.SECTION_LLM)
        }

        // 语言切换：中/EN 持久化，符号全半角由语言状态驱动重渲染；sherpa 模式切换语言模型
        override fun languageClick() {
            val target = if (prefs.isKeyboardZh) AppPrefs.LANG_EN else AppPrefs.LANG_ZH
            if (prefs.activeProvider == AppPrefs.PROVIDER_SHERPA) {
                val modelDir = SherpaModelDownloader.findInstalledModelByLanguage(this@SayboardProIME, target)
                if (modelDir == null) {
                    val res = if (target == AppPrefs.LANG_EN) R.string.toast_en_model_missing
                    else R.string.toast_zh_model_missing
                    Toast.makeText(this@SayboardProIME, res, Toast.LENGTH_SHORT).show()
                    // 目标语言模型未下载：保持当前语言，只提示
                    return
                }
                // 模型路径变了：SttEngine 下次 start 时 ensureProvider 检测到 path 变化会自动重建 recognizer
                prefs.sherpaModelPath = modelDir.absolutePath
            } else {
                prefs.whisperLanguage = if (target == AppPrefs.LANG_EN) "en" else "zh"
            }
            prefs.keyboardLanguage = target
            languageLD.value = target
        }

        // 底行网格地球：弹系统输入法选择器
        override fun imePickerClick() {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        // 符号插入：逗号/句号/全半角符号列，直接 commitText
        override fun symbolClick(symbol: String) {
            textManager.insertText(symbol)
        }

        // 撤回最近一次 LLM 润色（状态条 DONE 态的"撤回"按钮）。
        // 一次撤回机会：原文已应用则换回原文并复位状态条；用户改过文本/光标移开则失效。
        override fun undoCorrectionClick() {
            if (textManager.undoCorrection()) {
                correctionStateLD.value = CorrectionUiState()
                mainHandler.removeCallbacks(resetCorrectionRunnable)
                Toast.makeText(this@SayboardProIME, R.string.toast_undo_applied, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@SayboardProIME, R.string.toast_cannot_undo, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 切回上一个输入法（如 Gboard）。
     *
     * 带防抖：收起键盘按钮（hideKeyboardClick）调用 hideSoftInputFromWindow 后，
     * 系统可能紧接着回调 onFinishInputView(finishingInput=true)——两处都会触发本方法。
     * switchToPreviousInputMethod 是"循环切换"语义，连续调两次会切回本输入法，
     * 因此用时间窗去重，保证一次收起只切换一次。返回 false（无上一个输入法）时忽略。
     */
    private fun switchBackToPreviousIme() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSwitchBackMs < SWITCH_BACK_DEBOUNCE_MS) return
        lastSwitchBackMs = now
        switchToPreviousInputMethod()
    }

    private fun openSettings(section: String? = null) {
        val intent = Intent(this, SettingsActivity::class.java)
        if (section != null) {
            intent.putExtra(SettingsActivity.EXTRA_SECTION, section)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun checkMicrophonePermission() {
        hasMicPermission = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "SayboardProIME"

        /** "切回上一个输入法"防抖窗口：收起按钮与 onFinishInputView 对同一次收起的触发间隔 */
        private const val SWITCH_BACK_DEBOUNCE_MS = 500L

        /** 纠错"完成"状态条的展示时长 */
        private const val RESET_DONE_MS = 4000L

        /** 纠错"失败"状态条的展示时长 */
        private const val RESET_FAILED_MS = 2000L
    }
}
