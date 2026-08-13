package com.drliuhuan.sayboardpro.ime

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.MutableLiveData
import com.drliuhuan.sayboardpro.AppPrefs
import com.drliuhuan.sayboardpro.Constants
import com.drliuhuan.sayboardpro.R
import com.drliuhuan.sayboardpro.stt.SttEngineClient
import com.drliuhuan.sayboardpro.stt.SttEngineClient.State
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * 键盘主界面（Compose AbstractComposeView），风格参考 Sayboard 的 ViewManager。
 *
 * 布局（用户重新规划）：
 * - 首行：左「返回箭头」（收起键盘并切回上一个输入法）+ provider 名；
 *   右侧从右往左依次：退格（长按连删）、Spacer、齿轮（设置）、字典（直达词典 Tab）、LLM 按钮。
 * - 中部：大麦克风 + 状态文字 + 音量条 + 两侧全半角符号列（左：？ ！ ： …；右：； ~ （） “”）。
 * - 底行：左「网格地球」（弹输入法选择器）+ 语言按钮（中/EN）；
 *   中「空格长条」（左侧逗号，右侧句号）；右「回车换行」。
 *
 * 麦克风手势：点按与长按同时生效，无需设置切换——
 * - 点按（短按）：开始识别，再按一下立即结束；停止说话自动结束（静音检测）
 * - 长按（按住 ≥ [LONG_PRESS_THRESHOLD_MS]）：按下开始识别，松开立即结束
 */
@SuppressLint("ViewConstructor")
class KeyboardView(
    private val ime: Context,
    private val engine: SttEngineClient,
    private val correctionState: MutableLiveData<CorrectionUiState>,
    private val languageState: MutableLiveData<String>,
    private val llmEnabledState: MutableLiveData<Boolean>,
    private val listener: Listener
) : AbstractComposeView(ime) {

    /** 读键盘高度档位/智能回车等键盘行为设置（在每次 attach 组合时重新读取，见 Content） */
    private val prefs = AppPrefs(ime)

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }

    @Composable
    override fun Content() {
        val state by engine.stateLD.observeAsState()
        val volume by engine.volumeLD.observeAsState(0f)
        val errorMsg by engine.errorMessageLD.observeAsState("")
        val providerName by engine.providerNameLD.observeAsState("")
        val correction by correctionState.observeAsState(CorrectionUiState())
        val language by languageState.observeAsState(AppPrefs.LANG_ZH)
        val llmEnabled by llmEnabledState.observeAsState(false)
        val isZh = language == AppPrefs.LANG_ZH

        val isPortrait =
            LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
        // 键盘高度档位：低=30%屏高（默认）/中=40%/高=50%；横屏屏幕矮，占比适当加成
        val baseFraction = prefs.keyboardHeightFraction
        val heightFraction =
            if (isPortrait) baseFraction else (baseFraction + 0.15f).coerceAtMost(0.65f)
        val height = (LocalConfiguration.current.screenHeightDp * heightFraction).toInt().dp

        SayboardProTheme {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
                    .background(MaterialTheme.colors.background)
            ) {
                // ── 首行：返回箭头 / provider 名 / 右侧（LLM、字典、齿轮、退格） ──
                TopRow(providerName, llmEnabled)

                // ── LLM 纠错状态条（进行中/完成/失败，IDLE 不渲染） ──
                CorrectionStatusBar(correction)

                // ── 中部：左符号列 + 大麦克风 + 右符号列 ──
                MiddleArea(state, errorMsg, volume, isZh, heightFraction)

                // ── 底行：输入法选择 / 语言 / 逗号 + 空格 + 句号 / 回车 ──
                BottomRow(isZh)
            }
        }
    }

    // ── 首行 ─────────────────────────────────────────────────────────

    @Composable
    private fun TopRow(providerName: String, llmEnabled: Boolean) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左：返回箭头——收起键盘并切回上一个输入法
            IconButton(onClick = { listener.hideKeyboardClick() }) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.ime_hide_keyboard)
                )
            }
            // provider 名称（识别引擎展示名）
            Text(
                text = providerName,
                style = MaterialTheme.typography.caption.copy(fontSize = 14.sp),
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Spacer(modifier = Modifier.weight(1f))
            // 右侧集群，视觉从右往左：退格、Spacer、齿轮、字典、LLM
            // （Row 内从左到右排：LLM、字典、齿轮、Spacer、退格）
            LlmButton(llmEnabled)
            IconButton(onClick = { listener.dictionaryClick() }) {
                Icon(
                    painter = painterResource(R.drawable.ic_dictionary),
                    contentDescription = stringResource(R.string.ime_dictionary)
                )
            }
            IconButton(onClick = { listener.settingsClick() }) {
                Icon(
                    painter = painterResource(R.drawable.ic_settings),
                    contentDescription = stringResource(R.string.ime_open_settings)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            BackspaceButton()
        }
    }

    /** LLM 按钮：文字 "LLM"，开启时显示 "LLM ✓"；点按开关/弹配置，长按进 LLM 设置 */
    @Composable
    private fun LlmButton(llmEnabled: Boolean) {
        val bg = if (llmEnabled) {
            MaterialTheme.colors.primary.copy(alpha = 0.15f)
        } else {
            MaterialTheme.colors.surface
        }
        Box(
            modifier = Modifier
                .padding(horizontal = 2.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(bg)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var cancelled = false
                        val up = withTimeoutOrNull(LONG_PRESS_THRESHOLD_MS) {
                            val result = waitForUpOrCancellation()
                            if (result == null) {
                                cancelled = true
                            }
                            result
                        }
                        if (up == null && !cancelled) {
                            // 长按：进入 LLM 纠错设置
                            waitForUpOrCancellation()
                            listener.llmLongPress()
                        } else if (up != null) {
                            // 点按：切换开关（未配置时由 IME 改为弹配置界面）
                            listener.llmClick()
                        }
                    }
                }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (llmEnabled) "LLM ✓" else "LLM",
                style = MaterialTheme.typography.subtitle2.copy(fontSize = 16.sp),
                color = if (llmEnabled) {
                    MaterialTheme.colors.primary
                } else {
                    MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                }
            )
        }
    }

    /** 退格按钮：点按删一个字符；长按（≥ 400ms）连续删，每 60ms 删一个，松开停止 */
    @Composable
    private fun BackspaceButton() {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colors.surface)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var cancelled = false
                        val up = withTimeoutOrNull(Constants.BACKSPACE_REPEAT_START_DELAY_MS) {
                            val result = waitForUpOrCancellation()
                            if (result == null) {
                                cancelled = true
                            }
                            result
                        }
                        if (up == null && !cancelled) {
                            // 超过初始延迟仍未抬起：长按连续退格，松开停止
                            listener.backspaceLongPressStart()
                            waitForUpOrCancellation()
                            listener.backspaceLongPressStop()
                        } else if (up != null) {
                            // 阈值内抬起：单删一个
                            listener.backspaceClick()
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_backspace),
                contentDescription = stringResource(R.string.ime_backspace),
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.size(24.dp)
            )
        }
    }

    // ── 中部：符号列 + 大麦克风 ─────────────────────────────────────

    @Composable
    private fun ColumnScope.MiddleArea(
        state: State?,
        errorMsg: String,
        volume: Float,
        isZh: Boolean,
        heightFraction: Float
    ) {
        // 键盘高度收缩到 30% 后，大麦克风/图标按比例缩小，避免中部溢出
        val micBoxSize = (heightFraction * 220).dp
        val micIconSize = (heightFraction * 132).dp
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左符号列：问号 叹号 冒号 省略号
            SymbolColumn(
                symbols = LEFT_SYMBOLS,
                isZh = isZh,
                onSymbolClick = { listener.symbolClick(it) }
            )
            // 中央：大麦克风 + 状态 + 音量
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // 点按与长按同时生效，手势状态机（见类注释）：
                    // 按下后等阈值判定——超过 300ms 视为长按（micPressDown 开始，松开 micPressUp 结束）；
                    // 未到阈值就抬起视为点按（micClick 走 toggle：未在听则开始，正在听则立即结束）。
                    Box(
                        modifier = Modifier
                            .size(micBoxSize)
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    // 受限的 AwaitPointerEventScope 内不能用 coroutineScope/launch/delay 计时，
                                    // 改用 withTimeoutOrNull：300ms 内抬起 → 返回 up（点按）；
                                    // 超时 → 取消等待并返回 null（判定长按），随后再等松开。
                                    var cancelled = false
                                    val up = withTimeoutOrNull(LONG_PRESS_THRESHOLD_MS) {
                                        val result = waitForUpOrCancellation()
                                        if (result == null) {
                                            // 阈值内手势被取消（手指划走/系统取消）：不触发任何动作
                                            cancelled = true
                                        }
                                        result
                                    }
                                    if (up == null && !cancelled) {
                                        // 超过阈值（300ms）仍未抬起 → 长按：按下开始识别，松开立即结束
                                        listener.micPressDown()
                                        waitForUpOrCancellation()
                                        listener.micPressUp()
                                    } else if (up != null) {
                                        // 阈值内抬起 → 点按
                                        listener.micClick()
                                    }
                                    // cancelled：未到阈值就被取消，无动作
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        MicIcon(state, micIconSize)
                    }
                    Text(
                        text = statusText(state, errorMsg),
                        style = MaterialTheme.typography.h6.copy(fontSize = 22.sp)
                    )
                    if (state == State.LISTENING) {
                        LinearProgressIndicator(
                            progress = volume,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .width(160.dp)
                                .height(6.dp)
                        )
                    }
                }
            }
            // 右符号列：分号 波浪线 括号 引号
            SymbolColumn(
                symbols = RIGHT_SYMBOLS,
                isZh = isZh,
                onSymbolClick = { listener.symbolClick(it) }
            )
        }
    }

    /** 一侧符号列：4 个符号按钮垂直均分中部高度（weight=1f，零间距）；显示与插入随 中/EN 全半角切换 */
    @Composable
    private fun SymbolColumn(
        symbols: List<SymbolKey>,
        isZh: Boolean,
        onSymbolClick: (String) -> Unit
    ) {
        Column(
            modifier = Modifier
                .width(52.dp)
                .fillMaxHeight()
                .padding(vertical = 2.dp)
        ) {
            symbols.forEach { key ->
                val label = if (isZh) key.zh else key.en
                SymbolButton(label) { onSymbolClick(label) }
            }
        }
    }

    @Composable
    private fun ColumnScope.SymbolButton(label: String, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colors.surface)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.body1.copy(fontSize = 20.sp),
                maxLines = 1
            )
        }
    }

    // ── 底行 ─────────────────────────────────────────────────────────

    @Composable
    private fun BottomRow(isZh: Boolean) {
        // 逗号/句号跟随语言全半角：中文=，。 英文=,.
        val comma = if (isZh) "，" else ","
        val period = if (isZh) "。" else "."
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // 网格地球：弹系统输入法选择器
            IconButton(onClick = { listener.imePickerClick() }) {
                Icon(
                    painter = painterResource(R.drawable.ic_language),
                    contentDescription = stringResource(R.string.ime_switch_keyboard)
                )
            }
            // 语言按钮：中文 "中" / 英文 "EN"，点击切换
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colors.surface)
                    .clickable { listener.languageClick() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isZh) "中" else "EN",
                    style = MaterialTheme.typography.body1.copy(fontSize = 16.sp),
                    color = MaterialTheme.colors.primary
                )
            }
            // 逗号（空格左侧）
            BottomSymbolButton(comma) { listener.symbolClick(comma) }
            // 空格长条（始终半角 " "）
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colors.surface)
                    .clickable { listener.symbolClick(" ") },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.ime_space),
                    style = MaterialTheme.typography.caption.copy(fontSize = 16.sp),
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f)
                )
            }
            // 句号（空格右侧）
            BottomSymbolButton(period) { listener.symbolClick(period) }
            // 回车：智能（跟随输入框动作）/固定换行由 IME 层按 smart_enter 设置分派
            IconButton(onClick = { listener.enterClick() }) {
                Icon(
                    painter = painterResource(R.drawable.ic_enter),
                    contentDescription = stringResource(R.string.ime_enter_newline)
                )
            }
        }
    }

    @Composable
    private fun BottomSymbolButton(label: String, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colors.surface)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(label, style = MaterialTheme.typography.body1.copy(fontSize = 22.sp))
        }
    }

    // ── 公共小组件 ───────────────────────────────────────────────────

    @Composable
    private fun MicIcon(state: State?, size: Dp) {
        Icon(
            painter = painterResource(R.drawable.ic_mic),
            contentDescription = null,
            tint = if (state == State.LISTENING) {
                MaterialTheme.colors.primary
            } else {
                MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
            },
            modifier = Modifier.size(size)
        )
    }

    @Composable
    private fun statusText(state: State?, errorMsg: String): String {
        if (state == State.ERROR) return errorMsg.ifEmpty { stringResource(R.string.ime_status_error) }
        return when (state) {
            State.PREPARING -> stringResource(R.string.ime_status_preparing)
            State.LISTENING -> stringResource(R.string.ime_status_listening)
            State.PROCESSING -> stringResource(R.string.ime_status_processing)
            else -> stringResource(R.string.ime_status_ready)
        }
    }

    @Composable
    private fun CorrectionStatusBar(correction: CorrectionUiState) {
        if (correction.status == CorrectionStatus.IDLE) return
        val message = when (correction.status) {
            CorrectionStatus.CORRECTING -> stringResource(R.string.ime_correcting)
            CorrectionStatus.DONE -> {
                val seconds = correction.durationMs / 1000.0
                val speed = if (correction.durationMs > 0) {
                    (correction.totalTokens / seconds).toInt()
                } else {
                    correction.totalTokens
                }
                stringResource(
                    R.string.ime_correction_done,
                    String.format(Locale.US, "%.1fs", seconds),
                    speed.toString()
                )
            }
            CorrectionStatus.FAILED -> stringResource(R.string.ime_correction_failed)
            else -> return
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (correction.status == CorrectionStatus.CORRECTING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = message,
                style = MaterialTheme.typography.caption.copy(fontSize = 14.sp),
                color = when (correction.status) {
                    CorrectionStatus.FAILED -> MaterialTheme.colors.error
                    CorrectionStatus.DONE -> MaterialTheme.colors.primary
                    else -> MaterialTheme.colors.onSurface
                }
            )
            // 纠错完成：提供"撤回"按钮，一键把 LLM 结果换回原文（一次撤回机会，见 TextManager.undoCorrection）
            if (correction.status == CorrectionStatus.DONE) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.ime_correction_undo),
                    style = MaterialTheme.typography.caption.copy(fontSize = 14.sp),
                    color = MaterialTheme.colors.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colors.surface)
                        .clickable { listener.undoCorrectionClick() }
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
    }

    companion object {
        /** 按住超过该时长判定为长按（否则为短按/点按）。 */
        private const val LONG_PRESS_THRESHOLD_MS = 300L

        /** 大按钮左侧符号列（从上到下）：问号、叹号、冒号、省略号 */
        private val LEFT_SYMBOLS = listOf(
            SymbolKey("？", "?"),
            SymbolKey("！", "!"),
            SymbolKey("：", ":"),
            SymbolKey("…", "...")
        )

        /** 大按钮右侧符号列（从上到下）：分号、波浪线、括号、引号（波浪线始终半角） */
        private val RIGHT_SYMBOLS = listOf(
            SymbolKey("；", ";"),
            SymbolKey("~", "~"),
            SymbolKey("（）", "()"),
            SymbolKey("“”", "\"\"")
        )
    }

    interface Listener {
        fun micClick()
        fun micPressDown()
        fun micPressUp()
        fun hideKeyboardClick()
        fun backspaceClick()
        fun backspaceLongPressStart()
        fun backspaceLongPressStop()
        fun enterClick()
        fun settingsClick()
        fun dictionaryClick()
        fun llmClick()
        fun llmLongPress()
        fun languageClick()
        fun imePickerClick()
        fun symbolClick(symbol: String)
        fun undoCorrectionClick()
    }
}

/** LLM 纠错状态（IME 驱动，KeyboardView 观察渲染） */
enum class CorrectionStatus { IDLE, CORRECTING, DONE, FAILED }

/** LLM 纠错 UI 状态：状态 + 耗时(ms) + 总 token 数 */
data class CorrectionUiState(
    val status: CorrectionStatus = CorrectionStatus.IDLE,
    val durationMs: Long = 0,
    val totalTokens: Int = 0
)

/** 符号键：中文（全角）与英文（半角）两种形态，随键盘语言切换渲染与插入 */
private data class SymbolKey(val zh: String, val en: String)

@Composable
fun SayboardProTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = lightColors(
            primary = Color(0xFF2E7D32),
            secondary = Color(0xFFFF9800),
            background = Color(0xFFF5F5F5),
            surface = Color.White
        ),
        content = content
    )
}
