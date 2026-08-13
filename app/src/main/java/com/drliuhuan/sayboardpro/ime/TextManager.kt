package com.drliuhuan.sayboardpro.ime

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.Toast
import com.drliuhuan.sayboardpro.AppPrefs
import com.drliuhuan.sayboardpro.CrashLogger
import com.drliuhuan.sayboardpro.data.DictionaryPostProcessor

/** 刚提交到编辑器的一段文本，含绝对偏移（用于纠错定位）。 */
data class CommittedText(val text: String, val start: Int, val end: Int)

/**
 * 文本上屏管理：partial 用 setComposingText（实时预览），final 用 commitText（落盘）。
 * 移植自 Sayboard 的 TextManager，并增加词典后处理（[DictionaryPostProcessor]）
 * 与"说完后 LLM 纠错断句"的异步替换状态机（参考 Sayboard-2 的实现）。
 *
 * 门控放宽（对比报告 P1#5/6）：
 * - PARTIAL（新的语音 partial）不再把 pending 纠错判为"用户已编辑"；
 * - 不再因为光标移出 committed 范围就放弃纠错，替换时只校验 committed 仍在文档中。
 */
class TextManager(
    private val ime: SayboardProIME,
    private val postProcessor: DictionaryPostProcessor
) {

    private val prefs = AppPrefs(ime)

    private var addSpace = false
    private var capitalize = true
    private var firstSinceResume = true
    private var composing = false

    // ── 纠错状态 ────────────────────────────────────────────────────
    private var lastCommitted: CommittedText? = null
    private var awaitingCorrection = false
    private var commitId = 0L

    /** 最近一次已应用的 LLM 纠错的撤回记录（只保留最后一次，一次撤回机会） */
    private var undoRecord: CorrectionUndoRecord? = null

    /** 最近一次提交的单调递增 id，用于判定纠错结果是否过期 */
    val pendingCommitId: Long
        get() = commitId

    fun onUpdateSelection(newSelStart: Int, newSelEnd: Int) {
        if (!composing && newSelStart == newSelEnd) {
            checkAddSpaceAndCapitalize()
        }
        // 放宽门控：光标移出 committed 范围不再标记为"用户编辑"，
        // 替换时以文本存在性检查为准（见 applyCorrection）。
    }

    /**
     * 上屏一段文本，返回实际提交到编辑器的文本（供纠错使用）。
     * @param text 原始识别文本（尚未做词典后处理）
     * @param mode PARTIAL=composing 预览，FINAL=commit，INSERT=直接插入（键盘按钮）
     */
    fun onText(text: String, mode: Mode): String = doOnText(text, mode, retry = false)

    /**
     * onText 的真正实现。[retry] 标记本次调用是 ic 为 null 后的延迟重试：
     * 只重试一次（避免递归死循环），重试仍拿不到连接才丢弃。
     */
    private fun doOnText(text: String, mode: Mode, retry: Boolean): String {
        if (text.isEmpty()) return ""

        if (firstSinceResume) {
            firstSinceResume = false
            checkAddSpaceAndCapitalize()
        }

        val ic = ime.currentInputConnection
        if (ic == null) {
            val pkg = ime.currentInputEditorInfo?.packageName
            if (retry) {
                // 重试仍无连接：丢弃并留痕（只重试一次）
                CrashLogger.w(TAG, "Retry still no input connection, drop text (pkg=$pkg)")
            } else {
                // 诊断：区分"微信场景 ic 为 null"与"commitText 被拒绝"；InputConnection
                // 刚建立时可能未就绪，100ms 后重取连接完整重试一次。
                CrashLogger.w(TAG, "No input connection, drop text (pkg=$pkg), schedule one retry")
                Handler(Looper.getMainLooper()).postDelayed({
                    doOnText(text, mode, retry = true)
                }, RETRY_DELAY_MS)
            }
            return ""
        }

        var result = text
        if (mode != Mode.INSERT) {
            // 词典后处理只作用于语音识别结果
            val processed = try {
                postProcessor.process(result)
            } catch (e: Throwable) {
                // 兜底（关键）：词典后处理任何异常都降级为原文上屏，绝不让它阻塞 commitText。
                // 用 Throwable 而非 Exception——OOM/链接错误等 Error 同样不该吞掉识别结果。
                CrashLogger.w(TAG, "Dictionary post-process crashed, keeping original: ${e.message}")
                text
            }
            // 兜底：后处理异常返回空串时降级为原文，绝不让识别结果被吞掉（问题 1）
            result = if (processed.isEmpty()) {
                CrashLogger.w(TAG, "Dictionary post-process returned empty, keeping original")
                text
            } else {
                processed
            }
        }
        if (result.isEmpty()) return ""

        if (prefs.autoCapitalize && capitalize && result.isNotEmpty()) {
            result = result[0].uppercase() + result.substring(1)
        }
        if (addSpace) {
            result = " $result"
        }

        when (mode) {
            Mode.FINAL, Mode.STANDARD -> {
                addSpace = addSpaceAfter(result[result.length - 1])
                capitalizeAfter(result)?.let { capitalize = it }
                composing = false
                val start = commitStart(ic)
                // 兜底成功才算真正提交：recordCommit 只在 commitText 真正上屏后调用，
                // 避免纠错链路拿到未上屏的文本（微信拒绝 commitText 时走兜底链）。
                if (commitWithFallback(ic, result, recordOnSuccess = true)) {
                    recordCommit(CommittedText(result, start, start + result.length))
                } else {
                    // 提交失败（含延迟重试仍在途）：不让纠错链路拿到未上屏的文本
                    return ""
                }
            }

            Mode.PARTIAL -> {
                // 新的 partial 不算用户编辑：纠错窗口内继续说话不使 pending 失效
                composing = true
                ic.setComposingText(result, 1)
            }

            Mode.INSERT -> {
                // 用户手动按键（键盘按钮）：视为用户编辑，作废 pending 纠错与撤回记录
                if (awaitingCorrection) clearPending() else undoRecord = null
                composing = false
                if (!commitWithFallback(ic, result, recordOnSuccess = false)) {
                    return ""
                }
            }
        }
        return result
    }

    /**
     * 提交文本到编辑器，带微信等自绘输入框的兜底链。
     * 原样 commitText 返回 true 后先读回验证（[commitAndVerify]），确认内容真的落盘——
     * 飞书等绝大多数应用直接通过；微信等自绘输入框可能"幽灵提交"（返回 true 但界面无字），
     * 验证失败即进入兜底链：
     *  1. batch 包裹重试（begin/endBatchEdit 包裹 commitAndVerify）；
     *  2. composing 路径（setComposingText + finishComposingText，自绘输入框兼容性更好）；
     *  3. 主线程延迟重试（100ms 后重新取 currentInputConnection，不用旧引用）。
     * 每步提交后都读回验证，且提交前先 textLanded 防重（避免验证误判导致重复上屏）。
     * 任一成功返回 true（FINAL 调用方据此 recordCommit）。[recordOnSuccess] 控制延迟
     * 重试成功后是否 recordCommit：FINAL 需要（纠错定位），INSERT 不需要（手动插入）。
     */
    private fun commitWithFallback(ic: InputConnection, text: String, recordOnSuccess: Boolean): Boolean {
        val pkg = ime.currentInputEditorInfo?.packageName

        // 原路径：commitText 返回 true 后读回验证，确认内容真的落盘。飞书等大多数应用
        // 直接通过；微信等自绘输入框可能"幽灵提交"（返回 true 但界面无字），验证会抓住它。
        if (commitAndVerify(ic, text)) return true
        // 关键诊断日志：commitText 成功但文本未落盘（或 commitText 被拒），开始兜底链。
        CrashLogger.w(TAG, "commit not landed after commitText (pkg=$pkg), trying fallbacks")

        // 兜底 1：batch 包裹重试。先 textLanded 检查——验证可能误判（读回恰好失败），
        // 若文本其实已落盘则直接成功，防止重复上屏。
        if (textLanded(ic, text)) {
            CrashLogger.d(TAG, "text already landed (pkg=$pkg)")
            return true
        }
        CrashLogger.w(TAG, "commitText rejected by app (pkg=$pkg), trying batch fallback")
        ic.beginBatchEdit()
        val batchOk = try {
            commitAndVerify(ic, text)
        } finally {
            ic.endBatchEdit()
        }
        if (batchOk) {
            CrashLogger.d(TAG, "commit succeeded via batch fallback (pkg=$pkg)")
            return true
        }

        // 兜底 2：composing 路径——微信等自绘输入框对 composing 兼容性更好。
        // 提交后同样 textLanded 验证，确认真的落盘（不信任 finishComposingText 返回值）。
        CrashLogger.w(TAG, "batch fallback failed (pkg=$pkg), trying composing path")
        ic.setComposingText(text, 1)
        ic.finishComposingText()
        if (textLanded(ic, text)) {
            CrashLogger.d(TAG, "commit succeeded via composing fallback (pkg=$pkg)")
            return true
        }

        // 兜底 3：主线程延迟重试。onFinal 在 main 线程执行，postDelayed 100ms 后
        // InputConnection 通常已就绪；重试必须重新取 currentInputConnection，不用旧引用。
        CrashLogger.w(TAG, "composing path failed (pkg=$pkg), scheduling delayed retry")
        Handler(Looper.getMainLooper()).postDelayed({
            val freshIc = ime.currentInputConnection
            if (freshIc == null) {
                CrashLogger.w(TAG, "Delayed retry no input connection (pkg=$pkg), drop")
                return@postDelayed
            }
            freshIc.beginBatchEdit()
            try {
                // 偏移必须用新连接重新算（光标可能已移动）
                val freshStart = commitStart(freshIc)
                // 防重：延迟期间幽灵提交可能已落盘，先读回检查，避免重复上屏
                if (textLanded(freshIc, text)) {
                    CrashLogger.d(TAG, "text already landed on delayed retry (pkg=$pkg)")
                    if (recordOnSuccess) {
                        recordCommit(CommittedText(text, freshStart, freshStart + text.length))
                    }
                } else if (commitAndVerify(freshIc, text)) {
                    CrashLogger.d(TAG, "commit succeeded via delayed retry (pkg=$pkg)")
                    if (recordOnSuccess) {
                        recordCommit(CommittedText(text, freshStart, freshStart + text.length))
                    }
                } else {
                    CrashLogger.w(TAG, "Delayed retry commitText rejected again (pkg=$pkg), drop")
                    // 兜底链全灭：给用户即时反馈（幽灵提交/焦点漂移时不再无声失败）
                    try {
                        Toast.makeText(
                            ime,
                            "上屏失败：输入焦点可能已切换到其他界面，请重新点击输入框",
                            Toast.LENGTH_SHORT
                        ).show()
                    } catch (e: Exception) {
                        // Toast 失败不影响主流程
                    }
                }
            } finally {
                freshIc.endBatchEdit()
            }
        }, RETRY_DELAY_MS)
        return false
    }

    /**
     * 提交并验证：commitText 返回 true 不代表内容真的落盘（微信等自绘输入框会"幽灵提交"：
     * 返回 true 但界面无字）。提交后读回光标前文本，验证是否以 [text] 结尾。
     * 读回失败（连接异常等）时信任 commitText 返回值，避免误伤正常 app。
     */
    private fun commitAndVerify(ic: InputConnection, text: String): Boolean {
        if (!ic.commitText(text, 1)) return false
        val checkLen = maxOf(text.length, 8)
        return try {
            val before = ic.getTextBeforeCursor(checkLen, 0)?.toString()
            before != null && before.endsWith(text)
        } catch (e: Exception) {
            true // 无法读回：信任 commitText 返回值
        }
    }

    /** 文本是否已实际落在光标前（防兜底重试造成重复上屏） */
    private fun textLanded(ic: InputConnection, text: String): Boolean =
        try {
            val before = ic.getTextBeforeCursor(maxOf(text.length, 8), 0)?.toString()
            before != null && before.endsWith(text)
        } catch (e: Exception) {
            false
        }

    /**
     * 键盘退格：删除光标前一个 Unicode 码点（长按连续退格由 IME 协程循环调用本方法）。
     * 手动编辑，作废 pending 纠错（用户正在改动文本，过期的 LLM 纠错不该再上屏）。
     */
    fun backspace() {
        val ic = ime.currentInputConnection ?: return
        // 用户手动编辑：纠错 pending 与已应用的撤回记录一并作废
        if (awaitingCorrection) clearPending() else undoRecord = null
        composing = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ic.deleteSurroundingTextInCodePoints(1, 0)
        } else {
            ic.deleteSurroundingText(1, 0)
        }
    }

    /**
     * 键盘按钮直接插入文本（符号/空格/标点）：commitText 上屏。
     * 不走 [Mode.INSERT] 的自动空格/自动大写逻辑——插入标点时不应在前面补空格
     * （如中文逗号要紧贴前文）；手动编辑，作废 pending 纠错。
     */
    fun insertText(text: String) {
        if (text.isEmpty()) return
        val ic = ime.currentInputConnection ?: return
        // 用户手动编辑：纠错 pending 与已应用的撤回记录一并作废
        if (awaitingCorrection) clearPending() else undoRecord = null
        composing = false
        // 直接插入也走兜底链（带读回验证）：微信等自绘输入框可能幽灵提交。INSERT 不记录纠错
        commitWithFallback(ic, text, recordOnSuccess = false)
    }

    // ── 纠错替换 ────────────────────────────────────────────────────

    /** 纠错结果 [capturedId]（在提交时捕获）是否仍可应用 */
    fun shouldReplace(capturedId: Long): Boolean =
        awaitingCorrection && lastCommitted != null && capturedId == commitId

    /**
     * 用 [corrected] 原位替换最近一次提交的文本。返回 true 表示已处理
     * （应用成功，或无差异的 no-op），false 表示应在原文基础上放弃/给建议。
     *
     * 放宽门控：只要 committed 文本仍存在于光标前的文档中即可替换，不再要求
     * "光标前文本与 committed 完全一致"。
     */
    fun applyCorrection(corrected: String, capturedId: Long): Boolean {
        val committed = lastCommitted ?: return false
        if (!shouldReplace(capturedId)) return false

        // committed 可能以自动补的空格开头（英文模式），保留分隔空格
        val finalCorrected =
            if (committed.text.startsWith(" ") && !corrected.startsWith(" ")) " $corrected" else corrected

        if (finalCorrected == committed.text) {
            // 无差异：不再静默丢弃（对比报告 D3），走 no-op 清理并打日志
            clearPending()
            CrashLogger.d(TAG, "Correction no-op: result identical to committed text")
            return true
        }

        val ic = ime.currentInputConnection ?: return false

        // 提前停止跟踪：InputConnection 调用会触发 onUpdateSelection，
        // 但此处无需再把它标记为用户编辑。
        awaitingCorrection = false

        val applied = replaceInPlace(ic, committed.text, finalCorrected)
        clearPending()
        // 撤回记录必须在 clearPending（会清空 undoRecord）之后写入，保证"一次撤回机会"：
        // 本次纠错确实替换了文本才记录；no-op（无差异）不产生撤回记录。
        if (applied) {
            undoRecord = CorrectionUndoRecord(
                originalText = committed.text,
                correctedText = finalCorrected,
                commitId = capturedId
            )
        }
        return applied
    }

    /**
     * 撤回最近一次已应用的 LLM 纠错：把纠错后的文本替换回原文。
     *
     * 边界校验（与 applyCorrection 的 stale 校验同思路，用实际文本比对兜底）：
     * 仅当光标前文本仍以纠错结果结尾——即该段文本未被用户修改、光标仍在纠错结果之后——
     * 才执行替换；否则撤回记录已失效，返回 false（调用方 Toast"无法撤回"）。
     * 撤回成功后记录清空，只有一次撤回机会。
     */
    fun undoCorrection(): Boolean {
        val record = undoRecord ?: return false
        val ic = ime.currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(record.correctedText.length + 32, 0)?.toString() ?: return false
        if (!before.endsWith(record.correctedText)) {
            // 用户修改过该段文本或光标已移开：撤回记录失效
            undoRecord = null
            return false
        }
        ic.beginBatchEdit()
        try {
            val codePoints = record.correctedText.codePointCount(0, record.correctedText.length)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ic.deleteSurroundingTextInCodePoints(codePoints, 0)
            } else {
                ic.deleteSurroundingText(record.correctedText.length, 0)
            }
            ic.commitText(record.originalText, 1)
        } finally {
            ic.endBatchEdit()
        }
        undoRecord = null
        return true
    }

    /**
     * 原位替换 committed 文本。
     * 优先走"committed 恰好紧邻光标左侧"的正常路径；若光标已移开，则在光标前的
     * 一段窗口里找到 committed 的最近一次出现，把光标移过去再删改。
     * 找不到则返回 false。
     */
    private fun replaceInPlace(ic: InputConnection, committed: String, corrected: String): Boolean {
        val windowLen = maxOf(committed.length + 32, 64)
        val before = ic.getTextBeforeCursor(windowLen, 0)?.toString() ?: return false
        val idx = before.lastIndexOf(committed)
        if (idx < 0) return false

        // committed 结尾到光标之间有多少字符（>0 说明光标已移开）
        val gap = before.length - idx - committed.length

        ic.beginBatchEdit()
        try {
            if (gap > 0) {
                // 需要把光标移到 committed 结尾：用 extracted text 的绝对偏移定位
                val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
                val cursorPos = extracted?.selectionStart ?: -1
                if (cursorPos < 0) return false
                val committedEnd = cursorPos - gap
                if (!ic.setSelection(committedEnd, committedEnd)) return false
            }
            val codePoints = committed.codePointCount(0, committed.length)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ic.deleteSurroundingTextInCodePoints(codePoints, 0)
            } else {
                ic.deleteSurroundingText(committed.length, 0)
            }
            // 删除原文后提交纠错结果并读回验证：微信等自绘输入框可能"幽灵提交"。
            if (commitAndVerify(ic, corrected)) return true
            // 验证失败：补一次 composing 路径（自绘输入框兼容性更好）；仍失败返回 false，
            // 调用方 applyCorrection 已有 "not applied" 日志路径。
            ic.setComposingText(corrected, 1)
            ic.finishComposingText()
            if (textLanded(ic, corrected)) return true
            return false
        } finally {
            ic.endBatchEdit()
        }
    }

    private fun checkAddSpaceAndCapitalize() {
        val cs = ime.currentInputConnection?.getTextBeforeCursor(3, 0) ?: return
        if (cs.isNotEmpty()) {
            addSpace = addSpaceAfter(cs[cs.length - 1])
            capitalizeAfter(cs)?.let { capitalize = it }
        }
    }

    private fun capitalizeAfter(string: CharSequence): Boolean? {
        for (char in string.reversed()) {
            if (char.isLetterOrDigit()) return false
            if (char in sentenceTerminator) return true
        }
        return null
    }

    /**
     * 自动空格（Sayboard 原版遗留，给英文输入用）：句尾标点后置 addSpace=true，下一个
     * commit 开头自动补一个空格。中文语音输入不需要词间空格（sherpa 中文模型输出已 trim），
     * 原逻辑在"。"后置 true，导致第二句 commit 开头出现前导空格（问题 2 根因）。
     * 统一禁用：恒返回 false，addSpace 保持 false，[onText] 中 `" $result"` 分支不再触发。
     */
    private fun addSpaceAfter(char: Char): Boolean = false

    /** 下一个 commit 的绝对起始偏移，或 -1（编辑器无法给出） */
    private fun commitStart(ic: InputConnection): Int {
        return try {
            val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
            if (extracted != null) extracted.startOffset + extracted.selectionStart else -1
        } catch (e: Exception) {
            CrashLogger.e(TAG, "Failed to get commit start", e)
            -1
        }
    }

    private fun recordCommit(committed: CommittedText) {
        lastCommitted = if (committed.start >= 0) committed else null
        awaitingCorrection = true
        commitId++
        // 新一轮上屏开始：上一轮的撤回机会作废（用户已进入新的输入流程）
        undoRecord = null
    }

    private fun clearPending() {
        awaitingCorrection = false
        lastCommitted = null
        undoRecord = null
    }

    fun onResume() {
        firstSinceResume = true
        // 新的输入会话：作废上一个编辑器的 pending 纠错
        clearPending()
    }

    enum class Mode {
        STANDARD, PARTIAL, FINAL, INSERT
    }

    companion object {
        private const val TAG = "TextManager"

        /** ic 为 null 或 commitText 被拒后的延迟重试延迟（InputConnection 刚建立时可能未就绪） */
        private const val RETRY_DELAY_MS = 100L

        private val sentenceTerminator = charArrayOf('.', '\n', '!', '?', '。', '！', '？')
    }
}

/** LLM 纠错撤回记录：原文 + 已应用的纠错结果 + 对应 commitId（一次撤回机会，仅 TextManager 内部使用）。 */
private data class CorrectionUndoRecord(
    val originalText: String,
    val correctedText: String,
    val commitId: Long
)
