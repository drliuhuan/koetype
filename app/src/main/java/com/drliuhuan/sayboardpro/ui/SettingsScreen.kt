package com.drliuhuan.sayboardpro.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.RadioButton
import androidx.compose.material.Slider
import androidx.compose.material.DrawerValue
import androidx.compose.material.ModalDrawer
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TopAppBar
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.material.rememberDrawerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.drliuhuan.sayboardpro.AppPrefs
import com.drliuhuan.sayboardpro.BuildConfig
import com.drliuhuan.sayboardpro.CrashLogger
import com.drliuhuan.sayboardpro.Constants
import com.drliuhuan.sayboardpro.R
import com.drliuhuan.sayboardpro.data.CustomDictionary
import com.drliuhuan.sayboardpro.data.PartOfSpeech
import com.drliuhuan.sayboardpro.downloader.SherpaModelDownloader
import com.drliuhuan.sayboardpro.llm.LlamaInferenceEngine
import com.drliuhuan.sayboardpro.llm.LlmModelCatalog
import com.drliuhuan.sayboardpro.llm.LocalLlamaModel
import com.drliuhuan.sayboardpro.llm.ModelDownloader
import com.drliuhuan.sayboardpro.net.ProxyHelper
import com.drliuhuan.sayboardpro.providers.ProviderPreset
import com.drliuhuan.sayboardpro.providers.ServiceCatalog
import com.drliuhuan.sayboardpro.providers.buildChatCompletionsUrl
import com.drliuhuan.sayboardpro.providers.buildModelsUrl
import com.drliuhuan.sayboardpro.update.UpdateChecker
import com.drliuhuan.sayboardpro.update.UpdateDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.util.Locale

/**
 * 设置页主题：按 [AppPrefs.appTheme] 选择浅色/深色，默认跟随系统。
 * 颜色沿用应用品牌绿（#2E7D32），深色主题用浅绿，与语音键盘配色保持一致。
 */
@Composable
fun SettingsTheme(prefs: AppPrefs, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (prefs.appTheme) {
        AppPrefs.THEME_DARK -> true
        AppPrefs.THEME_LIGHT -> false
        else -> systemDark
    }
    MaterialTheme(
        colors = if (dark) {
            darkColors(
                primary = Color(0xFF81C784),
                secondary = Color(0xFFFFB74D)
            )
        } else {
            lightColors(
                primary = Color(0xFF2E7D32),
                secondary = Color(0xFFFF9800)
            )
        },
        content = content
    )
}

/**
 * 设置页主界面：八个分区（识别与模型 / 词典 / 录音 / LLM 纠错 / 代理 / 日志 / 外观 / 关于）。
 * "识别服务"与"Sherpa 模型"已合并为"识别与模型"：顶部识别引擎选择（本地模型/在线 API），
 * 本地模型 UI 只在选"本地模型"时启用，在线 API 模式显示供应商预置与配置。
 * 分区较多、顶部 Tab 在窄屏放不下，改为左侧抽屉（ModalDrawer）导航：点击顶部 ≡ 打开抽屉，
 * 抽屉内列出全部分区名，点选切换；主体区只显示当前分区。全部文案中文。
 */
@Composable
fun SettingsScreen(
    initialSection: String?,
    micGranted: Boolean,
    imeEnabled: Boolean,
    onRequestMic: () -> Unit,
    onOpenImeSettings: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { AppPrefs(context) }

    val initialIndex = when (initialSection) {
        SettingsScreenSections.SHERPA -> 0
        SettingsScreenSections.DICTIONARY -> 1
        SettingsScreenSections.RECORDING -> 2
        SettingsScreenSections.LLM -> 3
        SettingsScreenSections.PROXY -> 4
        SettingsScreenSections.LOGS -> 5
        SettingsScreenSections.APPEARANCE -> 6
        else -> 0
    }
    var selectedIndex by remember { mutableIntStateOf(initialIndex) }
    // 词性记忆：记住上一次添加词汇用的词性，进程内有效（进程重启=刚打开=默认"名词"）
    var lastAddedPos by remember { mutableStateOf(PartOfSpeech.NOUN) }
    // 识别服务与模型管理已合并为"识别与模型"分区（本地模型 / 在线 API 二选一）
    val tabs = listOf("识别与模型", "词典", "录音", "LLM 纠错", "代理", "日志", "外观", "关于")
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // 离开设置页时强制释放输入框焦点并隐藏软键盘：防止 KoeType 自己的设置页输入框一直持有 IME 焦点，
    // 之后在微信/飞书说话时 commit 被提交到后台隐藏的 KoeType 输入框（幽灵提交，见 SayboardProIME 焦点漂移检测）。
    // 单 ON_PAUSE 不够：系统窗口焦点恢复机制可能在窗口重新聚焦时把 TextField 焦点带回来（后台抢焦点），
    // 因此 ON_PAUSE、ON_STOP、ON_RESUME 三个事件都清焦点，任何时刻离开/返回设置页都不残留 TextField 焦点。
    // 同时隐藏软键盘：设置页窗口焦点会持续占用系统级输入法会话（三星系统窗口焦点恢复机制），
    // 只清 Compose 焦点不够，需主动 hideSoftInputFromWindow 释放 InputConnection 绑定。
    // 取舍：ON_RESUME 每次从后台回来都清焦点，设置页用户需要手动点输入框才能打字（设置页打字场景少，稳定优先）。
    // 在 DisposableEffect 里注册 observer 并在 onDispose 移除，避免泄漏。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        // 宿主 Activity：用于取 windowToken 隐藏软键盘（释放系统级输入法会话）
        val activity = context as? Activity
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_RESUME -> {
                    focusManager.clearFocus()
                    // 释放系统级输入法会话：设置页窗口焦点会持续占用 InputConnection（三星系统窗口焦点
                    // 恢复机制），导致用户切回微信/飞书后提交仍指向设置页（幽灵提交）。离开时主动隐藏
                    // 软键盘，从源头释放输入法绑定；clearFocus 保留在上方，不重复清焦点。
                    activity?.let { a ->
                        runCatching {
                            val imm = a.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                            a.window?.decorView?.windowToken?.let {
                                imm.hideSoftInputFromWindow(it, 0)
                            }
                        }.onFailure {
                            // 不因清理失败崩溃
                        }
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    ModalDrawer(
        drawerState = drawerState,
        drawerContent = {
            // material 的 ModalDrawer 自带 surface 容器（surface 色 + 抽屉阴影），
            // 无需 material3 的 ModalDrawerSheet 包裹，直接按 Column 布局放菜单项。
            Text(
                "设置",
                style = MaterialTheme.typography.h6,
                modifier = Modifier.padding(16.dp)
            )
            Divider()
            tabs.forEachIndexed { index, title ->
                DrawerItem(
                    title = title,
                    selected = index == selectedIndex,
                    onClick = {
                        selectedIndex = index
                        scope.launch { drawerState.close() }
                    }
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 点击页面空白处清除输入框焦点（不吞输入框/按钮自己的点击：子节点消费点击后此回调不触发）
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { focusManager.clearFocus() }
        ) {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "打开设置菜单"
                        )
                    }
                }
            )

            if (!micGranted || !imeEnabled) {
                SetupNoticeCard(
                    micGranted = micGranted,
                    imeEnabled = imeEnabled,
                    onRequestMic = onRequestMic,
                    onOpenImeSettings = onOpenImeSettings
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (selectedIndex) {
                    0 -> RecognitionSection(prefs, context)
                    1 -> DictionarySection(prefs, lastAddedPos) { lastAddedPos = it }
                    2 -> RecordingSection(prefs)
                    3 -> LlmSection(prefs)
                    4 -> ProxySection(prefs)
                    5 -> LogsSection(context)
                    6 -> AppearanceSection(prefs)
                    7 -> AboutSection(prefs)
                }
            }
        }
    }
}

/** 抽屉菜单项：当前分区高亮，点击切换并自动关闭抽屉 */
@Composable
private fun DrawerItem(
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (selected) MaterialTheme.colors.primary.copy(alpha = 0.12f)
                else Color.Transparent
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = if (selected) MaterialTheme.typography.subtitle1
            else MaterialTheme.typography.body1,
            color = if (selected) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

/** 顶部提示：缺麦克风权限 / 未启用输入法 */
@Composable
private fun SetupNoticeCard(
    micGranted: Boolean,
    imeEnabled: Boolean,
    onRequestMic: () -> Unit,
    onOpenImeSettings: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!micGranted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("需要麦克风权限才能语音输入", modifier = Modifier.weight(1f))
                    Button(onClick = onRequestMic) { Text("授权") }
                }
            }
            if (!imeEnabled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("尚未启用本输入法", modifier = Modifier.weight(1f))
                    Button(onClick = onOpenImeSettings) { Text("去启用") }
                }
            }
        }
    }
}

/** 在线服务隐私警示条：在线模式会把音频/文本发往第三方服务器 */
@Composable
private fun PrivacyWarningBar(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFFEBEE), shape = RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = "隐私提示",
            tint = Color(0xFFC62828)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.body2,
            color = Color(0xFFC62828)
        )
    }
}

// ── 分区 0：识别与模型（识别服务 + 模型管理合并） ────────────────────

/**
 * 合并后的"识别与模型"分区：
 * 顶部识别引擎选择（本地模型 sherpa，默认 / 在线 API）。
 * 选"本地模型"时启用本地模型 UI（下载源 + ASR 模型卡片 + 标点状态 + 校验状态）；
 * 选"在线 API"时隐藏本地模型区，显示在线供应商预置与配置。
 */
@Composable
private fun RecognitionSection(prefs: AppPrefs, context: Context) {
    var engine by remember { mutableStateOf(prefs.activeProvider) }

    SectionCard(stringResource(R.string.settings_title_provider_choice)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = engine == AppPrefs.PROVIDER_SHERPA,
                onClick = {
                    engine = AppPrefs.PROVIDER_SHERPA
                    prefs.activeProvider = AppPrefs.PROVIDER_SHERPA
                }
            )
            Text(stringResource(R.string.settings_provider_sherpa))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = engine == AppPrefs.PROVIDER_WHISPER,
                onClick = {
                    engine = AppPrefs.PROVIDER_WHISPER
                    prefs.activeProvider = AppPrefs.PROVIDER_WHISPER
                }
            )
            Text(stringResource(R.string.settings_provider_whisper))
        }
        Text(
            stringResource(R.string.settings_engine_hint),
            style = MaterialTheme.typography.caption
        )
    }

    // 模型进程前台保活开关（task49f）：关闭后模型进程不做前台化，无通知栏常驻
    SttForegroundKeepAliveSection(prefs)

    if (engine == AppPrefs.PROVIDER_SHERPA) {
        // 本地模型相关 UI 只在选择"本地模型"时启用
        LocalSttModelSection(prefs, context)
    } else {
        // 在线 API 模式：供应商预置 + 服务配置 + 测试/拉取模型
        OnlineSttSection(prefs)
        ProviderIntroSection()
    }
}

/**
 * 模型进程前台保活开关（task49f）：
 * 默认开启（模型进程 startForeground，通知栏常驻，识别秒开）；
 * 关闭后模型进程为普通服务，无常驻通知，模型可能被系统回收（下次加载稍慢）。
 * 切换后下次模型进程启动时生效，无需重启。
 */
@Composable
private fun SttForegroundKeepAliveSection(prefs: AppPrefs) {
    var keepAlive by remember { mutableStateOf(prefs.sttForegroundKeepAlive) }

    SectionCard(stringResource(R.string.settings_stt_foreground_title)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.settings_stt_foreground_title),
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = keepAlive,
                onCheckedChange = {
                    keepAlive = it
                    prefs.sttForegroundKeepAlive = it
                }
            )
        }
        Text(
            stringResource(R.string.settings_stt_foreground_hint),
            style = MaterialTheme.typography.caption
        )
    }
}

// ── 在线语音识别（在线 API 模式）：供应商预置 + 配置 + 测试/拉取模型 ──

@Composable
private fun OnlineSttSection(prefs: AppPrefs) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var providerId by remember { mutableStateOf(matchSttProvider(prefs)) }
    var baseUrl by remember { mutableStateOf(prefs.whisperBaseUrl) }
    var apiKeyInput by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(prefs.whisperModel) }
    var language by remember { mutableStateOf(prefs.whisperLanguage) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var modelsPicker by remember { mutableStateOf<ModelsPickerState?>(null) }

    val selected = ServiceCatalog.sttProviderById(providerId)

    /** 选中供应商：写入持久化 id，并把默认 baseUrl/model 填进 prefs 与状态（均可再编辑） */
    fun applyPreset(preset: ProviderPreset) {
        prefs.sttProviderId = preset.id
        providerId = preset.id
        if (!preset.isCustom) {
            baseUrl = preset.baseUrl
            model = preset.defaultModel
            prefs.whisperBaseUrl = preset.baseUrl
            prefs.whisperModel = preset.defaultModel
        }
    }

    SectionCard(stringResource(R.string.settings_stt_provider_title)) {
        PrivacyWarningBar(stringResource(R.string.settings_privacy_warning_stt))
        ProviderPicker(
            label = stringResource(R.string.settings_stt_provider_label),
            providers = ServiceCatalog.sttProviders,
            selectedId = providerId,
            onSelect = ::applyPreset
        )
        Text(
            "默认接口：${selected.baseUrl.ifBlank { "（自定义）" }} · ${selected.note}",
            style = MaterialTheme.typography.caption,
            color = if (selected.needProxy) MaterialTheme.colors.secondary else MaterialTheme.colors.primary
        )
        Text(
            stringResource(R.string.settings_stt_provider_note),
            style = MaterialTheme.typography.caption
        )

        Divider(modifier = Modifier.padding(vertical = 4.dp))

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it; prefs.whisperBaseUrl = it },
            label = { Text(stringResource(R.string.settings_whisper_base_url)) },
            modifier = Modifier.fillMaxWidth()
        )
        SecretTextField(
            value = apiKeyInput,
            onValueChange = { s ->
                apiKeyInput = s
                if (s.isNotBlank()) prefs.whisperApiKey = s.trim()
            },
            label = stringResource(R.string.settings_whisper_api_key),
            hasConfigured = prefs.whisperApiKey.isNotBlank()
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it; prefs.whisperModel = it },
            label = { Text(stringResource(R.string.settings_whisper_model)) },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = language,
            onValueChange = { language = it; prefs.whisperLanguage = it },
            label = { Text(stringResource(R.string.settings_whisper_language)) },
            modifier = Modifier.fillMaxWidth()
        )

        // 已缓存模型列表（上次"获取模型"结果，离线可看/选择）
        val cached = prefs.whisperCachedModels
        if (cached.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    context.getString(R.string.settings_cached_models, cached.size),
                    style = MaterialTheme.typography.caption,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    modelsPicker = ModelsPickerState(
                        title = context.getString(R.string.settings_cached_models_title),
                        models = cached,
                        applyTo = { m -> model = m; prefs.whisperModel = m }
                    )
                }) {
                    Text(stringResource(R.string.settings_cached_models_choose))
                }
            }
        }

        Text(
            stringResource(R.string.settings_whisper_hint),
            style = MaterialTheme.typography.caption
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                // 语音测试走 {baseUrl}/models，只需接口地址即可验证连接
                enabled = !testing && baseUrl.isNotBlank(),
                onClick = {
                    testing = true
                    testResult = null
                    scope.launch {
                        testResult = testSttConnection(prefs, baseUrl, model)
                        testing = false
                    }
                }
            ) {
                Text(if (testing) stringResource(R.string.settings_testing)
                else stringResource(R.string.settings_button_test))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                enabled = !testing && baseUrl.isNotBlank(),
                onClick = {
                    testing = true
                    testResult = null
                    scope.launch {
                        val result = fetchModels(prefs, baseUrl, ProxyHelper.Usage.STT)
                        when (result) {
                            is FetchModelsResult.Success -> {
                                prefs.whisperCachedModels = result.models
                                if (result.models.isEmpty()) {
                                    testResult = context.getString(R.string.settings_fetch_models_empty)
                                } else {
                                    modelsPicker = ModelsPickerState(
                                        title = context.getString(R.string.settings_stt_models_title),
                                        models = result.models,
                                        applyTo = { m -> model = m; prefs.whisperModel = m }
                                    )
                                    testResult = context.getString(
                                        R.string.settings_fetch_models_saved, result.models.size
                                    )
                                }
                            }
                            is FetchModelsResult.Error -> {
                                testResult = context.getString(
                                    R.string.settings_fetch_models_fail, result.message
                                )
                            }
                        }
                        testing = false
                    }
                }
            ) { Text(stringResource(R.string.settings_button_fetch_models)) }
        }
        testResult?.let {
            Text(it, style = MaterialTheme.typography.caption)
        }
    }

    modelsPicker?.let { state ->
        ModelListDialog(
            title = state.title,
            models = state.models,
            onSelect = { m ->
                state.applyTo(m)
                modelsPicker = null
            },
            onDismiss = { modelsPicker = null }
        )
    }
}

/** 供应商下拉：只读文本框 + 透明点击覆盖层 + DropdownMenu（预置表共用一套框架） */
@Composable
private fun ProviderPicker(
    label: String,
    providers: List<ProviderPreset>,
    selectedId: String,
    onSelect: (ProviderPreset) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = providers.firstOrNull { it.id == selectedId } ?: providers.last()

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = selected.name,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { expanded = true }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            providers.forEach { p ->
                DropdownMenuItem(onClick = {
                    onSelect(p)
                    expanded = false
                }) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(p.name)
                        if (p.baseUrl.isNotBlank()) {
                            Text(p.baseUrl, style = MaterialTheme.typography.caption)
                        }
                    }
                }
            }
        }
    }
}

/** API 密钥输入：始终密码框（显示/隐藏切换按钮无效已移除，问题 5）；已配置时显示占位、不显示明文 */
@Composable
private fun SecretTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    hasConfigured: Boolean,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = {
            Text(if (hasConfigured && value.isEmpty()) "$label（已配置）" else label)
        },
        placeholder = {
            if (hasConfigured && value.isEmpty()) {
                Text(stringResource(R.string.settings_api_key_placeholder))
            }
        },
        visualTransformation = PasswordVisualTransformation(),
        modifier = modifier.fillMaxWidth()
    )
}

/** "获取模型"结果弹窗状态 */
private data class ModelsPickerState(
    val title: String,
    val models: List<String>,
    val applyTo: (String) -> Unit
)

/** 模型列表弹窗：点选某项应用为当前模型；空列表显示提示 */
@Composable
private fun ModelListDialog(
    title: String,
    models: List<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                if (models.isEmpty()) {
                    Text(stringResource(R.string.settings_fetch_models_empty))
                } else {
                    models.forEach { m ->
                        TextButton(
                            onClick = { onSelect(m) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(m) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

/**
 * 根据当前 prefs 值匹配语音供应商（兼容老用户直接填了预置值的情况）：
 * 1. baseUrl + model 都匹配某预置 → 该预置；
 * 2. baseUrl 匹配但模型被改过 → 仍是该供应商；
 * 3. 持久化 id 指向的预置 baseUrl 与当前一致 → 沿用；
 * 4. 否则归为"自定义"（手动填了非预置地址）。
 */
private fun matchSttProvider(prefs: AppPrefs): String {
    val base = prefs.whisperBaseUrl.trim().trimEnd('/')
    val model = prefs.whisperModel.trim()
    ServiceCatalog.sttProviders.firstOrNull { it.baseUrl == base && it.defaultModel == model }
        ?.let { return it.id }
    ServiceCatalog.sttProviders.firstOrNull { !it.isCustom && it.baseUrl == base }
        ?.let { return it.id }
    val stored = ServiceCatalog.sttProviderById(prefs.sttProviderId)
    if (!stored.isCustom && stored.baseUrl == base) return stored.id
    return "custom"
}

/** 根据当前 prefs 值匹配 LLM 供应商，规则同 [matchSttProvider] */
private fun matchLlmProvider(prefs: AppPrefs): String {
    val base = prefs.llmOnlineBaseUrl.trim().trimEnd('/')
    val model = prefs.llmOnlineModel.trim()
    ServiceCatalog.llmProviders.firstOrNull { it.baseUrl == base && it.defaultModel == model }
        ?.let { return it.id }
    ServiceCatalog.llmProviders.firstOrNull { !it.isCustom && it.baseUrl == base }
        ?.let { return it.id }
    val stored = ServiceCatalog.llmProviderById(prefs.llmProviderId)
    if (!stored.isCustom && stored.baseUrl == base) return stored.id
    return "custom"
}

// ── 模型下载源（中文 int8 + 标点走 GitHub 整包，默认；可切回 HF 多镜像） ──

@Composable
private fun DownloadSourceSection(prefs: AppPrefs) {
    var downloadSource by remember { mutableStateOf(prefs.downloadSource) }

    SectionCard(stringResource(R.string.settings_download_source_title)) {
        Text(
            stringResource(R.string.settings_download_source_hint),
            style = MaterialTheme.typography.caption
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = downloadSource == AppPrefs.DOWNLOAD_SOURCE_GITHUB,
                onClick = {
                    downloadSource = AppPrefs.DOWNLOAD_SOURCE_GITHUB
                    prefs.downloadSource = downloadSource
                }
            )
            Text(stringResource(R.string.settings_download_source_github))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = downloadSource == AppPrefs.DOWNLOAD_SOURCE_HF,
                onClick = {
                    downloadSource = AppPrefs.DOWNLOAD_SOURCE_HF
                    prefs.downloadSource = downloadSource
                }
            )
            Text(stringResource(R.string.settings_download_source_hf))
        }
    }
}

// ── 服务商介绍（静态文案，可折叠） ──────────────────────────────────

private data class ProviderIntro(
    val nameRes: Int,
    val introRes: Int,
    val registerRes: Int,
    val endpointRes: Int,
    val noteRes: Int,
    val supported: Boolean
)

private val PROVIDER_INTROS = listOf(
    ProviderIntro(
        R.string.service_groq_name,
        R.string.service_groq_intro,
        R.string.service_groq_register,
        R.string.service_groq_endpoint,
        R.string.service_groq_note,
        supported = true
    ),
    ProviderIntro(
        R.string.service_deepgram_name,
        R.string.service_deepgram_intro,
        R.string.service_deepgram_register,
        R.string.service_deepgram_endpoint,
        R.string.service_deepgram_note,
        supported = false
    ),
    ProviderIntro(
        R.string.service_siliconflow_name,
        R.string.service_siliconflow_intro,
        R.string.service_siliconflow_register,
        R.string.service_siliconflow_endpoint,
        R.string.service_siliconflow_note,
        supported = true
    ),
    ProviderIntro(
        R.string.service_glm_name,
        R.string.service_glm_intro,
        R.string.service_glm_register,
        R.string.service_glm_endpoint,
        R.string.service_glm_note,
        supported = true
    )
)

@Composable
private fun ProviderIntroSection() {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.settings_provider_intro_title),
                    style = MaterialTheme.typography.subtitle1,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                    contentDescription = null
                )
            }
            if (expanded) {
                PROVIDER_INTROS.forEach { service -> ProviderIntroCard(service) }
            }
        }
    }
}

@Composable
private fun ProviderIntroCard(service: ProviderIntro) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(service.nameRes),
                    style = MaterialTheme.typography.subtitle2,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (service.supported) stringResource(R.string.service_badge_supported)
                    else stringResource(R.string.service_badge_unsupported),
                    color = if (service.supported) MaterialTheme.colors.primary
                    else MaterialTheme.colors.secondary,
                    style = MaterialTheme.typography.caption
                )
            }
            Text(stringResource(service.introRes), style = MaterialTheme.typography.caption)
            Text(stringResource(service.registerRes), style = MaterialTheme.typography.caption)
            Text(stringResource(service.endpointRes), style = MaterialTheme.typography.caption)
            Text(
                stringResource(service.noteRes),
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.secondary
            )
        }
    }
}

// ── 测试连接 + 主动获取模型列表（在线 LLM / 在线语音共用一套框架） ─────

private sealed interface FetchModelsResult {
    data class Success(val models: List<String>) : FetchModelsResult
    data class Error(val message: String) : FetchModelsResult
}

/**
 * 测试 LLM 连接：POST {baseUrl}/chat/completions，max_tokens=1，prompt="hi"。
 * 成功返回"连接成功（{模型名}）"；失败按状态码给出可操作提示
 * （401=密钥错误 / 404=URL 错误 / 超时=网络或代理）。
 */
private suspend fun testLlmConnection(
    prefs: AppPrefs,
    baseUrl: String,
    model: String,
    apiKey: String
): String = withContext(Dispatchers.IO) {
    try {
        val url = buildChatCompletionsUrl(baseUrl)
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 1)
            .put("messages", JSONArray().put(
                JSONObject().put("role", "user").put("content", "hi")
            ))
            .toString()
        ProxyHelper.withProxy(prefs, ProxyHelper.Usage.LLM) { proxy ->
            val conn = ProxyHelper.open(url, proxy, prefs)
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("Accept", "application/json")
                if (apiKey.isNotBlank()) {
                    conn.setRequestProperty("Authorization", "Bearer $apiKey")
                }
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
                when (val code = conn.responseCode) {
                    in 200..299 -> "连接成功（$model）"
                    401, 403 -> "连接失败：API 密钥错误（HTTP $code）"
                    404 -> "连接失败：接口地址错误（HTTP 404）"
                    else -> "连接失败：HTTP $code"
                }
            } finally {
                conn.disconnect()
            }
        }
    } catch (e: java.net.SocketTimeoutException) {
        "连接失败：请求超时（检查网络或代理）"
    } catch (e: Exception) {
        "连接失败：${e.message ?: "未知错误"}"
    }
}

/**
 * 测试在线语音连接：对 {baseUrl}/models 发 GET（Whisper 兼容服务一般有 /models）。
 * 成功显示"连接成功（{模型名}）"；失败同上。404 提示该服务可能不支持 /models。
 */
private suspend fun testSttConnection(prefs: AppPrefs, baseUrl: String, model: String): String =
    withContext(Dispatchers.IO) {
        try {
            val url = buildModelsUrl(baseUrl)
            ProxyHelper.withProxy(prefs, ProxyHelper.Usage.STT) { proxy ->
                val conn = ProxyHelper.open(url, proxy, prefs)
                try {
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 15000
                    conn.readTimeout = 15000
                    conn.setRequestProperty("Accept", "application/json")
                    if (prefs.whisperApiKey.isNotBlank()) {
                        conn.setRequestProperty("Authorization", "Bearer ${prefs.whisperApiKey}")
                    }
                    when (val code = conn.responseCode) {
                        in 200..299 ->
                            if (model.isBlank()) "连接成功"
                            else "连接成功（$model）"
                        401, 403 -> "连接失败：API 密钥错误（HTTP $code）"
                        404 -> "连接失败：接口地址错误（HTTP 404，该服务可能不支持 /models）"
                        else -> "连接失败：HTTP $code"
                    }
                } finally {
                    conn.disconnect()
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            "连接失败：请求超时（检查网络或代理）"
        } catch (e: Exception) {
            "连接失败：${e.message ?: "未知错误"}"
        }
    }

/**
 * 主动获取模型列表：GET {baseUrl}/models → 解析 data[].id。
 * 404 返回"该服务不支持模型列表，请手动输入"；成功列表由调用方缓存到 prefs 供离线看。
 */
private suspend fun fetchModels(
    prefs: AppPrefs,
    baseUrl: String,
    usage: ProxyHelper.Usage
): FetchModelsResult = withContext(Dispatchers.IO) {
    try {
        val url = buildModelsUrl(baseUrl)
        ProxyHelper.withProxy(prefs, usage) { proxy ->
            val conn = ProxyHelper.open(url, proxy, prefs)
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.setRequestProperty("Accept", "application/json")
                val apiKey =
                    if (usage == ProxyHelper.Usage.STT) prefs.whisperApiKey else prefs.llmOnlineApiKey
                if (apiKey.isNotBlank()) {
                    conn.setRequestProperty("Authorization", "Bearer $apiKey")
                }
                when (val code = conn.responseCode) {
                    in 200..299 -> {
                        val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                        FetchModelsResult.Success(parseModelIds(body))
                    }
                    401, 403 -> FetchModelsResult.Error("API 密钥错误（HTTP $code）")
                    404 -> FetchModelsResult.Error("该服务不支持模型列表，请手动输入")
                    else -> FetchModelsResult.Error("HTTP $code")
                }
            } finally {
                conn.disconnect()
            }
        }
    } catch (e: Exception) {
        FetchModelsResult.Error(e.message ?: "网络错误")
    }
}

/** 解析 /models 响应中的 data[].id 列表；解析失败返回空列表 */
private fun parseModelIds(body: String): List<String> {
    return try {
        val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
        buildList {
            for (i in 0 until data.length()) {
                data.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
    } catch (e: Exception) {
        emptyList()
    }
}

// ── 本地模型子区（Sherpa）：下载源 + 模型卡片 + 标点状态 + 校验 ───────

@Composable
private fun LocalSttModelSection(prefs: AppPrefs, context: Context) {
    var refreshKey by remember { mutableIntStateOf(0) }
    var customUrl by remember { mutableStateOf("") }

    // 删除确认对话框目标：ASR 模型目录
    var deleteTarget by remember { mutableStateOf<File?>(null) }

    // 下载状态由进程级 SherpaModelDownloadManager 持有：离开设置页后下载线程仍在跑，
    // 回到设置页读到的仍是进行中的进度；finishedVersion 变化驱动"已下载"列表与按钮刷新
    val installed = remember(refreshKey, SherpaModelDownloadManager.finishedVersion) {
        SherpaModelDownloader.scanInstalled(context)
    }
    val currentPath = prefs.sherpaModelPath

    // 模型下载源单选（GitHub 整包 / HuggingFace 多镜像），随本地模型启用
    DownloadSourceSection(prefs)

    // 当前所选模型的基础校验状态（完整增量校验见另一任务书）
    val currentValid = remember(currentPath) {
        currentPath.isNotBlank() && runCatching {
            val dir = File(currentPath)
            dir.isDirectory && SherpaModelDownloader.validateModelDir(dir)
        }.getOrDefault(false)
    }

    /** 删除 ASR 模型目录；正在使用则清空当前模型路径（下次识别提示"模型未下载"而非崩溃） */
    fun deleteSherpaModel(dir: File) {
        if (dir.absolutePath == prefs.sherpaModelPath) {
            prefs.sherpaModelPath = ""
        }
        if (dir.exists()) Constants.deleteRecursive(dir)
        deleteTarget = null
        refreshKey++
    }

    SectionCard(stringResource(R.string.settings_title_sherpa)) {
        // 校验状态：当前模型文件完整性与基础校验（增量校验见另一任务书）
        if (currentPath.isNotBlank()) {
            Text(
                if (currentValid) stringResource(R.string.settings_model_validation_ok)
                else stringResource(R.string.settings_model_validation_bad),
                style = MaterialTheme.typography.caption,
                color = if (currentValid) MaterialTheme.colors.primary else MaterialTheme.colors.error
            )
        }

        if (installed.isEmpty()) {
            Text(stringResource(R.string.settings_sherpa_no_model))
        } else {
            installed.forEach { modelDir ->
                val inUse = modelDir.absolutePath == currentPath
                val preset = SherpaModelDownloader.PRESETS.firstOrNull { it.name == modelDir.name }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(modelDir.name)
                        Text(
                            (if (inUse) "✓ ${stringResource(R.string.settings_sherpa_model_used)} · " else "") +
                                formatSize(Constants.directorySize(modelDir)),
                            style = MaterialTheme.typography.caption
                        )
                    }
                    if (inUse) {
                        Text(stringResource(R.string.settings_sherpa_model_used), style = MaterialTheme.typography.caption)
                    } else {
                        TextButton(onClick = {
                            prefs.sherpaModelPath = modelDir.absolutePath
                            refreshKey++
                        }) {
                            Text(stringResource(R.string.settings_sherpa_use))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { deleteTarget = modelDir }) {
                        Text(stringResource(R.string.common_delete))
                    }
                    if (preset != null) {
                        // 重新下载：删除现有目录后自动开始下载（仅预设模型可自动重下）
                        TextButton(onClick = {
                            if (modelDir.absolutePath == prefs.sherpaModelPath) prefs.sherpaModelPath = ""
                            if (modelDir.exists()) Constants.deleteRecursive(modelDir)
                            refreshKey++
                            SherpaModelDownloadManager.startPreset(context, preset)
                        }) {
                            Text(stringResource(R.string.common_redownload))
                        }
                    }
                }
                Divider()
            }
        }
    }

    SectionCard("下载模型") {
        SherpaModelDownloader.PRESETS.forEach { preset ->
            val key = "preset:${preset.name}"
            val state = SherpaModelDownloadManager.states[key]
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(preset.label, modifier = Modifier.weight(1f))
                if (state?.running == true) {
                    LinearProgressIndicator(
                        progress = state.progress.coerceIn(0f, 1f),
                        modifier = Modifier.width(100.dp)
                    )
                } else {
                    Button(
                        onClick = { SherpaModelDownloadManager.startPreset(context, preset) }
                    ) { Text(stringResource(R.string.settings_sherpa_download)) }
                }
            }
        }

        val presetError = SherpaModelDownloader.PRESETS
            .mapNotNull { SherpaModelDownloadManager.states["preset:${it.name}"]?.error }
            .firstOrNull()
        presetError?.let {
            Text(it, color = MaterialTheme.colors.error, style = MaterialTheme.typography.caption)
        }

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        val zipKey = "zip:${customUrl.trim()}"
        val zipState = SherpaModelDownloadManager.states[zipKey]
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = customUrl,
                onValueChange = { customUrl = it },
                label = { Text(stringResource(R.string.settings_sherpa_custom_url)) },
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (zipState?.running == true && customUrl.isNotBlank()) {
                LinearProgressIndicator(
                    progress = zipState.progress.coerceIn(0f, 1f),
                    modifier = Modifier.width(100.dp)
                )
            } else {
                Button(
                    enabled = customUrl.isNotBlank(),
                    onClick = {
                        val url = customUrl.trim()
                        if (url.isBlank()) return@Button
                        SherpaModelDownloadManager.startZip(context, url)
                    }
                ) { Text(stringResource(R.string.settings_sherpa_download)) }
            }
        }
        zipState?.error?.let {
            Text(it, color = MaterialTheme.colors.error, style = MaterialTheme.typography.caption)
        }
    }

    // ── 删除确认对话框（误删保护） ──
    deleteTarget?.let { dir ->
        val inUse = dir.absolutePath == currentPath
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.confirm_delete_title)) },
            text = {
                Text(
                    if (inUse) stringResource(R.string.confirm_delete_used_message, dir.name)
                    else stringResource(R.string.confirm_delete_message, dir.name)
                )
            },
            confirmButton = {
                TextButton(onClick = { deleteSherpaModel(dir) }) {
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

}

private fun formatScore(score: Float): String =
    if (score % 1f == 0f) score.toInt().toString() else score.toString()

// ── 分区 2：词典 ────────────────────────────────────────────────────

@Composable
private fun DictionarySection(
    prefs: AppPrefs,
    lastAddedPos: PartOfSpeech,
    onPosUsed: (PartOfSpeech) -> Unit
) {
    val dictionary = remember { CustomDictionary(prefs) }
    var entries by remember { mutableStateOf(dictionary.getTerms()) }
    var word by remember { mutableStateOf("") }
    // 词性初始值 = 上一次刚添加词汇的词性；进程内还没有添加记录时默认"名词"
    var partOfSpeech by remember { mutableStateOf(lastAddedPos) }
    var posMenuOpen by remember { mutableStateOf(false) }
    var fuzzyPinyin by remember { mutableStateOf(prefs.dictionaryFuzzyPinyin) }
    // 热词权重：与词库强相关（词条进热词），随词库一起调整
    var hotwordsScore by remember { mutableStateOf(formatScore(prefs.sherpaHotwordsScore)) }
    val focusManager = LocalFocusManager.current

    SectionCard(stringResource(R.string.settings_title_dict)) {
        Text(
            stringResource(R.string.settings_dict_hint),
            style = MaterialTheme.typography.caption
        )

        // ── 添加词汇表单（词 + 词性 + 添加按钮）：放在词典分区最顶部，词多时无需下滑 ──
        OutlinedTextField(
            value = word,
            onValueChange = { word = it },
            label = { Text(stringResource(R.string.settings_dict_word)) },
            modifier = Modifier.fillMaxWidth()
        )

        // 词性下拉：readOnly 文本框 + 透明点击覆盖层 + DropdownMenu
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = partOfSpeech.label,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.settings_dict_pos)) },
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = null
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable { posMenuOpen = true }
            )
            DropdownMenu(
                expanded = posMenuOpen,
                onDismissRequest = { posMenuOpen = false }
            ) {
                PartOfSpeech.values().forEach { pos ->
                    DropdownMenuItem(onClick = {
                        partOfSpeech = pos
                        posMenuOpen = false
                    }) {
                        Text(pos.label)
                    }
                }
            }
        }

        Button(
            enabled = word.isNotBlank(),
            onClick = {
                // 权重不再由用户输入，统一使用默认值（CustomDictionary.add 的默认参数）
                dictionary.add(word, partOfSpeech)
                // 记忆本次词性：下一次添加默认沿用上一次刚添加词汇的词性
                onPosUsed(partOfSpeech)
                word = ""
                entries = dictionary.getTerms()
                // 保存后清除焦点，防止添加词汇后输入框仍持有 IME 焦点（幽灵提交）
                focusManager.clearFocus()
            },
            modifier = Modifier.align(Alignment.End)
        ) { Text(stringResource(R.string.settings_dict_add)) }

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        // ── 热词权重（从"识别与模型"分区迁来，与词库强相关，随词库一起调整） ──
        Text(
            stringResource(R.string.settings_sherpa_hotwords_hint),
            style = MaterialTheme.typography.caption
        )
        OutlinedTextField(
            value = hotwordsScore,
            onValueChange = { hotwordsScore = it },
            label = { Text(stringResource(R.string.settings_sherpa_hotwords_score)) },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                val v = hotwordsScore.toFloatOrNull()
                    ?.coerceIn(0f, AppPrefs.MAX_HOTWORDS_SCORE)
                    ?: AppPrefs.DEFAULT_HOTWORDS_SCORE
                prefs.sherpaHotwordsScore = v
                hotwordsScore = formatScore(v)
            },
            modifier = Modifier.align(Alignment.End)
        ) { Text(stringResource(R.string.common_save)) }

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        // 模糊拼音：默认关闭，需用户主动开启。开启后平翘舌/前后鼻音/n-l 等
        // 常见混淆也按读音归并修正（如"是/四、新/星"），可能误伤。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.settings_dict_fuzzy_pinyin),
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = fuzzyPinyin,
                onCheckedChange = {
                    fuzzyPinyin = it
                    prefs.dictionaryFuzzyPinyin = it
                }
            )
        }
        Text(
            stringResource(R.string.settings_dict_fuzzy_pinyin_hint),
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.secondary
        )

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        if (entries.isEmpty()) {
            Text(stringResource(R.string.settings_dict_empty))
        } else {
            entries.forEach { entry ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Switch(
                        checked = entry.enabled,
                        onCheckedChange = {
                            dictionary.setEnabled(entry.word, it)
                            entries = dictionary.getTerms()
                        }
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${entry.word}（${entry.partOfSpeech.label}）")
                    }
                    IconButton(onClick = {
                        dictionary.remove(entry.word)
                        entries = dictionary.getTerms()
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_backspace),
                            contentDescription = stringResource(R.string.settings_dict_delete)
                        )
                    }
                }
                Divider()
            }
        }
    }
}

// ── 分区 3：录音设置 ────────────────────────────────────────────────

@Composable
private fun RecordingSection(prefs: AppPrefs) {
    var silenceTimeout by remember { mutableStateOf(prefs.silenceTimeoutMs.toString()) }
    var threshold by remember { mutableFloatStateOf(prefs.silenceThreshold) }
    var autoCapitalize by remember { mutableStateOf(prefs.autoCapitalize) }
    var keyboardSwitchBack by remember { mutableStateOf(prefs.keyboardSwitchBack) }
    var smartEnter by remember { mutableStateOf(prefs.smartEnter) }
    var keyboardHeight by remember { mutableStateOf(prefs.keyboardHeight) }

    SectionCard("录音设置") {
        // 两种手势同时生效，无需设置项：点按开始/再按结束（停止说话自动结束）；长按按住开始、松开结束
        Text(
            "点按开始/结束识别（停止说话自动结束）；长按按住开始，松开结束。",
            style = MaterialTheme.typography.caption
        )

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        OutlinedTextField(
            value = silenceTimeout,
            onValueChange = { silenceTimeout = it },
            label = { Text(stringResource(R.string.settings_silence_timeout)) },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                prefs.silenceTimeoutMs = silenceTimeout.toIntOrNull() ?: 0
            },
            modifier = Modifier.align(Alignment.End)
        ) { Text(stringResource(R.string.common_save)) }

        Text(stringResource(R.string.settings_silence_threshold), style = MaterialTheme.typography.subtitle2)
        // 阈值范围 0.0~0.1（RMS），步进 0.005
        Slider(
            value = threshold,
            onValueChange = {
                threshold = it
                prefs.silenceThreshold = it
            },
            valueRange = 0f..0.1f
        )
        Text("${(threshold * 100).toInt()}%", style = MaterialTheme.typography.caption)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_auto_capitalize), modifier = Modifier.weight(1f))
            Switch(
                checked = autoCapitalize,
                onCheckedChange = {
                    autoCapitalize = it
                    prefs.autoCapitalize = it
                }
            )
        }

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        // 收起键盘后切回上一个输入法（如 Gboard）：默认开启
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.settings_keyboard_switch_back),
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = keyboardSwitchBack,
                onCheckedChange = {
                    keyboardSwitchBack = it
                    prefs.keyboardSwitchBack = it
                }
            )
        }
        Text(
            stringResource(R.string.settings_keyboard_switch_back_hint),
            style = MaterialTheme.typography.caption
        )

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        // 智能回车：跟随输入框 IME_ACTION（搜索/发送/下一行），关闭时固定换行（默认）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_smart_enter), modifier = Modifier.weight(1f))
            Switch(
                checked = smartEnter,
                onCheckedChange = {
                    smartEnter = it
                    prefs.smartEnter = it
                }
            )
        }
        Text(
            stringResource(R.string.settings_smart_enter_hint),
            style = MaterialTheme.typography.caption
        )

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        // 键盘高度：低=30%屏高（默认）/中=40%/高=50%
        Text(stringResource(R.string.settings_keyboard_height), style = MaterialTheme.typography.subtitle2)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = keyboardHeight == AppPrefs.KEYBOARD_HEIGHT_LOW,
                onClick = {
                    keyboardHeight = AppPrefs.KEYBOARD_HEIGHT_LOW
                    prefs.keyboardHeight = keyboardHeight
                }
            )
            Text(stringResource(R.string.settings_keyboard_height_low))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = keyboardHeight == AppPrefs.KEYBOARD_HEIGHT_MEDIUM,
                onClick = {
                    keyboardHeight = AppPrefs.KEYBOARD_HEIGHT_MEDIUM
                    prefs.keyboardHeight = keyboardHeight
                }
            )
            Text(stringResource(R.string.settings_keyboard_height_medium))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = keyboardHeight == AppPrefs.KEYBOARD_HEIGHT_HIGH,
                onClick = {
                    keyboardHeight = AppPrefs.KEYBOARD_HEIGHT_HIGH
                    prefs.keyboardHeight = keyboardHeight
                }
            )
            Text(stringResource(R.string.settings_keyboard_height_high))
        }
    }
}

// ── 分区 4.5：代理设置 ──────────────────────────────────────────────

@Composable
private fun ProxySection(prefs: AppPrefs) {
    var enabled by remember { mutableStateOf(prefs.proxyEnabled) }
    var protocol by remember { mutableStateOf(prefs.proxyProtocol) }
    var host by remember { mutableStateOf(prefs.proxyHost) }
    var port by remember { mutableStateOf(prefs.proxyPort.toString()) }
    var user by remember { mutableStateOf(prefs.proxyUser) }
    var pass by remember { mutableStateOf(prefs.proxyPass) }
    var forDownload by remember { mutableStateOf(prefs.proxyForDownload) }
    var forStt by remember { mutableStateOf(prefs.proxyForStt) }
    var forLlm by remember { mutableStateOf(prefs.proxyForLlm) }

    SectionCard(stringResource(R.string.settings_proxy_title)) {
        // 总开关：启用后显示下方配置
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_proxy_enabled), modifier = Modifier.weight(1f))
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    prefs.proxyEnabled = it
                }
            )
        }
        Text(
            stringResource(R.string.settings_proxy_enabled_hint),
            style = MaterialTheme.typography.caption
        )

        if (enabled) {
            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // 协议选择：HTTP / SOCKS5 / SOCKS5H
            Text(stringResource(R.string.settings_proxy_protocol), style = MaterialTheme.typography.subtitle2)
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = protocol == AppPrefs.PROXY_PROTOCOL_HTTP,
                    onClick = {
                        protocol = AppPrefs.PROXY_PROTOCOL_HTTP
                        prefs.proxyProtocol = protocol
                    }
                )
                Text(stringResource(R.string.settings_proxy_protocol_http))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = protocol == AppPrefs.PROXY_PROTOCOL_SOCKS5,
                    onClick = {
                        protocol = AppPrefs.PROXY_PROTOCOL_SOCKS5
                        prefs.proxyProtocol = protocol
                    }
                )
                Text(stringResource(R.string.settings_proxy_protocol_socks5))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = protocol == AppPrefs.PROXY_PROTOCOL_SOCKS5H,
                    onClick = {
                        protocol = AppPrefs.PROXY_PROTOCOL_SOCKS5H
                        prefs.proxyProtocol = protocol
                    }
                )
                Text(stringResource(R.string.settings_proxy_protocol_socks5h))
            }
            Text(
                stringResource(R.string.settings_proxy_socks5h_hint),
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.secondary
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // 主机 / 端口
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it; prefs.proxyHost = it },
                    label = { Text(stringResource(R.string.settings_proxy_host)) },
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = {
                        port = it
                        it.toIntOrNull()?.let { v -> prefs.proxyPort = v.coerceIn(1, 65535) }
                    },
                    label = { Text(stringResource(R.string.settings_proxy_port)) },
                    modifier = Modifier.width(110.dp)
                )
            }

            // 用户名 / 密码（可选）
            OutlinedTextField(
                value = user,
                onValueChange = { user = it; prefs.proxyUser = it },
                label = { Text(stringResource(R.string.settings_proxy_user)) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = pass,
                onValueChange = { pass = it; prefs.proxyPass = it },
                label = { Text(stringResource(R.string.settings_proxy_pass)) },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // 三个独立用途开关（默认：模型下载开，两个 API 关）
            Text(stringResource(R.string.settings_proxy_usage), style = MaterialTheme.typography.subtitle2)
            ProxyUsageRow(stringResource(R.string.settings_proxy_for_download), forDownload) {
                forDownload = it; prefs.proxyForDownload = it
            }
            ProxyUsageRow(stringResource(R.string.settings_proxy_for_stt), forStt) {
                forStt = it; prefs.proxyForStt = it
            }
            ProxyUsageRow(stringResource(R.string.settings_proxy_for_llm), forLlm) {
                forLlm = it; prefs.proxyForLlm = it
            }
            Text(
                stringResource(R.string.settings_proxy_usage_hint),
                style = MaterialTheme.typography.caption
            )
        }
    }
}

/** 代理用途开关行（label + Switch），复用三处 */
@Composable
private fun ProxyUsageRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// ── 分区 4：LLM 纠错 ────────────────────────────────────────────────

@Composable
private fun LlmSection(prefs: AppPrefs) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(prefs.llmCorrectionEnabled) }
    var mode by remember { mutableStateOf(prefs.llmCorrectionMode) }
    var baseUrl by remember { mutableStateOf(prefs.llmOnlineBaseUrl) }
    var llmApiKeyInput by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(prefs.llmOnlineModel) }
    var maxTokens by remember { mutableStateOf(prefs.llmOnlineMaxTokens.toString()) }
    var disableThinking by remember { mutableStateOf(prefs.llmDisableThinking) }
    var llmProviderId by remember { mutableStateOf(matchLlmProvider(prefs)) }
    var llmTestResult by remember { mutableStateOf<String?>(null) }
    var llmTesting by remember { mutableStateOf(false) }
    var llmModelsPicker by remember { mutableStateOf<ModelsPickerState?>(null) }
    var statsVersion by remember { mutableIntStateOf(0) }
    val stats = remember(statsVersion) { prefs.llmStats }

    /** 选中 LLM 供应商：写入持久化 id，并把默认 baseUrl/model 填进 prefs 与状态（均可再编辑） */
    fun applyLlmPreset(preset: ProviderPreset) {
        prefs.llmProviderId = preset.id
        llmProviderId = preset.id
        if (!preset.isCustom) {
            baseUrl = preset.baseUrl
            model = preset.defaultModel
            prefs.llmOnlineBaseUrl = preset.baseUrl
            prefs.llmOnlineModel = preset.defaultModel
        }
    }

    SectionCard(stringResource(R.string.settings_title_llm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_llm_enabled), modifier = Modifier.weight(1f))
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    prefs.llmCorrectionEnabled = it
                }
            )
        }

        if (enabled) {
            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // ── 纠错模式：在线 API / 本地模型（默认在线） ──
            Text(stringResource(R.string.settings_llm_mode), style = MaterialTheme.typography.subtitle2)
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = mode == AppPrefs.LLM_MODE_ONLINE,
                    onClick = {
                        mode = AppPrefs.LLM_MODE_ONLINE
                        prefs.llmCorrectionMode = mode
                    }
                )
                Text(stringResource(R.string.settings_llm_mode_online))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = mode == AppPrefs.LLM_MODE_LOCAL,
                    onClick = {
                        mode = AppPrefs.LLM_MODE_LOCAL
                        prefs.llmCorrectionMode = mode
                        // 本地模型未下载时切到本地模式 → Toast 提示去下载
                        if (!hasLocalModel(prefs)) {
                            Toast.makeText(context, R.string.settings_llm_local_select_toast, Toast.LENGTH_SHORT).show()
                        }
                    }
                )
                Text(stringResource(R.string.settings_llm_mode_local))
            }

            Text(
                stringResource(R.string.settings_privacy_mode_hint),
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.secondary
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // ── 自定义提示词（本地/在线纠错共用，附加到 system prompt 末尾） ──
            CustomPromptField(prefs)

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            if (mode == AppPrefs.LLM_MODE_LOCAL) {
                // ── 本地模型子区：预设列表 + 下载/进度/已下载/使用/删除 ──
                LocalModelSection(prefs, context)
            } else {
                // ── 在线模式：供应商预置 + 配置 + 测试/拉取模型 ──
                PrivacyWarningBar(stringResource(R.string.settings_privacy_warning_llm))
                val llmSelected = ServiceCatalog.llmProviderById(llmProviderId)
                ProviderPicker(
                    label = stringResource(R.string.settings_llm_provider_label),
                    providers = ServiceCatalog.llmProviders,
                    selectedId = llmProviderId,
                    onSelect = ::applyLlmPreset
                )
                Text(
                    "默认接口：${llmSelected.baseUrl.ifBlank { "（自定义）" }} · ${llmSelected.note}",
                    style = MaterialTheme.typography.caption,
                    color = if (llmSelected.needProxy) MaterialTheme.colors.secondary
                    else MaterialTheme.colors.primary
                )

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it; prefs.llmOnlineBaseUrl = it },
                    label = { Text(stringResource(R.string.settings_llm_base_url)) },
                    modifier = Modifier.fillMaxWidth()
                )
                SecretTextField(
                    value = llmApiKeyInput,
                    onValueChange = { s ->
                        llmApiKeyInput = s
                        if (s.isNotBlank()) prefs.llmOnlineApiKey = s.trim()
                    },
                    label = stringResource(R.string.settings_llm_api_key),
                    hasConfigured = prefs.llmOnlineApiKey.isNotBlank()
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it; prefs.llmOnlineModel = it },
                    label = { Text(stringResource(R.string.settings_llm_model)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = maxTokens,
                    onValueChange = {
                        maxTokens = it
                        it.toIntOrNull()?.let { v -> prefs.llmOnlineMaxTokens = v.coerceIn(1, 8192) }
                    },
                    label = { Text(stringResource(R.string.settings_llm_max_tokens)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.settings_llm_disable_thinking),
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = disableThinking,
                        onCheckedChange = {
                            disableThinking = it
                            prefs.llmDisableThinking = it
                        }
                    )
                }
                Text(
                    stringResource(R.string.settings_llm_disable_thinking_hint),
                    style = MaterialTheme.typography.caption
                )

                // 已缓存模型列表（上次"获取模型"结果，离线可看/选择）
                val llmCached = prefs.llmCachedModels
                if (llmCached.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            context.getString(R.string.settings_cached_models, llmCached.size),
                            style = MaterialTheme.typography.caption,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            llmModelsPicker = ModelsPickerState(
                                title = context.getString(R.string.settings_cached_models_title),
                                models = llmCached,
                                applyTo = { m -> model = m; prefs.llmOnlineModel = m }
                            )
                        }) {
                            Text(stringResource(R.string.settings_cached_models_choose))
                        }
                    }
                }

                Text(
                    stringResource(R.string.settings_llm_hint),
                    style = MaterialTheme.typography.caption
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        enabled = !llmTesting && baseUrl.isNotBlank() && model.isNotBlank(),
                        onClick = {
                            llmTesting = true
                            llmTestResult = null
                            scope.launch {
                                llmTestResult = testLlmConnection(
                                    prefs, baseUrl, model, prefs.llmOnlineApiKey
                                )
                                llmTesting = false
                            }
                        }
                    ) {
                        Text(if (llmTesting) stringResource(R.string.settings_testing)
                        else stringResource(R.string.settings_button_test))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        enabled = !llmTesting && baseUrl.isNotBlank(),
                        onClick = {
                            llmTesting = true
                            llmTestResult = null
                            scope.launch {
                                val result = fetchModels(prefs, baseUrl, ProxyHelper.Usage.LLM)
                                when (result) {
                                    is FetchModelsResult.Success -> {
                                        prefs.llmCachedModels = result.models
                                        if (result.models.isEmpty()) {
                                            llmTestResult = context.getString(
                                                R.string.settings_fetch_models_empty
                                            )
                                        } else {
                                            llmModelsPicker = ModelsPickerState(
                                                title = context.getString(R.string.settings_llm_models_title),
                                                models = result.models,
                                                applyTo = { m -> model = m; prefs.llmOnlineModel = m }
                                            )
                                            llmTestResult = context.getString(
                                                R.string.settings_fetch_models_saved,
                                                result.models.size
                                            )
                                        }
                                    }
                                    is FetchModelsResult.Error -> {
                                        llmTestResult = context.getString(
                                            R.string.settings_fetch_models_fail, result.message
                                        )
                                    }
                                }
                                llmTesting = false
                            }
                        }
                    ) { Text(stringResource(R.string.settings_button_fetch_models)) }
                }
                llmTestResult?.let {
                    Text(it, style = MaterialTheme.typography.caption)
                }
            }
        }

        // ── LLM 统计（累计 prompt/completion tokens、次数、平均延迟） ──
        Divider(modifier = Modifier.padding(vertical = 8.dp))
        Text(stringResource(R.string.settings_llm_stats), style = MaterialTheme.typography.subtitle2)
        StatRow(stringResource(R.string.settings_llm_stats_prompt), stats.promptTokens.toString())
        StatRow(stringResource(R.string.settings_llm_stats_completion), stats.completionTokens.toString())
        StatRow(stringResource(R.string.settings_llm_stats_total), stats.totalTokens.toString())
        StatRow(stringResource(R.string.settings_llm_stats_count), stats.requestCount.toString())
        StatRow(stringResource(R.string.settings_llm_stats_latency), formatLatency(stats.avgLatencyMs))
        TextButton(
            onClick = {
                prefs.resetCorrectionStats()
                statsVersion++
            },
            modifier = Modifier.align(Alignment.End)
        ) {
            Text(stringResource(R.string.settings_llm_stats_reset))
        }
    }

    llmModelsPicker?.let { state ->
        ModelListDialog(
            title = state.title,
            models = state.models,
            onSelect = { m ->
                state.applyTo(m)
                llmModelsPicker = null
            },
            onDismiss = { llmModelsPicker = null }
        )
    }
}

/** 自定义提示词：多行输入 + 保存，留空不附加（本地/在线纠错共用，附加到 system prompt 末尾） */
@Composable
private fun CustomPromptField(prefs: AppPrefs) {
    var customPrompt by remember { mutableStateOf(prefs.llmCustomPrompt) }

    Column {
        OutlinedTextField(
            value = customPrompt,
            onValueChange = { customPrompt = it },
            label = { Text(stringResource(R.string.settings_llm_custom_prompt)) },
            placeholder = { Text(stringResource(R.string.settings_llm_custom_prompt_placeholder)) },
            minLines = 3,
            maxLines = 5,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            stringResource(R.string.settings_llm_custom_prompt_hint),
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.secondary
        )
        Button(
            onClick = { prefs.llmCustomPrompt = customPrompt.trim() },
            // 内容与已保存值一致时禁用（灰色），修改后恢复可点：保存反馈
            enabled = customPrompt.trim() != prefs.llmCustomPrompt,
            modifier = Modifier.align(Alignment.End)
        ) { Text(stringResource(R.string.common_save)) }
    }
}

/** 本地模型子区：预设列表 + 下载/进度/已下载/使用/删除 + 错误提示。 */
@Composable
private fun LocalModelSection(prefs: AppPrefs, context: Context) {
    var selectedPath by remember { mutableStateOf(prefs.llmLocalModelPath) }
    // 删除确认对话框目标文件（null=不显示）
    var llmDeleteTarget by remember { mutableStateOf<File?>(null) }
    // 订阅下载/删除的完成版本：任务结束自增，驱动本列表重组刷新"已下载"状态
    val version = LocalModelDownloadManager.finishedVersion

    Text(
        stringResource(R.string.settings_llm_local_hint),
        style = MaterialTheme.typography.caption
    )

    // native 库仅打包 arm64-v8a；其他 ABI 上无法加载，提示后下载按钮禁用
    if (!LlamaInferenceEngine.isAvailable()) {
        Text(
            stringResource(R.string.settings_llm_local_unsupported),
            color = MaterialTheme.colors.error,
            style = MaterialTheme.typography.caption
        )
    }

    LlmModelCatalog.presets.forEach { preset ->
        val file = remember(preset.name, version, context) {
            ModelDownloader.targetFile(context, LlmModelCatalog.fileNameFor(preset.name))
        }
        val state = LocalModelDownloadManager.states[file.name]
        val downloaded = ModelDownloader.isValidGguf(file)
        val selected = selectedPath == file.absolutePath

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (selected) "✓ ${preset.displayName}" else preset.displayName,
                    style = MaterialTheme.typography.subtitle2
                )
                Text(
                    when {
                        state?.running == true -> stringResource(R.string.settings_llm_local_downloading) +
                            " ${(state.progress * 100).toInt()}%"
                        state?.failed == true -> "下载失败"
                        downloaded -> formatSize(file.length()) + " · " +
                            stringResource(R.string.settings_llm_local_downloaded)
                        else -> preset.sizeLabel + " · " + stringResource(R.string.settings_llm_local_not_downloaded)
                    },
                    style = MaterialTheme.typography.caption
                )
            }
            if (state?.running == true) {
                LinearProgressIndicator(
                    progress = state.progress.coerceIn(0f, 1f),
                    modifier = Modifier.width(90.dp)
                )
            } else if (downloaded) {
                TextButton(
                    enabled = !selected,
                    onClick = {
                        prefs.llmLocalModelPath = file.absolutePath
                        selectedPath = file.absolutePath
                    }
                ) {
                    Text(if (selected) "✓" else stringResource(R.string.settings_llm_local_use))
                }
                TextButton(onClick = { llmDeleteTarget = file }) {
                    Text(stringResource(R.string.common_delete))
                }
                TextButton(onClick = {
                    // 重新下载：删除后自动重新下载（startDownload 内部防重入）
                    LocalModelDownloadManager.deleteModel(context, file)
                    if (selectedPath == file.absolutePath) {
                        prefs.llmLocalModelPath = ""
                        selectedPath = ""
                    }
                    LocalModelDownloadManager.startDownload(context, preset.url, file.name)
                }) {
                    Text(stringResource(R.string.common_redownload))
                }
            } else {
                Button(
                    enabled = LlamaInferenceEngine.isAvailable(),
                    onClick = {
                        LocalModelDownloadManager.startDownload(context, preset.url, file.name)
                    }
                ) {
                    Text(stringResource(R.string.settings_llm_local_download))
                }
            }
        }
    }

    val lastError = LocalModelDownloadManager.lastError
    if (lastError != null) {
        Text(lastError, color = MaterialTheme.colors.error, style = MaterialTheme.typography.caption)
    }

    // 删除确认对话框（误删保护）：正在使用的模型删除后清空已选路径，LLM 按钮下次提示"模型未下载"
    llmDeleteTarget?.let { file ->
        val inUse = selectedPath == file.absolutePath
        AlertDialog(
            onDismissRequest = { llmDeleteTarget = null },
            title = { Text(stringResource(R.string.confirm_delete_title)) },
            text = {
                Text(
                    if (inUse) stringResource(R.string.confirm_delete_used_message, file.name)
                    else stringResource(R.string.confirm_delete_message, file.name)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    LocalModelDownloadManager.deleteModel(context, file)
                    if (selectedPath == file.absolutePath) {
                        prefs.llmLocalModelPath = ""
                        selectedPath = ""
                    }
                    llmDeleteTarget = null
                }) {
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { llmDeleteTarget = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

/** 本地模型是否已下载并可用（已配置路径 + 文件存在 + 通过 GGUF 校验）。 */
private fun hasLocalModel(prefs: AppPrefs): Boolean {
    val path = prefs.llmLocalModelPath.trim()
    if (path.isEmpty()) return false
    val file = File(path)
    return file.exists() && ModelDownloader.isValidGguf(file)
}

/**
 * Sherpa 模型（ASR 预设 / 标点 / 自定义 zip）下载状态管理：进程级单例，独立于 Compose 组合。
 * 下载线程由 [SherpaModelDownloader] 在普通 Thread 上跑，不随 Activity 生命周期取消；
 * 回调只写本对象的状态（mutableStateMapOf），不引用已销毁的 Compose 状态/Activity，
 * 因此离开设置页后下载继续、回来能读到最新进度，也避免"回来进度归零/重复开下"。
 * 断点续传由 [SherpaModelDownloader.downloadFile] 的 Range 头负责（半截文件落盘续传）。
 */
private object SherpaModelDownloadManager {
    data class DownloadState(
        val progress: Float = 0f,
        val running: Boolean = false,
        val error: String? = null
    )

    val states = mutableStateMapOf<String, DownloadState>()

    /** 下载/删除完成时自增，驱动列表重组刷新"已下载/未下载"与进度 */
    var finishedVersion by mutableStateOf(0)
        private set

    private fun keyFor(preset: SherpaModelDownloader.ModelPreset) = "preset:${preset.name}"
    private fun keyForPunct() = "punct"
    private fun keyForZip(url: String) = "zip:$url"

    fun startPreset(context: Context, preset: SherpaModelDownloader.ModelPreset) {
        val key = keyFor(preset)
        if (states[key]?.running == true) return
        states[key] = DownloadState(running = true)
        val appContext = context.applicationContext
        SherpaModelDownloader.downloadAndInstall(
            appContext, preset,
            object : SherpaModelDownloader.Callback {
                override fun onProgress(progress: Float) {
                    states[key] = DownloadState(progress = progress, running = true)
                }

                override fun onSuccess(modelDir: File) {
                    states[key] = DownloadState(progress = 1f, running = false)
                    finishedVersion++
                    // 标点模型"绑定下载"：GitHub 整包已内含标点；HF 单文件模式下下载器
                    // 已随 ASR 串行补下标点（见 downloadAndInstall），此处仅当标点仍缺失时
                    // 再补一次重试（下载器内标点失败不阻塞 ASR 成功，靠这里兜底重拉）
                    if (preset.name == SherpaModelDownloader.GITHUB_ZH_INT8_PRESET_NAME &&
                        AppPrefs(appContext).downloadSource == AppPrefs.DOWNLOAD_SOURCE_HF &&
                        SherpaModelDownloader.scanPunctInstalled(appContext) == null
                    ) {
                        startPunct(appContext)
                    }
                }

                override fun onError(message: String) {
                    states[key] = DownloadState(running = false, error = message)
                    finishedVersion++
                }
            }
        )
    }

    fun startPunct(context: Context) {
        val key = keyForPunct()
        if (states[key]?.running == true) return
        states[key] = DownloadState(running = true)
        val appContext = context.applicationContext
        SherpaModelDownloader.downloadPunctModel(appContext, object : SherpaModelDownloader.Callback {
            override fun onProgress(progress: Float) {
                states[key] = DownloadState(progress = progress, running = true)
            }

            override fun onSuccess(modelDir: File) {
                states[key] = DownloadState(progress = 1f, running = false)
                finishedVersion++
            }

            override fun onError(message: String) {
                states[key] = DownloadState(running = false, error = message)
                finishedVersion++
            }
        })
    }

    fun startZip(context: Context, url: String) {
        val key = keyForZip(url)
        if (states[key]?.running == true) return
        states[key] = DownloadState(running = true)
        val appContext = context.applicationContext
        SherpaModelDownloader.downloadAndInstallZip(appContext, url, object : SherpaModelDownloader.Callback {
            override fun onProgress(progress: Float) {
                states[key] = DownloadState(progress = progress, running = true)
            }

            override fun onSuccess(modelDir: File) {
                states[key] = DownloadState(progress = 1f, running = false)
                finishedVersion++
            }

            override fun onError(message: String) {
                states[key] = DownloadState(running = false, error = message)
                finishedVersion++
            }
        })
    }
}

/**
 * 本地 GGUF 模型下载的状态管理。独立于 Compose 组合：离开设置页后下载仍在继续，
 * 回到设置页能读到最新进度。断点续传由 [ModelDownloader.download] 负责（Range 头）。
 */
private object LocalModelDownloadManager {
    data class DownloadState(
        val progress: Float = 0f,
        val running: Boolean = false,
        val failed: Boolean = false
    )

    val states = mutableStateMapOf<String, DownloadState>()

    /** 下载/删除完成时自增，驱动列表重组刷新"已下载/未下载"状态 */
    var finishedVersion by mutableStateOf(0)
        private set

    /** 最近一次下载失败的错误信息（展示用；新下载/成功后清空） */
    var lastError: String? by mutableStateOf(null)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun startDownload(context: Context, url: String, fileName: String) {
        if (states[fileName]?.running == true) return // 已在下载中
        val dest = ModelDownloader.targetFile(context, fileName)
        states[fileName] = DownloadState(running = true)
        lastError = null
        scope.launch {
            // 下载代理开关在 ModelDownloader.download 内部读取（走 AppPrefs）
            val result = ModelDownloader.download(AppPrefs(context), url, dest) { downloaded, total, progress ->
                states[fileName] = DownloadState(progress = progress, running = true)
            }
            result.fold(
                onSuccess = {
                    states[fileName] = DownloadState(progress = 1f, running = false)
                    lastError = null
                },
                onFailure = { e ->
                    states[fileName] = DownloadState(running = false, failed = true)
                    lastError = e.message ?: "下载失败"
                }
            )
            finishedVersion++
        }
    }

    /** 删除已下载的模型文件，并释放本地模型句柄（若正被加载）。 */
    fun deleteModel(context: Context, file: File) {
        val name = file.name
        if (file.exists()) file.delete()
        states.remove(name)
        LocalLlamaModel.release()
        lastError = null
        finishedVersion++
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.caption, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.caption)
    }
}

private fun formatLatency(ms: Long): String =
    if (ms >= 1000) String.format(Locale.US, "%.1f 秒", ms / 1000.0) else "$ms ms"

// ── 分区 5：日志 ─────────────────────────────────────────────────────

@Composable
private fun LogsSection(context: Context) {
    var refreshKey by remember { mutableIntStateOf(0) }
    var shareResult by remember { mutableStateOf<String?>(null) }

    val crashFiles = remember(refreshKey) { CrashLogger.readAll(context) }
    val hasAppLog = remember(refreshKey) { CrashLogger.hasAnyLog(context) }
    val hasLogs = crashFiles.isNotEmpty() || hasAppLog

    SectionCard("日志") {
        Text(
            "崩溃与运行日志可一键导出分享给开发者。",
            style = MaterialTheme.typography.caption
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("崩溃日志：${crashFiles.size} 个", modifier = Modifier.weight(1f))
            if (hasAppLog) {
                Text("（含运行日志 app_log.txt / app_log_persist.txt）", style = MaterialTheme.typography.caption)
            }
        }

        if (crashFiles.isEmpty()) {
            Text("暂无崩溃记录", style = MaterialTheme.typography.caption)
        } else {
            crashFiles.take(8).forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        f.name,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.caption
                    )
                    Text(formatSize(f.length()), style = MaterialTheme.typography.caption)
                }
            }
            if (crashFiles.size > 8) {
                Text("… 共 ${crashFiles.size} 个", style = MaterialTheme.typography.caption)
            }
        }

        Divider(modifier = Modifier.padding(vertical = 4.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = hasLogs,
                onClick = {
                    shareResult = try {
                        val file = CrashLogger.buildExportFile(context)
                        if (file != null) {
                            shareLog(context, file)
                            "已导出：${file.name}"
                        } else {
                            "导出失败：无法写入日志目录"
                        }
                    } catch (e: Exception) {
                        "导出失败：${e.message ?: "未知错误"}"
                    }
                }
            ) { Text("导出日志") }
            TextButton(
                enabled = hasLogs,
                onClick = {
                    CrashLogger.clear(context)
                    refreshKey++
                    shareResult = null
                }
            ) { Text("清空日志") }
        }
        shareResult?.let {
            Text(it, style = MaterialTheme.typography.caption)
        }
    }
}

/** 用系统分享面板把打包好的日志文件发出去（微信/飞书/文件管理等） */
private fun shareLog(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(
        context, context.packageName + ".fileprovider", file
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "KoeType 日志")
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "分享日志"))
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

// ── 通用卡片 ────────────────────────────────────────────────────────

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.subtitle1)
            content()
        }
    }
}

private object SettingsScreenSections {
    const val SHERPA = "sherpa"
    const val DICTIONARY = "dictionary"
    const val RECORDING = "recording"
    const val LLM = "llm"
    const val PROXY = "proxy"
    const val LOGS = "logs"
    const val APPEARANCE = "appearance"
}

// ── 分区 6：外观（设置界面主题 + 语音键盘主题） ────────────────────

/** 自定义颜色预设色板：既可直接点选，也作为色值参考（含默认前景/背景） */
private val KEYBOARD_COLOR_PRESETS = listOf(
    Color(0xFF202124), // 深灰（默认前景）
    Color(0xFFFFFFFF), // 白（默认背景）
    Color(0xFF000000), // 黑
    Color(0xFFF5F5F5), // 浅灰
    Color(0xFF424242), // 中灰
    Color(0xFF121212), // 近黑
    Color(0xFF2E7D32), // 绿
    Color(0xFF1E88E5), // 蓝
    Color(0xFFE53935), // 红
    Color(0xFFFF9800)  // 橙
)

@Composable
private fun AppearanceSection(prefs: AppPrefs) {
    var appTheme by remember { mutableStateOf(prefs.appTheme) }
    var keyboardTheme by remember { mutableStateOf(prefs.keyboardTheme) }

    SectionCard("设置界面主题") {
        Text("选择设置页的明暗外观；默认跟随系统。", style = MaterialTheme.typography.caption)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = appTheme == AppPrefs.THEME_SYSTEM, onClick = {
                appTheme = AppPrefs.THEME_SYSTEM; prefs.appTheme = appTheme
            })
            Text("跟随系统")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = appTheme == AppPrefs.THEME_LIGHT, onClick = {
                appTheme = AppPrefs.THEME_LIGHT; prefs.appTheme = appTheme
            })
            Text("浅色")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = appTheme == AppPrefs.THEME_DARK, onClick = {
                appTheme = AppPrefs.THEME_DARK; prefs.appTheme = appTheme
            })
            Text("深色")
        }
    }

    SectionCard("语音键盘主题") {
        Text("选择键盘配色；重新弹出键盘后生效。", style = MaterialTheme.typography.caption)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = keyboardTheme == AppPrefs.THEME_SYSTEM, onClick = {
                keyboardTheme = AppPrefs.THEME_SYSTEM; prefs.keyboardTheme = keyboardTheme
            })
            Text("跟随系统")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = keyboardTheme == AppPrefs.THEME_LIGHT, onClick = {
                keyboardTheme = AppPrefs.THEME_LIGHT; prefs.keyboardTheme = keyboardTheme
            })
            Text("浅色")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = keyboardTheme == AppPrefs.THEME_DARK, onClick = {
                keyboardTheme = AppPrefs.THEME_DARK; prefs.keyboardTheme = keyboardTheme
            })
            Text("深色")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = keyboardTheme == AppPrefs.THEME_CUSTOM, onClick = {
                keyboardTheme = AppPrefs.THEME_CUSTOM; prefs.keyboardTheme = keyboardTheme
            })
            Text("自定义")
        }
    }

    if (keyboardTheme == AppPrefs.THEME_CUSTOM) {
        SectionCard("自定义颜色") {
            Text("点选色板设置前景色与背景色。", style = MaterialTheme.typography.caption)
            ColorSwatchRow("前景色（文字/图标）", prefs.keyboardForegroundColor) { argb ->
                prefs.keyboardForegroundColor = argb
            }
            ColorSwatchRow("背景色", prefs.keyboardBackgroundColor) { argb ->
                prefs.keyboardBackgroundColor = argb
            }
        }
    }
}

@Composable
private fun ColorSwatchRow(label: String, currentArgb: Int, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.subtitle2)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KEYBOARD_COLOR_PRESETS.forEach { c ->
                val argb = c.toArgb()
                val selected = argb == currentArgb
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(c)
                        .clickable { onPick(argb) },
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Text("✓", color = if (c.luminance() > 0.5f) Color.Black else Color.White)
                    }
                }
            }
        }
    }
}

// ── 关于 KoeType（版本 / GitHub / 许可 / 致谢 / 捐赠） ────────────────

private const val KOETYPE_GITHUB_URL = "https://github.com/drliuhuan/koetype"
private const val KOETYPE_ALIPAY_URL = "https://qr.alipay.com/fkx11216aybdf4j4uvmycd3"
private const val POLYFORM_LICENSE_URL = "https://polyformproject.org/licenses/noncommercial/1.0.0"

/** 关于区块：设置页独立分区（抽屉第 7 项），展示版本 / 检查更新 / GitHub / 许可 / 致谢 / 捐赠 */
@Composable
private fun AboutSection(prefs: AppPrefs) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 打开外部链接（GitHub / 支付宝 / PolyForm 许可）；无浏览器或地址非法时 Toast 提示
    fun openUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(context, "无法打开链接：$url", Toast.LENGTH_SHORT).show()
        }
    }

    // ── 检查更新状态 ──
    var checkingUpdate by remember { mutableStateOf(false) }   // 是否正在请求 GitHub 最新版
    var updateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) } // 最新版信息（弹对话框用）
    var downloadProgress by remember { mutableFloatStateOf(-1f) } // <0=未下载；0f..1f=下载中

    // 检查 GitHub 最新版：网络操作在后台线程，完成回主线程更新 UI
    fun checkForUpdate() {
        if (checkingUpdate) return
        checkingUpdate = true
        scope.launch {
            val info = withContext(Dispatchers.IO) {
                UpdateChecker.checkLatest(ProxyHelper.proxyForDownload(prefs))
            }
            checkingUpdate = false
            when {
                info.errorMessage != null ->
                    Toast.makeText(context, "检查更新失败：${info.errorMessage}", Toast.LENGTH_SHORT).show()
                info.hasUpdate -> updateInfo = info
                else -> Toast.makeText(context, "已是最新版本", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 下载最新版 APK → 完成交给系统安装（FileProvider + ACTION_VIEW）。
    // 无匹配 ABI 的下载地址时改为打开 GitHub Releases 让用户手动下载
    fun startUpdateDownload(info: UpdateChecker.UpdateInfo) {
        val url = info.downloadUrl ?: run {
            openUrl("https://github.com/drliuhuan/koetype/releases")
            return
        }
        if (downloadProgress >= 0f) return // 已在下载
        scope.launch {
            downloadProgress = 0f
            val base = context.getExternalFilesDir(null)
            val target = if (base != null) File(File(base, "update"), "KoeType-latest.apk") else null
            // onProgress 在 IO 线程回调：Compose 快照状态线程安全，可直接写进度
            val ok = target != null &&
                UpdateDownloader.download(url, target, ProxyHelper.proxyForDownload(prefs)) { p ->
                    downloadProgress = p
                }
            downloadProgress = -1f
            if (ok && target != null) {
                updateInfo = null
                installApk(context, target)
            } else {
                Toast.makeText(context, "下载失败，请稍后重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    SectionCard("关于 KoeType") {
        // 版本：从 BuildConfig 读 versionName（versionName "0.1"）
        AboutInfoRow("版本", BuildConfig.VERSION_NAME)

        Divider(modifier = Modifier.padding(vertical = 4.dp))

        // 检查更新：点击查询 GitHub 最新版；无新版 Toast，有新版弹对话框
        AboutLinkRow(
            "检查更新",
            if (checkingUpdate) "检查中…" else "点击检查",
            onClick = ::checkForUpdate
        )

        Divider(modifier = Modifier.padding(vertical = 4.dp))

        // GitHub 仓库：可点击跳转浏览器
        AboutLinkRow("GitHub 仓库", KOETYPE_GITHUB_URL, onClick = { openUrl(KOETYPE_GITHUB_URL) })

        Divider(modifier = Modifier.padding(vertical = 4.dp))

        // 许可：可折叠，展开显示 PolyForm 许可与模型版权声明
        LicenseBlock(onOpenUrl = ::openUrl)

        Divider(modifier = Modifier.padding(vertical = 4.dp))

        // 致谢：上游开源项目与模型作者
        ThanksBlock()

        Divider(modifier = Modifier.padding(vertical = 4.dp))

        // 捐赠：支付宝链接 + 微信收款码 + 声明
        DonateBlock(onOpenUrl = ::openUrl)
    }

    // 有新版：弹对话框，标题版本号 + release notes 摘要 + 下载更新/稍后；下载中显示进度条
    updateInfo?.takeIf { it.hasUpdate }?.let { info ->
        AlertDialog(
            onDismissRequest = { if (downloadProgress < 0f) updateInfo = null },
            title = { Text("发现新版本 v${info.latestVersion}") },
            text = {
                if (downloadProgress >= 0f) {
                    Column {
                        Text("正在下载更新…", style = MaterialTheme.typography.body2)
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(progress = downloadProgress)
                    }
                } else {
                    Column {
                        // 发行版标题（GitHub name 字段）与 tag 不同时展示
                        if (info.releaseName.isNotBlank() && info.releaseName != "v${info.latestVersion}") {
                            Text("发行版：${info.releaseName}", style = MaterialTheme.typography.caption)
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        Text(summarizeReleaseNotes(info.releaseNotes), style = MaterialTheme.typography.body2)
                        if (info.downloadUrl == null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "未找到匹配本机 CPU 架构（${Build.SUPPORTED_ABIS.firstOrNull() ?: "未知"}）的安装包，请手动下载。",
                                style = MaterialTheme.typography.caption
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (downloadProgress < 0f) {
                    TextButton(onClick = { startUpdateDownload(info) }) {
                        Text(if (info.downloadUrl != null) "下载更新" else "手动下载")
                    }
                }
            },
            dismissButton = {
                if (downloadProgress < 0f) {
                    TextButton(onClick = { updateInfo = null }) { Text("稍后") }
                }
            }
        )
    }
}

/** release notes 摘要：取 body 前 ~200 字符；空内容给提示 */
private fun summarizeReleaseNotes(body: String): String {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return "请前往 GitHub 查看更新说明。"
    return if (trimmed.length > 200) trimmed.take(200) + "…" else trimmed
}

/** 安装已下载的 APK：FileProvider 授权 + ACTION_VIEW；未知来源安装确认由系统处理 */
private fun installApk(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(uri, "application/vnd.android.package-archive")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "打开安装包失败：${e.message}", Toast.LENGTH_SHORT).show()
    }
}

/** 只读信息行（版本等）：左标签右取值 */
@Composable
private fun AboutInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.body2)
    }
}

/** 可点击链接行：左侧标签，右侧主题色可点链接 */
@Composable
private fun AboutLinkRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value, color = MaterialTheme.colors.primary, style = MaterialTheme.typography.body2)
    }
}

/** 许可信息：默认折叠，点标题展开/收起 */
@Composable
private fun LicenseBlock(onOpenUrl: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("许可", style = MaterialTheme.typography.subtitle2, modifier = Modifier.weight(1f))
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
            contentDescription = null
        )
    }
    if (expanded) {
        Text(
            "本软件采用 PolyForm Noncommercial License 1.0.0：非商业用途可免费使用、复制、修改、分发；商业使用须作者书面许可。",
            style = MaterialTheme.typography.caption
        )
        AboutLinkRow("许可文本", POLYFORM_LICENSE_URL, onClick = { onOpenUrl(POLYFORM_LICENSE_URL) })
        Text(
            "模型版权：sherpa-onnx 及识别/标点模型 Apache-2.0、llama.cpp MIT、Qwen2.5 GGUF Apache-2.0。版权归各自上游作者，保留上游声明。",
            style = MaterialTheme.typography.caption
        )
    }
}

/** 致谢：上游开源项目与模型作者 */
@Composable
private fun ThanksBlock() {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("致谢", style = MaterialTheme.typography.subtitle2)
        Text(
            "sherpa-onnx（k2-fsa）· 中文识别模型（csukuangfj）· 标点模型（ranger810）",
            style = MaterialTheme.typography.caption
        )
        Text(
            "llama.cpp（ggerganov）· Qwen（阿里）· Sayboard（Elisha Azaria，产品启发）",
            style = MaterialTheme.typography.caption
        )
    }
}

/** 捐赠：支付宝链接 + 微信收款码图片（点按放大）+ 声明 */
@Composable
private fun DonateBlock(onOpenUrl: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("捐赠", style = MaterialTheme.typography.subtitle2)

        // 支付宝：可点击链接跳转
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenUrl(KOETYPE_ALIPAY_URL) }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Buy me some tokens. ⚡ 支付宝捐赠",
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colors.primary
            )
            Text("跳转", color = MaterialTheme.colors.primary, style = MaterialTheme.typography.caption)
        }

        // 微信：收款码图片，点按放大查看
        DonateWechatImage()

        Text(
            "捐赠不代表商业授权；商业使用请联系作者（GitHub Issues）。",
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.secondary
        )
    }
}

/** 微信收款码：宽 180dp 居中显示，点按弹放大对话框 */
@Composable
private fun DonateWechatImage() {
    var showZoom by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(R.drawable.donate_wechat),
            contentDescription = "微信收款码",
            modifier = Modifier
                .width(180.dp)
                .clickable { showZoom = true }
        )
    }
    if (showZoom) {
        AlertDialog(
            onDismissRequest = { showZoom = false },
            text = {
                Image(
                    painter = painterResource(R.drawable.donate_wechat),
                    contentDescription = "微信收款码",
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = { showZoom = false }) {
                    Text("关闭")
                }
            }
        )
    }
}
