package com.drliuhuan.sayboardpro.providers

/**
 * 在线服务供应商预置表（LLM 纠错 / 在线语音识别共用一套框架）。
 *
 * 每个预置供应商含默认 baseUrl 与默认模型，但**所有字段都可修改**：
 * 选中供应商后把默认值填进设置项（AppPrefs 的 whisperBaseUrl/llmOnlineBaseUrl 等），
 * 用户可编辑 URL / 模型 / key。
 *
 * 备注字段说明"国内直连 / 需代理 / 见服务商文档"等信息，展示在设置页下拉下方。
 * 拿不准的端点（沙箱无网、未联网验证）仍预置但备注"端点可能需调整"，见各条 TODO。
 */
data class ProviderPreset(
    /** 稳定标识（AppPrefs 持久化用），"custom" 为自定义项 */
    val id: String,
    /** 展示名 */
    val name: String,
    /** 默认 baseUrl；自定义项为空 */
    val baseUrl: String,
    /** 默认模型；自定义项为空 */
    val defaultModel: String,
    /** 是否需要代理（仅展示提示，不自动切换代理开关） */
    val needProxy: Boolean,
    /** 备注：国内直连 / 需代理 / 见服务商文档 / 端点可能需调整 */
    val note: String
) {
    val isCustom: Boolean get() = id == "custom"
}

object ServiceCatalog {

    /** LLM 纠错供应商（OpenAI 兼容 /chat/completions） */
    val llmProviders = listOf(
        ProviderPreset(
            id = "openai",
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-4o-mini",
            needProxy = true,
            note = "需代理"
        ),
        ProviderPreset(
            id = "deepseek",
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            defaultModel = "deepseek-chat",
            needProxy = false,
            note = "国内直连"
        ),
        ProviderPreset(
            id = "siliconflow",
            name = "硅基流动 SiliconFlow",
            baseUrl = "https://api.siliconflow.cn/v1",
            defaultModel = "Qwen/Qwen2.5-7B-Instruct",
            needProxy = false,
            note = "国内直连"
        ),
        ProviderPreset(
            id = "opencode-go",
            name = "OpenCode Go",
            baseUrl = "https://opencode.ai/zen/go/v1",
            defaultModel = "deepseek-v4-flash",
            needProxy = false,
            note = "见服务商文档"
        ),
        ProviderPreset(
            id = "opencode-zen",
            name = "OpenCode Zen",
            baseUrl = "https://opencode.ai/zen/v1",
            defaultModel = "deepseek-v4-flash",
            needProxy = false,
            note = "见服务商文档"
        ),
        ProviderPreset(
            id = "kimi",
            name = "Kimi (Moonshot)",
            baseUrl = "https://api.moonshot.cn/v1",
            defaultModel = "moonshot-v1-8k",
            needProxy = false,
            note = "国内直连"
        ),
        ProviderPreset(
            id = "glm",
            name = "GLM (智谱)",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            defaultModel = "glm-4-flash",
            needProxy = false,
            note = "国内直连"
        ),
        ProviderPreset(
            id = "gemini",
            name = "Gemini",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            defaultModel = "gemini-2.5-flash",
            needProxy = true,
            note = "需代理；OpenAI 兼容端点"
        ),
        ProviderPreset(
            id = "grok",
            name = "Grok (xAI)",
            baseUrl = "https://api.x.ai/v1",
            defaultModel = "grok-2-latest",
            needProxy = true,
            note = "需代理"
        ),
        ProviderPreset(
            id = "custom",
            name = "自定义 API",
            baseUrl = "",
            defaultModel = "",
            needProxy = false,
            note = "手动填写接口地址与模型"
        )
    )

    /**
     * 在线语音识别供应商（Whisper 兼容 /audio/transcriptions）。
     *
     * 只预置确认提供 Whisper 兼容语音 API 的供应商；Kimi / Gemini / Grok 等语音端点
     * 不确定，不在此列表（放"自定义 API"的说明里提示）。
     * TODO(待验证，沙箱无网未联网核对)：GLM / 火山引擎的 Whisper 兼容端点与模型名
     * 以平台文档为准，可能需调整；仍预置但备注"端点可能需调整"。
     */
    val sttProviders = listOf(
        ProviderPreset(
            id = "openai",
            name = "OpenAI（Whisper）",
            baseUrl = "https://api.openai.com/v1",
            defaultModel = "whisper-1",
            needProxy = true,
            note = "需代理"
        ),
        ProviderPreset(
            id = "siliconflow",
            name = "硅基流动 SiliconFlow",
            baseUrl = "https://api.siliconflow.cn/v1",
            defaultModel = "FunAudioLLM/SenseVoiceSmall",
            needProxy = false,
            note = "国内直连（确认兼容 /audio/transcriptions）"
        ),
        // TODO(待验证)：智谱 GLM 语音转写端点可能不是标准 /audio/transcriptions，
        // 若实际不支持请移入"自定义 API"并在说明中提示。
        ProviderPreset(
            id = "glm",
            name = "GLM (智谱)",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            defaultModel = "glm-4v-flash",
            needProxy = false,
            note = "国内直连；端点可能需调整"
        ),
        ProviderPreset(
            id = "groq",
            name = "Groq",
            baseUrl = "https://api.groq.com/openai/v1",
            defaultModel = "whisper-large-v3",
            needProxy = true,
            note = "需代理"
        ),
        // TODO(待验证)：火山引擎豆包 ASR 是否提供 OpenAI 兼容 /audio/transcriptions
        // 未联网确认，端点可能需调整。
        ProviderPreset(
            id = "volcengine",
            name = "豆包 / 火山引擎",
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            defaultModel = "doubao-asr",
            needProxy = false,
            note = "国内直连；端点可能需调整"
        ),
        ProviderPreset(
            id = "custom",
            name = "自定义 API",
            baseUrl = "",
            defaultModel = "",
            needProxy = false,
            note = "手动填写接口地址与模型；Kimi / Gemini / Grok 等语音端点不确定时也选此项"
        )
    )

    /** 按 id 查 LLM 供应商；找不到返回自定义项 */
    fun llmProviderById(id: String): ProviderPreset =
        llmProviders.firstOrNull { it.id == id } ?: llmProviders.last()

    /** 按 id 查语音供应商；找不到返回自定义项 */
    fun sttProviderById(id: String): ProviderPreset =
        sttProviders.firstOrNull { it.id == id } ?: sttProviders.last()
}

/**
 * 拼接 OpenAI 兼容 /chat/completions 端点。
 *
 * 兼容三种 baseUrl 形态：
 * - 已含完整路径（.../chat/completions）→ 原样；
 * - Gemini OpenAI 兼容端点（.../v1beta/openai）→ 直接追加 /chat/completions；
 * - 常规 .../v1 → /v1/chat/completions；
 * - 其他（如 .../v1beta/openai 之外的裸域名）→ 追加 /v1/chat/completions。
 */
internal fun buildChatCompletionsUrl(baseUrl: String): String {
    var url = baseUrl.trim().trimEnd('/')
    return when {
        url.endsWith("/chat/completions") -> url
        url.endsWith("/openai") -> "$url/chat/completions"
        url.endsWith("/v1") -> "$url/chat/completions"
        else -> "$url/v1/chat/completions"
    }
}

/** 拼接 /models 端点（Whisper 兼容服务一般提供，用于"获取模型"） */
internal fun buildModelsUrl(baseUrl: String): String {
    val base = baseUrl.trim().trimEnd('/')
    return if (base.endsWith("/models")) base else "$base/models"
}
