package com.drliuhuan.sayboardpro

import android.content.Context
import android.content.SharedPreferences
import com.drliuhuan.sayboardpro.downloader.SherpaModelDownloader
import org.json.JSONArray
import java.io.File

/**
 * 应用偏好设置，基于 SharedPreferences。
 * 参考 Sayboard / SayboardNeo 的 AppPrefs，轻量直接的 SharedPreferences 封装。
 *
 * API key 只存在这里（SharedPreferences），代码中不硬编码任何密钥。
 */
class AppPrefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    init {
        // 设置变更全局监听：任何设置项改动都留 CrashLogger 日志（词库增删/LLM 配置/代理等）
        ensurePrefListener(context.applicationContext)
    }

    companion object {
        private const val FILE = "koetype_prefs"

        // STT provider 取值
        const val PROVIDER_WHISPER = "whisper"
        const val PROVIDER_SHERPA = "sherpa"

        // 键盘语言取值
        const val LANG_ZH = "zh"
        const val LANG_EN = "en"

        // LLM 纠错模式取值
        const val LLM_MODE_ONLINE = "online"
        const val LLM_MODE_LOCAL = "local"

        // 键盘高度档位
        const val KEYBOARD_HEIGHT_LOW = "low"
        const val KEYBOARD_HEIGHT_MEDIUM = "medium"
        const val KEYBOARD_HEIGHT_HIGH = "high"

        // 代理协议取值
        const val PROXY_PROTOCOL_HTTP = "http"
        const val PROXY_PROTOCOL_SOCKS5 = "socks5"
        const val PROXY_PROTOCOL_SOCKS5H = "socks5h"

        // 模型下载源取值
        const val DOWNLOAD_SOURCE_GITHUB = "github"
        const val DOWNLOAD_SOURCE_HF = "hf"

        // 键名
        private const val KEY_PROVIDER = "stt_provider"
        private const val KEY_WHISPER_BASE_URL = "whisper_base_url"
        private const val KEY_WHISPER_API_KEY = "whisper_api_key"
        private const val KEY_WHISPER_MODEL = "whisper_model"
        private const val KEY_WHISPER_LANGUAGE = "whisper_language"
        private const val KEY_SHERPA_MODEL_PATH = "sherpa_model_path"
        private const val KEY_SHERPA_HOTWORDS_SCORE = "sherpa_hotwords_score"
        private const val KEY_STT_FOREGROUND_KEEP_ALIVE = "stt_foreground_keep_alive"
        private const val KEY_PUNCT_MODEL_PATH = "punct_model_path"
        private const val KEY_SILENCE_TIMEOUT_MS = "silence_timeout_ms"
        private const val KEY_SILENCE_THRESHOLD = "silence_threshold"
        private const val KEY_AUTO_CAPITALIZE = "auto_capitalize"
        private const val KEY_DICT_JSON = "dictionary_json"
        private const val KEY_DICT_FUZZY_PINYIN = "dictionary_fuzzy_pinyin"
        private const val KEY_KEYBOARD_SWITCH_BACK = "keyboard_switch_back"
        private const val KEY_KEYBOARD_LANGUAGE = "keyboard_language"
        private const val KEY_SMART_ENTER = "smart_enter"
        private const val KEY_KEYBOARD_HEIGHT = "keyboard_height"

        // 设置页推荐系统输入法的一次性提示（task49c：引导不强制）
        private const val KEY_SETTINGS_IME_TIP_SHOWN = "settings_ime_tip_shown"

        // 代理设置
        private const val KEY_PROXY_ENABLED = "proxy_enabled"
        private const val KEY_PROXY_PROTOCOL = "proxy_protocol"
        private const val KEY_PROXY_HOST = "proxy_host"
        private const val KEY_PROXY_PORT = "proxy_port"
        private const val KEY_PROXY_USER = "proxy_user"
        private const val KEY_PROXY_PASS = "proxy_pass"
        private const val KEY_PROXY_FOR_DOWNLOAD = "proxy_for_download"
        private const val KEY_PROXY_FOR_STT = "proxy_for_stt"
        private const val KEY_PROXY_FOR_LLM = "proxy_for_llm"

        // 模型下载源
        private const val KEY_DOWNLOAD_SOURCE = "download_source"

        // LLM 纠错
        private const val KEY_LLM_CORRECTION_ENABLED = "llm_correction_enabled"
        private const val KEY_LLM_MODE = "llm_correction_mode"
        private const val KEY_LLM_LOCAL_MODEL_PATH = "llm_local_model_path"
        private const val KEY_LLM_ONLINE_BASE_URL = "llm_online_base_url"
        private const val KEY_LLM_ONLINE_API_KEY = "llm_online_api_key"
        private const val KEY_LLM_ONLINE_MODEL = "llm_online_model"
        private const val KEY_LLM_ONLINE_MAX_TOKENS = "llm_online_max_tokens"
        private const val KEY_LLM_DISABLE_THINKING = "llm_disable_thinking"
        private const val KEY_LLM_CUSTOM_PROMPT = "llm_custom_prompt"

        // LLM 纠错统计（累计，设置页展示）
        private const val KEY_STAT_PROMPT_TOKENS = "stat_prompt_tokens"
        private const val KEY_STAT_COMPLETION_TOKENS = "stat_completion_tokens"
        private const val KEY_STAT_REQUESTS = "stat_requests"
        private const val KEY_STAT_TOTAL_LATENCY = "stat_total_latency_ms"

        // 在线供应商选择（识别 / LLM 共用一套预置框架，见 ServiceCatalog）
        private const val KEY_STT_PROVIDER_ID = "stt_provider_id"
        private const val KEY_LLM_PROVIDER_ID = "llm_provider_id"

        // "获取模型"拉到的模型列表缓存（JSON 字符串数组，方便离线看/选）
        private const val KEY_WHISPER_CACHED_MODELS = "whisper_cached_models"
        private const val KEY_LLM_CACHED_MODELS = "llm_cached_models"

        /** 在线 LLM 默认端点（OpenAI 兼容） */
        const val DEFAULT_LLM_BASE_URL = "https://api.openai.com/v1"

        /**
         * 热词 boost 权重默认值：sherpa-onnx 按 token 级加分，中文模型一个字是一个 token。
         * 官方建议 1~10。过低（如 3）时偏置太弱，压不过模型默认输出的同音字
         * （实测"甲钴胺"被识别成"甲骨胺"）；过高（如 20+）时 modified_beam_search 会
         * 反复选中热词路径，产生"示例人名示例人名示例人名""示例医院医院"这类重复输出。
         * 8 是平衡点：对常见词条足够有偏置，又为重复防护留有余量（上限见 [MAX_HOTWORDS_SCORE]）。
         * 仅改默认值，已有用户设置过的 score 不强制覆盖。
         */
        const val DEFAULT_HOTWORDS_SCORE = 8f

        /** 热词 boost 允许的最大值：超过此值重复输出风险陡增（读写都夹取到此范围） */
        const val MAX_HOTWORDS_SCORE = 10f

        // ── 设置变更全局日志（进程级单例监听） ───────────────────────
        @Volatile
        private var listenerRegistered = false

        // 注册进程级 SharedPreferences 变更监听：任何设置改动都打一条 CrashLogger.d("PREF", ...)。
        // 进程内只注册一次（静态标志防泄漏，不会每 new 一次 AppPrefs 就多一个 listener）；
        // 回调不依赖 AppPrefs 实例，直接读 SharedPreferences。跳过高频统计键（stat_*）与
        // 模型列表缓存（*_cached_models），避免每次纠错刷屏。
        private fun ensurePrefListener(context: Context) {
            if (listenerRegistered) return
            listenerRegistered = true
            val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            sp.registerOnSharedPreferenceChangeListener { _, key ->
                if (key == null) return@registerOnSharedPreferenceChangeListener
                // 跳过高频统计键与缓存列表（每次纠错都变，刷屏）
                if (key.startsWith("stat_") || key.endsWith("_cached_models")) {
                    return@registerOnSharedPreferenceChangeListener
                }
                val value = sp.all[key]
                val display = when {
                    key.contains("api_key") || key.contains("token") || key.contains("pass") ||
                        key.contains("secret") -> "***"
                    key == "dictionary_json" -> (value as? String)?.take(200) ?: "null"
                    else -> value?.toString()?.take(100) ?: "null"
                }
                CrashLogger.d("PREF", "change: $key=$display")
            }
        }
    }

    // ── STT provider ───────────────────────────────────────────────

    /**
     * 识别引擎选择（抽屉"识别与模型"分区顶部）：
     * 本地模型（[PROVIDER_SHERPA]，默认）/ 在线 API（[PROVIDER_WHISPER]）。
     * 合并分区后默认本地模型——本地模型相关 UI 只在选中它时启用。
     */
    var activeProvider: String
        get() = sp.getString(KEY_PROVIDER, PROVIDER_SHERPA)!!
        set(value) = sp.edit().putString(KEY_PROVIDER, value).apply()

    val isWhisperActive: Boolean
        get() = activeProvider == PROVIDER_WHISPER

    var whisperBaseUrl: String
        get() = sp.getString(KEY_WHISPER_BASE_URL, "")!!
        set(value) = sp.edit().putString(KEY_WHISPER_BASE_URL, value).apply()

    var whisperApiKey: String
        get() = sp.getString(KEY_WHISPER_API_KEY, "")!!
        set(value) = sp.edit().putString(KEY_WHISPER_API_KEY, value).apply()

    var whisperModel: String
        get() = sp.getString(KEY_WHISPER_MODEL, "")!!
        set(value) = sp.edit().putString(KEY_WHISPER_MODEL, value).apply()

    var whisperLanguage: String
        get() = sp.getString(KEY_WHISPER_LANGUAGE, "")!!
        set(value) = sp.edit().putString(KEY_WHISPER_LANGUAGE, value).apply()

    /** 在线语音识别选中的供应商 id（[com.drliuhuan.sayboardpro.providers.ServiceCatalog.sttProviders]），默认自定义 */
    var sttProviderId: String
        get() = sp.getString(KEY_STT_PROVIDER_ID, "custom")!!
        set(value) = sp.edit().putString(KEY_STT_PROVIDER_ID, value).apply()

    /** "获取模型"拉到的在线语音模型列表缓存（JSON 字符串数组，离线可看/选择） */
    var whisperCachedModels: List<String>
        get() = parseStringList(sp.getString(KEY_WHISPER_CACHED_MODELS, "[]")!!)
        set(value) = sp.edit().putString(KEY_WHISPER_CACHED_MODELS, toJsonStringList(value)).apply()

    /** 当前使用的 sherpa 模型目录（files/SherpaModels/<model>/） */
    var sherpaModelPath: String
        get() = sp.getString(KEY_SHERPA_MODEL_PATH, "")!!
        set(value) = sp.edit().putString(KEY_SHERPA_MODEL_PATH, value).apply()

    /** 本地标点模型目录（files/SherpaModels/punct/）。下载成功后由下载器写入；删除时清空 */
    var punctModelPath: String
        get() = sp.getString(KEY_PUNCT_MODEL_PATH, "")!!
        set(value) = sp.edit().putString(KEY_PUNCT_MODEL_PATH, value).apply()

    /** 标点模型是否已下载（路径已配置且目录完整）。识别 final 后本地断句加标点用 */
    val punctModelInstalled: Boolean
        get() = punctModelPath.isNotBlank() && SherpaModelDownloader.validatePunctDir(File(punctModelPath))

    /**
     * 热词 boost 权重：注入 sherpa hotwords 时的全局分值（sherpa 按 token 级加分）。
     * 范围 0~10（[MAX_HOTWORDS_SCORE]），读写都夹取——防止历史高分值（旧默认 20）残留，
     * 在 modified_beam_search 下反复选中热词路径导致重复输出。
     */
    var sherpaHotwordsScore: Float
        get() = sp.getFloat(KEY_SHERPA_HOTWORDS_SCORE, DEFAULT_HOTWORDS_SCORE)
            .coerceIn(0f, MAX_HOTWORDS_SCORE)
        set(value) = sp.edit()
            .putFloat(KEY_SHERPA_HOTWORDS_SCORE, value.coerceIn(0f, MAX_HOTWORDS_SCORE))
            .apply()

    /**
     * 模型进程前台保活（通知栏常驻）：开启=模型常驻不回收（有常驻通知）；
     * 关闭=普通服务（模型可能被系统回收，加载稍慢）。默认开启。
     * 切换后下次模型进程启动时生效（:stt 进程读 prefs 是跨进程，常驻期间不热更新）。
     */
    var sttForegroundKeepAlive: Boolean
        get() = sp.getBoolean(KEY_STT_FOREGROUND_KEEP_ALIVE, true)
        set(value) = sp.edit().putBoolean(KEY_STT_FOREGROUND_KEEP_ALIVE, value).apply()

    // ── 录音设置 ────────────────────────────────────────────────────

    /** 静音持续多久(毫秒)后自动结束录音，0 表示不自动结束 */
    var silenceTimeoutMs: Int
        get() = sp.getInt(KEY_SILENCE_TIMEOUT_MS, 1500)
        set(value) = sp.edit().putInt(KEY_SILENCE_TIMEOUT_MS, value).apply()

    /** 静音判定阈值(0.0~1.0 的 RMS)，音量低于此值视为静音 */
    var silenceThreshold: Float
        get() = sp.getFloat(KEY_SILENCE_THRESHOLD, 0.02f)
        set(value) = sp.edit().putFloat(KEY_SILENCE_THRESHOLD, value).apply()

    var autoCapitalize: Boolean
        get() = sp.getBoolean(KEY_AUTO_CAPITALIZE, true)
        set(value) = sp.edit().putBoolean(KEY_AUTO_CAPITALIZE, value).apply()

    // ── 键盘行为 ────────────────────────────────────────────────────

    /** 收起键盘后自动切回上一个输入法（如 Gboard），默认开启 */
    var keyboardSwitchBack: Boolean
        get() = sp.getBoolean(KEY_KEYBOARD_SWITCH_BACK, true)
        set(value) = sp.edit().putBoolean(KEY_KEYBOARD_SWITCH_BACK, value).apply()

    /** 键盘语言（zh=中文，en=英文），默认中文；驱动符号全半角与 sherpa 语言模型切换 */
    var keyboardLanguage: String
        get() = sp.getString(KEY_KEYBOARD_LANGUAGE, LANG_ZH)!!
        set(value) = sp.edit().putString(KEY_KEYBOARD_LANGUAGE, value).apply()

    /** 当前是否为中文键盘 */
    val isKeyboardZh: Boolean
        get() = keyboardLanguage == LANG_ZH

    /** 智能回车：开启时回车键执行输入框的 IME_ACTION（搜索/发送/下一行），关闭时固定插入换行（默认） */
    var smartEnter: Boolean
        get() = sp.getBoolean(KEY_SMART_ENTER, false)
        set(value) = sp.edit().putBoolean(KEY_SMART_ENTER, value).apply()

    /** 键盘高度档位（low=30% 屏高，默认；medium=40%；high=50%） */
    var keyboardHeight: String
        get() = sp.getString(KEY_KEYBOARD_HEIGHT, KEYBOARD_HEIGHT_LOW)!!
        set(value) = sp.edit().putString(KEY_KEYBOARD_HEIGHT, value).apply()

    /** 键盘高度占屏幕高度的比例（竖屏基准；横屏由 KeyboardView 在基础上加成） */
    val keyboardHeightFraction: Float
        get() = when (keyboardHeight) {
            KEYBOARD_HEIGHT_HIGH -> 0.50f
            KEYBOARD_HEIGHT_MEDIUM -> 0.40f
            else -> 0.30f
        }

    /**
     * 设置页推荐系统输入法的一次性提示是否已展示（task49c）。
     * 持久标志：IME 每次自杀重建，实例字段会在每次键盘弹出都提示；只有
     * SharedPreferences 才能保证"整个应用只提示一次"。默认 false。
     */
    var settingsImeTipShown: Boolean
        get() = sp.getBoolean(KEY_SETTINGS_IME_TIP_SHOWN, false)
        set(value) = sp.edit().putBoolean(KEY_SETTINGS_IME_TIP_SHOWN, value).apply()

    // ── 词典（JSON 序列化，见 CustomDictionary） ───────────────────

    var dictionaryJson: String
        get() = sp.getString(KEY_DICT_JSON, "[]")!!
        set(value) = sp.edit().putString(KEY_DICT_JSON, value).apply()

    /**
     * 模糊拼音：开启后词典同音修正额外按平翘舌/前后鼻音/n-l 等常见混淆归并匹配
     * （如"是/四、新/星"）。默认关闭——模糊匹配可能误伤，由用户在设置中主动开启。
     */
    var dictionaryFuzzyPinyin: Boolean
        get() = sp.getBoolean(KEY_DICT_FUZZY_PINYIN, false)
        set(value) = sp.edit().putBoolean(KEY_DICT_FUZZY_PINYIN, value).apply()

    // 词库启用词条数（STATE 总览日志用；只读统计 dictionary_json 中的启用行）。
    fun enabledHotwordsCount(): Int {
        val raw = sp.getString(KEY_DICT_JSON, "[]")!!
        // 旧 JSON 格式（[ 开头）由 CustomDictionary 读取时迁移，此处按行格式统计
        if (raw.isBlank() || raw.trimStart().startsWith("[")) return 0
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .count { line ->
                val flag = line.split(',').getOrNull(3)?.trim()
                flag.isNullOrEmpty() || (flag != "0" && !flag.equals("false", ignoreCase = true))
            }
    }

    // ── LLM 纠错（说完后异步纠错断句） ─────────────────────────────

    /** 总开关：默认关闭，需在设置页手动打开 */
    var llmCorrectionEnabled: Boolean
        get() = sp.getBoolean(KEY_LLM_CORRECTION_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_LLM_CORRECTION_ENABLED, value).apply()

    /** 纠错模式：在线 API（[LLM_MODE_ONLINE]，默认）/ 本地模型（[LLM_MODE_LOCAL]） */
    var llmCorrectionMode: String
        get() = sp.getString(KEY_LLM_MODE, LLM_MODE_ONLINE)!!
        set(value) = sp.edit().putString(KEY_LLM_MODE, value).apply()

    /** 本地模型 .gguf 文件绝对路径；空表示尚未下载/选择模型 */
    var llmLocalModelPath: String
        get() = sp.getString(KEY_LLM_LOCAL_MODEL_PATH, "")!!
        set(value) = sp.edit().putString(KEY_LLM_LOCAL_MODEL_PATH, value).apply()

    /**
     * LLM 纠错是否已配置（键盘 LLM 按钮据此决定开关或弹配置）。
     * 本地模式：本地模型路径已配置且文件存在；在线模式：baseUrl/apiKey/model 任一为空视为未配置。
     */
    val llmConfigured: Boolean
        get() = if (llmCorrectionMode == LLM_MODE_LOCAL) {
            llmLocalModelPath.isNotBlank() && File(llmLocalModelPath).exists()
        } else {
            llmOnlineBaseUrl.isNotBlank() && llmOnlineApiKey.isNotBlank() && llmOnlineModel.isNotBlank()
        }

    /** 在线端点 baseUrl（OpenAI 兼容 /chat/completions） */
    var llmOnlineBaseUrl: String
        get() = sp.getString(KEY_LLM_ONLINE_BASE_URL, DEFAULT_LLM_BASE_URL)!!
        set(value) = sp.edit().putString(KEY_LLM_ONLINE_BASE_URL, value).apply()

    /** API key（仅存 SharedPreferences，不硬编码） */
    var llmOnlineApiKey: String
        get() = sp.getString(KEY_LLM_ONLINE_API_KEY, "")!!
        set(value) = sp.edit().putString(KEY_LLM_ONLINE_API_KEY, value).apply()

    /** 模型名称 */
    var llmOnlineModel: String
        get() = sp.getString(KEY_LLM_ONLINE_MODEL, "")!!
        set(value) = sp.edit().putString(KEY_LLM_ONLINE_MODEL, value).apply()

    /** LLM 纠错选中的供应商 id（[com.drliuhuan.sayboardpro.providers.ServiceCatalog.llmProviders]），默认 OpenAI */
    var llmProviderId: String
        get() = sp.getString(KEY_LLM_PROVIDER_ID, "openai")!!
        set(value) = sp.edit().putString(KEY_LLM_PROVIDER_ID, value).apply()

    /** "获取模型"拉到的 LLM 模型列表缓存（JSON 字符串数组，离线可看/选择） */
    var llmCachedModels: List<String>
        get() = parseStringList(sp.getString(KEY_LLM_CACHED_MODELS, "[]")!!)
        set(value) = sp.edit().putString(KEY_LLM_CACHED_MODELS, toJsonStringList(value)).apply()

    /** 每次纠错请求的最大生成 token 数（512~1024 推荐） */
    var llmOnlineMaxTokens: Int
        get() = sp.getInt(KEY_LLM_ONLINE_MAX_TOKENS, 512)
        set(value) = sp.edit().putInt(KEY_LLM_ONLINE_MAX_TOKENS, value).apply()

    /** 关闭思考模式：请求体携带 thinking:{"type":"disabled"}（DeepSeek 官方参数）。
     *  DeepSeek 系列默认思考，会把思维链放进 content 导致上屏思考过程，默认开启关闭。
     *  OpenAI/Groq 等端点会忽略未知字段，无害；若某端点因不支持该字段报错，可关闭此开关。 */
    var llmDisableThinking: Boolean
        get() = sp.getBoolean(KEY_LLM_DISABLE_THINKING, true)
        set(value) = sp.edit().putBoolean(KEY_LLM_DISABLE_THINKING, value).apply()

    /** 用户自定义提示词（如职业/使用场景背景），附加到 LLM 纠错 system prompt 末尾；留空不附加 */
    var llmCustomPrompt: String
        get() = sp.getString(KEY_LLM_CUSTOM_PROMPT, "")!!
        set(value) = sp.edit().putString(KEY_LLM_CUSTOM_PROMPT, value).apply()

    // ── LLM 纠错统计（累计，设置页展示） ────────────────────────────

    val llmStats: LlmStats
        get() = LlmStats(
            promptTokens = sp.getLong(KEY_STAT_PROMPT_TOKENS, 0),
            completionTokens = sp.getLong(KEY_STAT_COMPLETION_TOKENS, 0),
            requestCount = sp.getInt(KEY_STAT_REQUESTS, 0),
            totalLatencyMs = sp.getLong(KEY_STAT_TOTAL_LATENCY, 0)
        )

    /**
     * 记录一次纠错请求。次数与延迟无论成功失败都累计；token 只在请求成功时计入
     * （失败时传 0）。调用方负责传入实际数值。
     */
    fun addCorrectionAttempt(latencyMs: Long, promptTokens: Int, completionTokens: Int) {
        sp.edit()
            .putInt(KEY_STAT_REQUESTS, sp.getInt(KEY_STAT_REQUESTS, 0) + 1)
            .putLong(KEY_STAT_TOTAL_LATENCY, sp.getLong(KEY_STAT_TOTAL_LATENCY, 0) + latencyMs)
            .putLong(KEY_STAT_PROMPT_TOKENS, sp.getLong(KEY_STAT_PROMPT_TOKENS, 0) + promptTokens)
            .putLong(KEY_STAT_COMPLETION_TOKENS, sp.getLong(KEY_STAT_COMPLETION_TOKENS, 0) + completionTokens)
            .apply()
    }

    /** 清零 LLM 纠错统计 */
    fun resetCorrectionStats() {
        sp.edit()
            .remove(KEY_STAT_PROMPT_TOKENS)
            .remove(KEY_STAT_COMPLETION_TOKENS)
            .remove(KEY_STAT_REQUESTS)
            .remove(KEY_STAT_TOTAL_LATENCY)
            .apply()
    }

    // ── 代理设置（模型下载 / 在线 STT / LLM 纠错可独立开关） ─────────

    /** 代理总开关：默认关闭，开启后显示下方配置 */
    var proxyEnabled: Boolean
        get() = sp.getBoolean(KEY_PROXY_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_PROXY_ENABLED, value).apply()

    /** 代理协议：[PROXY_PROTOCOL_HTTP]（默认）/ [PROXY_PROTOCOL_SOCKS5] / [PROXY_PROTOCOL_SOCKS5H] */
    var proxyProtocol: String
        get() = sp.getString(KEY_PROXY_PROTOCOL, PROXY_PROTOCOL_HTTP)!!
        set(value) = sp.edit().putString(KEY_PROXY_PROTOCOL, value).apply()

    /** 代理主机（IP 或域名） */
    var proxyHost: String
        get() = sp.getString(KEY_PROXY_HOST, "")!!
        set(value) = sp.edit().putString(KEY_PROXY_HOST, value).apply()

    /** 代理端口（1~65535，默认 1080） */
    var proxyPort: Int
        get() = sp.getInt(KEY_PROXY_PORT, 1080)
        set(value) = sp.edit().putInt(KEY_PROXY_PORT, value.coerceIn(1, 65535)).apply()

    /** 代理用户名（可选，HTTP/SOCKS5 认证用） */
    var proxyUser: String
        get() = sp.getString(KEY_PROXY_USER, "")!!
        set(value) = sp.edit().putString(KEY_PROXY_USER, value).apply()

    /** 代理密码（可选） */
    var proxyPass: String
        get() = sp.getString(KEY_PROXY_PASS, "")!!
        set(value) = sp.edit().putString(KEY_PROXY_PASS, value).apply()

    /** 模型下载走代理（sherpa ASR/标点/本地 LLM 下载），默认开启 */
    var proxyForDownload: Boolean
        get() = sp.getBoolean(KEY_PROXY_FOR_DOWNLOAD, true)
        set(value) = sp.edit().putBoolean(KEY_PROXY_FOR_DOWNLOAD, value).apply()

    /** 在线语音识别（Whisper API）走代理，默认关闭 */
    var proxyForStt: Boolean
        get() = sp.getBoolean(KEY_PROXY_FOR_STT, false)
        set(value) = sp.edit().putBoolean(KEY_PROXY_FOR_STT, value).apply()

    /** LLM 纠错（在线 OpenAI 兼容 API）走代理，默认关闭 */
    var proxyForLlm: Boolean
        get() = sp.getBoolean(KEY_PROXY_FOR_LLM, false)
        set(value) = sp.edit().putBoolean(KEY_PROXY_FOR_LLM, value).apply()

    // ── 模型下载源（GitHub 仓库整包 / HuggingFace 多镜像） ───────────────

    /**
     * 模型下载源，默认 [DOWNLOAD_SOURCE_GITHUB]：
     * - GitHub 仓库：中文 int8 ASR + 标点模型走整包 zip（[SherpaModelDownloader.GITHUB_MODELS_ZIP_URL]），
     *   一次下完、国内可直连；14M/英文预设不受此开关影响，始终走 HF 多镜像。
     * - HuggingFace 多镜像：全部预设与标点都走 HF → hf-mirror → modelscope 单文件回退。
     */
    var downloadSource: String
        get() = sp.getString(KEY_DOWNLOAD_SOURCE, DOWNLOAD_SOURCE_GITHUB)!!
        set(value) = sp.edit().putString(KEY_DOWNLOAD_SOURCE, value).apply()

    // ── 模型列表缓存序列化（JSON 字符串数组，保持顺序） ───────────────

    private fun parseStringList(json: String): List<String> = try {
        val arr = JSONArray(json)
        buildList { for (i in 0 until arr.length()) add(arr.getString(i)) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun toJsonStringList(list: List<String>): String = JSONArray(list).toString()
}

/** LLM 纠错累计统计（设置页"LLM 统计"区展示） */
data class LlmStats(
    val promptTokens: Long,
    val completionTokens: Long,
    val requestCount: Int,
    val totalLatencyMs: Long
) {
    val totalTokens: Long get() = promptTokens + completionTokens
    val avgLatencyMs: Long get() = if (requestCount > 0) totalLatencyMs / requestCount else 0
}
