package com.drliuhuan.sayboardpro.downloader

import android.content.Context
import android.util.Log
import com.drliuhuan.sayboardpro.AppPrefs
import com.drliuhuan.sayboardpro.Constants
import com.drliuhuan.sayboardpro.CrashLogger
import com.drliuhuan.sayboardpro.net.ProxyHelper
import java.io.BufferedInputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.security.MessageDigest

/**
 * sherpa-onnx 模型下载器。
 *
 * sherpa 官方把模型发布为 HF 仓库里的松散文件（encoder/decoder/joiner/tokens），
 * 同时也提供 GitHub release 的 .tar.bz2 整包。为满足"国内可下"，这里用
 * **单文件下载** + 多镜像回退：
 *   1. https://huggingface.co/{repo}/resolve/main/{file}
 *   2. https://hf-mirror.com/{repo}/resolve/main/{file}   （HF 国内镜像）
 *   3. https://modelscope.cn/models/{repo}/resolve/master/{file}  （魔搭社区）
 * 每个文件按顺序尝试各镜像，第一个成功的写盘；全部文件下载完即安装完成。
 *
 * 不引入 tar.bz2 解压（需要 bzip2 库），所以整包型 URL 不在此列；
 * 自定义 zip 包走 [ZipTools.extractZipTo]。
 */
object SherpaModelDownloader {

    private const val TAG = "SherpaModelDownloader"

    /** 二进制模型文件最小有效大小：代理/镜像错误页、截断流几乎都远小于该值 */
    private const val MIN_VALID_MODEL_BYTES: Long = 100L * 1024 // 100 KB

    /** 已安装模型目录校验：encoder ≥1MB（0 字节/截断下载的明显信号，与 SherpaProvider.verifyModelFiles 对齐） */
    private const val MIN_ENCODER_BYTES = 1_048_576L

    /** decoder/joiner/tokens/bpe 最小字节数（zipformer 系列远大于此，仅做损坏兜底） */
    private const val MIN_SMALL_MODEL_BYTES = 1_024L

    /** 标点模型主文件最小字节数（int8 约 75MB，仅做损坏兜底） */
    private const val MIN_PUNCT_MODEL_BYTES = 1_048_576L

    /**
     * GitHub 整包下载重入保护：设置页"中文 int8 预设下载"与"下载标点模型"两个入口都走
     * [downloadAndInstallGitHubBundle]，避免同时拉同一份 247MB zip 并写同一批目录。
     */
    @Volatile
    private var githubBundleDownloading = false

    interface Callback {
        fun onProgress(progress: Float)
        fun onSuccess(modelDir: File)
        fun onError(message: String)
    }

    /** 一个需要下载的文件：远端名 → 本地文件名 */
    data class ModelFile(val remoteName: String, val localName: String)

    /** sherpa 模型目录内文件 → 构建 OnlineModelConfig 的映射 */
    data class SherpaModelConfig(
        val encoder: String,
        val decoder: String,
        val joiner: String,
        val tokens: String,
        val modelType: String,
        val modelingUnit: String,
        val bpeVocab: String = ""
    )

    /** 模型预设：label 展示名 / name 本地目录名 / repoId HF 仓库 / files 文件列表 / config 构建配置 */
    data class ModelPreset(
        val label: String,
        val name: String,
        val repoId: String,
        val files: List<ModelFile>,
        val config: SherpaModelConfig
    )

    val PRESETS = listOf(
        // 中文流式 Zipformer int8，2025-06-30 发布，char-based 训练（cjkchar 建模单元）
        // 体积按实测修正（问题 3）：encoder.int8.onnx 约 154MB，全套含 decoder/joiner/tokens 约 167MB，
        // 用户实测 159MB，标注 160MB（不再写"以实际下载为准"）。
        ModelPreset(
            label = "中文流式 Zipformer int8（推荐，约 160MB）",
            name = "sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30",
            repoId = "csukuangfj/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30",
            files = listOf(
                ModelFile("encoder.int8.onnx", "encoder.int8.onnx"),
                ModelFile("decoder.onnx", "decoder.onnx"),
                ModelFile("joiner.int8.onnx", "joiner.int8.onnx"),
                ModelFile("tokens.txt", "tokens.txt")
            ),
            config = SherpaModelConfig(
                encoder = "encoder.int8.onnx",
                decoder = "decoder.onnx",
                joiner = "joiner.int8.onnx",
                tokens = "tokens.txt",
                modelType = "zipformer2",
                modelingUnit = "cjkchar"
            )
        ),
        // 中文流式小模型 14M（更快更省内存，准确率略低）
        ModelPreset(
            label = "中文流式小模型 14M（更小更快，约 30MB，以实际下载为准）",
            name = "sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23",
            repoId = "csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23",
            files = listOf(
                ModelFile("encoder-epoch-99-avg-1.int8.onnx", "encoder-epoch-99-avg-1.int8.onnx"),
                ModelFile("decoder-epoch-99-avg-1.onnx", "decoder-epoch-99-avg-1.onnx"),
                ModelFile("joiner-epoch-99-avg-1.int8.onnx", "joiner-epoch-99-avg-1.int8.onnx"),
                ModelFile("tokens.txt", "tokens.txt")
            ),
            config = SherpaModelConfig(
                encoder = "encoder-epoch-99-avg-1.int8.onnx",
                decoder = "decoder-epoch-99-avg-1.onnx",
                joiner = "joiner-epoch-99-avg-1.int8.onnx",
                tokens = "tokens.txt",
                modelType = "zipformer",
                modelingUnit = "cjkchar"
            )
        ),
        // 英文流式 Zipformer int8，2023-02-21 发布，bpe 建模单元（英文需 bpe.model 词表）
        ModelPreset(
            label = "英文流式 Zipformer int8（约 70MB，以实际下载为准）",
            name = "sherpa-onnx-streaming-zipformer-en-2023-02-21",
            repoId = "csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-02-21",
            files = listOf(
                ModelFile("encoder-epoch-99-avg-1.int8.onnx", "encoder-epoch-99-avg-1.int8.onnx"),
                ModelFile("decoder-epoch-99-avg-1.onnx", "decoder-epoch-99-avg-1.onnx"),
                ModelFile("joiner-epoch-99-avg-1.int8.onnx", "joiner-epoch-99-avg-1.int8.onnx"),
                ModelFile("tokens.txt", "tokens.txt"),
                ModelFile("bpe.model", "bpe.model")
            ),
            config = SherpaModelConfig(
                encoder = "encoder-epoch-99-avg-1.int8.onnx",
                decoder = "decoder-epoch-99-avg-1.onnx",
                joiner = "joiner-epoch-99-avg-1.int8.onnx",
                tokens = "tokens.txt",
                modelType = "zipformer",
                modelingUnit = "bpe",
                bpeVocab = "bpe.model"
            )
        )
    )

    /**
     * 校验一个目录是否为完整的 sherpa 模型（encoder/decoder/joiner/tokens 齐全且非空；
     * bpe 模型还需 bpe.model）。
     * 不仅查存在性、还查文件大小：下载失败/卸载残留会留下同名但截断（0 字节/半截）的文件，
     * 只查 exists() 会把这类目录误判为"已安装"，导致点麦克风时跳过"下载模型"提示、却在
     * prepare 阶段才报"模型文件异常"。这里用 [File.length] 一并覆盖存在性与最小尺寸。
     */
    fun validateModelDir(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val config = configForDir(dir) ?: return false
        return File(dir, config.encoder).length() >= MIN_ENCODER_BYTES &&
            File(dir, config.decoder).length() >= MIN_SMALL_MODEL_BYTES &&
            File(dir, config.joiner).length() >= MIN_SMALL_MODEL_BYTES &&
            File(dir, config.tokens).length() >= MIN_SMALL_MODEL_BYTES &&
            (config.bpeVocab.isBlank() || File(dir, config.bpeVocab).length() >= MIN_SMALL_MODEL_BYTES)
    }

    /**
     * 根据目录内的文件名猜它是哪个预设；匹配不到返回 null。
     * 取「所需文件全部存在且匹配文件数最多」的预设：中文 14M 与英文预设共用
     * encoder-epoch-99-avg-1.int8.onnx 等文件名，英文目录还额外含 bpe.model，
     * 只有按匹配文件数取最大才能正确区分（避免英文目录被误判成中文 cjkchar）。
     */
    fun configForDir(dir: File): SherpaModelConfig? {
        val files = dir.listFiles()?.map { it.name }?.toSet() ?: return null
        var best: Pair<Int, SherpaModelConfig>? = null
        PRESETS.forEach { preset ->
            val required = preset.files.map { it.localName }
            if (files.containsAll(required) && (best == null || required.size > best!!.first)) {
                best = required.size to preset.config
            }
        }
        return best?.second
    }

    /** 扫描已安装的 sherpa 模型目录 */
    fun scanInstalled(context: Context): List<File> {
        val root = Constants.getSherpaModelsDirectory(context)
        return root.listFiles()
            ?.filter { it.isDirectory && validateModelDir(it) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /**
     * 按语言找已安装的 sherpa 模型目录（按预设目录名里的语言段识别：含 "-zh-" 为中文，
     * 含 "-en-" 为英文）。目标语言未下载时返回 null。
     * @param language 取值 [AppPrefs.LANG_EN] / [AppPrefs.LANG_ZH]
     */
    fun findInstalledModelByLanguage(context: Context, language: String): File? {
        val token = if (language == AppPrefs.LANG_EN) "-en-" else "-zh-"
        return scanInstalled(context).firstOrNull { it.name.contains(token) }
    }

    // ── 用户自有 GitHub 仓库整包（中文 int8 ASR + 标点，一次下完） ──────────

    /**
     * GitHub 仓库整包模型下载地址（Release v1.0-models）。
     * zip 内部结构（解压后）：
     *   models/sherpa-zh-int8/{zh_encoder.int8.onnx, zh_decoder.onnx,
     *       zh_joiner.int8.onnx, zh_tokens.txt}
     *   models/punct/{punct_int8.onnx, punct_tokens.json}
     * 解压时按映射改名为现有 config/校验期望的文件名（见 [downloadAndInstallGitHubBundle]）。
     */
    const val GITHUB_MODELS_ZIP_URL =
        "https://github.com/drliuhuan/koetype/releases/download/v1.0-models/koetype-models-v1.0.zip"

    /** GitHub 整包 zip 预期字节数（Content-Length 硬校验，247,089,282 B） */
    const val GITHUB_MODELS_ZIP_EXPECTED_BYTES: Long = 247_089_282L

    /** GitHub 整包 zip 的 SHA-256（下载完成后硬校验，防篡改/损坏） */
    const val GITHUB_MODELS_ZIP_SHA256 = "3847fdcf3b1245fa8080045dd16c6ac50fd25985399167cef9461bbcf1f048bc"

    /** 整包里含的中文 int8 预设目录名（即 [PRESETS] 中第一条的 name） */
    const val GITHUB_ZH_INT8_PRESET_NAME = "sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30"

    // ── 标点模型（OfflinePunctuation，本地断句加标点） ──────────────────

    /**
     * 标点恢复模型（sherpa-onnx OfflinePunctuation，CT-Transformer 中英 int8）。
     *
     * 流式 ASR 天生不输出标点，断句标点完全依赖 LLM 纠错；LLM 未配置/失败时输出裸文本。
     * 集成标点模型后可本地断句加标点，毫秒级、不依赖 LLM。
     *
     * 仓库：`ranger810/sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8`
     * （官方 csukuangfj 仓库只有 fp32 约 294MB、无 int8；int8 由第三方 ranger810 发布）。
     * 文件：model.int8.onnx（int8 约 75.5MB）+ tokens.json（词表，JSON 数组 4.2MB）。
     * 下载到 files/SherpaModels/punct/（与 ASR 模型同根目录，独立子目录），
     * 复用本对象的多镜像下载逻辑（HF → hf-mirror → modelscope 回退）。
     */
    const val PUNCT_REPO_ID =
        "ranger810/sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8"

    /** 标点模型主文件（int8 发布为 model.int8.onnx，非 model.onnx） */
    const val PUNCT_MODEL_FILE = "model.int8.onnx"

    /** 标点模型目录名（与 ASR 模型预设平级，固定目录方便下载/删除/状态判断） */
    const val PUNCT_MODEL_NAME = "punct"

    /** 标点模型词表候选名：int8 仓库为 tokens.json（JSON 数组）；个别发布为 vocab.txt/tokens.txt */
    private val PUNCT_VOCAB_CANDIDATES = listOf("tokens.json", "vocab.txt", "tokens.txt")

    /** 标点模型目录：files/SherpaModels/punct/ */
    fun getPunctModelDir(context: Context): File =
        File(Constants.getSherpaModelsDirectory(context), PUNCT_MODEL_NAME)

    /** 校验标点模型目录完整性：model.int8.onnx（非空且 ≥1MB）+ 任一候选词表（非空）齐全 */
    fun validatePunctDir(dir: File): Boolean {
        if (!dir.isDirectory) return false
        if (File(dir, PUNCT_MODEL_FILE).length() < MIN_PUNCT_MODEL_BYTES) return false
        return PUNCT_VOCAB_CANDIDATES.any { File(dir, it).length() > 0 }
    }

    /** 扫描已安装的标点模型目录；未安装返回 null */
    fun scanPunctInstalled(context: Context): File? {
        val dir = getPunctModelDir(context)
        return if (validatePunctDir(dir)) dir else null
    }

    /**
     * 下载并安装标点模型到 files/SherpaModels/punct/，完成后自动写入
     * [AppPrefs.punctModelPath]。走与 ASR 模型相同的多镜像下载（HF → hf-mirror → modelscope）。
     * 词表先试 tokens.json，失败回退 vocab.txt/tokens.txt（不同发布命名略有差异）。
     *
     * 下载源为 [AppPrefs.DOWNLOAD_SOURCE_GITHUB] 时改走 GitHub 整包
     * （[downloadAndInstallGitHubBundle]，一次同时装好中文 int8 ASR + 标点）。
     */
    fun downloadPunctModel(context: Context, callback: Callback) {
        if (AppPrefs(context).downloadSource == AppPrefs.DOWNLOAD_SOURCE_GITHUB) {
            downloadAndInstallGitHubBundle(context, callback)
            return
        }
        Thread {
            val targetDir = getPunctModelDir(context)
            try {
                targetDir.mkdirs()
                val prefs = AppPrefs(context)

                // 1. model.int8.onnx（模型主体，必须）
                val modelOk = downloadWithMirrors(
                    PUNCT_REPO_ID,
                    PUNCT_MODEL_FILE,
                    File(targetDir, PUNCT_MODEL_FILE),
                    prefs
                ) { frac -> callback.onProgress(frac * 0.7f) }
                if (!modelOk) {
                    // 保留半截文件供下次断点续传（不再整目录删除）
                    callback.onError("网络或代理异常导致下载失败：$PUNCT_MODEL_FILE，所有镜像均不可用，请重试")
                    return@Thread
                }

                // 2. 词表：依次尝试候选名，第一个成功即完成
                var vocabOk = false
                for (name in PUNCT_VOCAB_CANDIDATES) {
                    if (downloadWithMirrors(PUNCT_REPO_ID, name, File(targetDir, name), prefs) { frac ->
                            callback.onProgress(0.7f + frac * 0.3f)
                        }) {
                        vocabOk = true
                        break
                    }
                }
                if (!vocabOk) {
                    // 保留已下载的模型主体（75MB）与半截词表，下次续传不再重下
                    callback.onError("网络或代理异常导致下载失败：词表文件，所有镜像均不可用，请重试")
                    return@Thread
                }

                if (!validatePunctDir(targetDir)) {
                    Constants.deleteRecursive(targetDir)
                    callback.onError("标点模型文件下载不完整（网络或代理异常），请重试")
                    return@Thread
                }

                AppPrefs(context).punctModelPath = targetDir.absolutePath
                callback.onSuccess(targetDir)
            } catch (e: Exception) {
                Log.e(TAG, "punct model download failed", e)
                Constants.deleteRecursive(targetDir)
                callback.onError(e.message ?: "下载失败")
            }
        }.start()
    }

    /** 删除已安装的标点模型并清空 [AppPrefs.punctModelPath] */
    fun deletePunctModel(context: Context) {
        val dir = getPunctModelDir(context)
        if (dir.exists()) Constants.deleteRecursive(dir)
        AppPrefs(context).punctModelPath = ""
    }

    /**
     * 从 [GITHUB_MODELS_ZIP_URL] 一次下载并安装中文 int8 ASR 与标点模型。
     *
     * zip 整包（约 247MB）下载到临时文件后按映射选择性解压：
     * - models/sherpa-zh-int8/ 目录全部文件 → files/SherpaModels/<中文 int8 预设名>/，重命名为 config 期望的
     *   encoder.int8.onnx / decoder.onnx / joiner.int8.onnx / tokens.txt；
     * - models/punct/ 目录全部文件 → files/SherpaModels/punct/，重命名为 [PUNCT_MODEL_FILE]
     *   （model.int8.onnx）与 tokens.json。
     *
     * 下载校验：zip 实收字节与 [GITHUB_MODELS_ZIP_EXPECTED_BYTES] 硬比对（Content-Length 之外
     * 再锚定已知大小）；解压后复用 [validateModelDir] / [validatePunctDir] 验证文件存在且非空。
     * 成功后同时写入 [AppPrefs.sherpaModelPath] 与 [AppPrefs.punctModelPath]。
     * 走 [ProxyHelper.Usage.DOWNLOAD]，受"模型下载走代理"开关控制（GitHub 国内直连可用，有代理兜底）。
     */
    fun downloadAndInstallGitHubBundle(context: Context, callback: Callback) {
        if (githubBundleDownloading) {
            callback.onError("已有模型下载在进行中，请稍候")
            return
        }
        githubBundleDownloading = true
        Thread {
            val filename = GITHUB_MODELS_ZIP_URL.substringAfterLast('/').substringBefore('?')
            val tempFile = Constants.getTemporaryDownloadLocation(context, filename)
            tempFile.parentFile?.mkdirs()
            try {
                val prefs = AppPrefs(context)
                val ok = downloadFile(
                    GITHUB_MODELS_ZIP_URL, tempFile, prefs,
                    onProgress = { frac -> callback.onProgress(frac * 0.8f) },
                    expectedSize = GITHUB_MODELS_ZIP_EXPECTED_BYTES
                )
                if (!ok) {
                    callback.onError("网络或代理异常导致下载失败（GitHub 仓库 zip），请重试")
                    return@Thread
                }

                // SHA-256 硬校验：字节数一致还不够，用已知哈希锚定包内容，防篡改/损坏。
                // 不匹配时删除临时文件并失败返回（损坏内容无续传价值）。
                if (!verifySha256(tempFile, GITHUB_MODELS_ZIP_SHA256)) {
                    tempFile.delete()
                    callback.onError("模型包完整性校验失败（SHA-256 不匹配），请重新下载")
                    return@Thread
                }

                val zhPreset = PRESETS.first { it.name == GITHUB_ZH_INT8_PRESET_NAME }
                val sherpaDir = Constants.getSherpaModelDir(context, zhPreset.name)
                val punctDir = getPunctModelDir(context)
                callback.onProgress(0.8f)

                val sherpaOk = ZipTools.extractMapped(
                    tempFile, sherpaDir,
                    mapOf(
                        "models/sherpa-zh-int8/zh_encoder.int8.onnx" to zhPreset.config.encoder,
                        "models/sherpa-zh-int8/zh_decoder.onnx" to zhPreset.config.decoder,
                        "models/sherpa-zh-int8/zh_joiner.int8.onnx" to zhPreset.config.joiner,
                        "models/sherpa-zh-int8/zh_tokens.txt" to zhPreset.config.tokens
                    )
                )
                val punctOk = sherpaOk && ZipTools.extractMapped(
                    tempFile, punctDir,
                    mapOf(
                        "models/punct/punct_int8.onnx" to PUNCT_MODEL_FILE,
                        "models/punct/punct_tokens.json" to PUNCT_VOCAB_CANDIDATES.first()
                    )
                )
                if (!sherpaOk || !punctOk) {
                    if (sherpaDir.exists()) Constants.deleteRecursive(sherpaDir)
                    if (punctDir.exists()) Constants.deleteRecursive(punctDir)
                    tempFile.delete()
                    callback.onError("解压失败：zip 包内容不符合预期（缺少模型文件），请重新下载")
                    return@Thread
                }

                callback.onProgress(0.95f)
                if (!validateModelDir(sherpaDir) || !validatePunctDir(punctDir)) {
                    if (sherpaDir.exists()) Constants.deleteRecursive(sherpaDir)
                    if (punctDir.exists()) Constants.deleteRecursive(punctDir)
                    tempFile.delete()
                    callback.onError("模型文件下载不完整（zip 校验失败），请重试")
                    return@Thread
                }

                prefs.sherpaModelPath = sherpaDir.absolutePath
                prefs.punctModelPath = punctDir.absolutePath
                // 下载+校验+解压全部成功：清理断点临时文件（断点记录即半截 zip，随成功删除）
                tempFile.delete()
                callback.onProgress(1f)
                callback.onSuccess(sherpaDir)
            } catch (e: Exception) {
                Log.e(TAG, "github bundle download failed", e)
                // 保留 tempFile 半截文件供下次断点续传
                callback.onError(e.message ?: "下载失败")
            } finally {
                githubBundleDownloading = false
            }
        }.start()
    }

    /** 下载预设并安装到 files/SherpaModels/<name>/，完成后自动设为当前模型 */
    fun downloadAndInstall(
        context: Context,
        preset: ModelPreset,
        callback: Callback
    ) {
        val prefs = AppPrefs(context)
        // 下载源为 GitHub 仓库时，中文 int8 预设走整包下载（一次同时装好 ASR + 标点）；
        // 14M/英文预设不在整包里，保持原 HF 多镜像单文件下载。
        if (prefs.downloadSource == AppPrefs.DOWNLOAD_SOURCE_GITHUB &&
            preset.name == GITHUB_ZH_INT8_PRESET_NAME
        ) {
            downloadAndInstallGitHubBundle(context, callback)
            return
        }
        Thread {
            val targetDir = Constants.getSherpaModelDir(context, preset.name)
            try {
                targetDir.mkdirs()
                val total = preset.files.size

                for ((index, file) in preset.files.withIndex()) {
                    val target = File(targetDir, file.localName)
                    val ok = downloadWithMirrors(preset.repoId, file.remoteName, target, prefs) { frac ->
                        // 整体进度 = 已完成文件 + 当前文件进度，按文件数均摊；
                        // 映射到 0-0.9，预留 0.9-1.0 给标点模型补下（见下方），保证进度单调
                        callback.onProgress(0.9f * (index + frac) / total)
                    }
                    if (!ok) {
                        // 保留已下载的半截文件供下次断点续传（不再整目录删除）
                        callback.onError("网络或代理异常导致下载失败：${file.remoteName}，所有镜像均不可用，请重试")
                        return@Thread
                    }
                }

                if (!validateModelDir(targetDir)) {
                    Constants.deleteRecursive(targetDir)
                    callback.onError("模型文件下载不完整（网络或代理异常），请重试")
                    return@Thread
                }

                AppPrefs(context).sherpaModelPath = targetDir.absolutePath
                // 标点模型：HF 单文件模式下 ASR 与标点是两个独立下载任务——
                // GitHub 整包模式已含标点，此处必须补下，否则标点失效（punct=none）。
                // 仅 HF 下载源补下：GitHub 源下 downloadPunctModel 会重定向到整包下载，
                // 14M/英文预设（不在整包内）会误触发整包并覆盖刚装好的 ASR，不能在此调用。
                if (prefs.downloadSource == AppPrefs.DOWNLOAD_SOURCE_HF) {
                    try {
                        val punctDir = getPunctModelDir(context)
                        if (!validatePunctDir(punctDir)) {
                            CrashLogger.d(TAG, "punct missing, downloading after ASR")
                            downloadPunctModel(context, object : Callback {
                                override fun onProgress(frac: Float) {
                                    // 标点进度映射到 0.9-1.0 区间（ASR 已完成）
                                    callback.onProgress(0.9f + frac * 0.1f)
                                }
                                override fun onSuccess(file: File) {
                                    CrashLogger.d(TAG, "punct installed after ASR")
                                    callback.onProgress(1f)
                                    // 仍回传 ASR 目录：调用方会把 onSuccess 的目录写入 sherpaModelPath，
                                    // 若传标点目录会把 ASR 路径覆盖成标点（与整包路径 onSuccess(sherpaDir) 一致）
                                    callback.onSuccess(File(prefs.sherpaModelPath))
                                }
                                override fun onError(msg: String) {
                                    // 标点失败不阻塞 ASR 可用性：已装好的 ASR 保留，仅提示标点缺失
                                    CrashLogger.w(TAG, "punct download failed after ASR: $msg")
                                    callback.onSuccess(File(prefs.sherpaModelPath)) // ASR 仍成功
                                }
                            })
                            return@Thread
                        }
                    } catch (e: Exception) {
                        CrashLogger.w(TAG, "punct check failed: ${e.message}")
                    }
                }
                callback.onSuccess(File(prefs.sherpaModelPath))
            } catch (e: Exception) {
                Log.e(TAG, "download failed", e)
                Constants.deleteRecursive(targetDir)
                callback.onError(e.message ?: "下载失败")
            }
        }.start()
    }

    /** 下载并解压自定义 zip 到模型目录，完成后自动设为当前模型 */
    fun downloadAndInstallZip(
        context: Context,
        url: String,
        callback: Callback
    ) {
        Thread {
            val filename = url.substringAfterLast('/').substringBefore('?')
                .ifEmpty { "model.zip" }
            val tempFile = Constants.getTemporaryDownloadLocation(context, filename)
            tempFile.parentFile?.mkdirs()
            try {
                val prefs = AppPrefs(context)
                val ok = downloadFile(
                    url, tempFile, prefs,
                    onProgress = { frac -> callback.onProgress(frac * 0.9f) }
                )
                if (!ok) {
                    callback.onError("网络或代理异常导致下载失败：$url，请重试")
                    return@Thread
                }
                // 模型目录名取文件名去扩展名
                val modelName = filename.substringBeforeLast('.')
                val targetDir = Constants.getSherpaModelDir(context, modelName)
                if (!ZipTools.extractZipTo(tempFile, targetDir)) {
                    Constants.deleteRecursive(targetDir)
                    tempFile.delete()
                    callback.onError("解压失败：不是有效的 zip 压缩包")
                    return@Thread
                }
                if (!validateModelDir(targetDir)) {
                    Constants.deleteRecursive(targetDir)
                    tempFile.delete()
                    callback.onError("不是有效的 sherpa 模型包（缺少 encoder/decoder/joiner/tokens）")
                    return@Thread
                }
                callback.onProgress(1f)
                AppPrefs(context).sherpaModelPath = targetDir.absolutePath
                // 成功：清理断点临时文件
                tempFile.delete()
                callback.onSuccess(targetDir)
            } catch (e: Exception) {
                Log.e(TAG, "zip download failed", e)
                // 保留 tempFile 半截文件供下次断点续传
                callback.onError(e.message ?: "下载失败")
            }
        }.start()
    }

    // ── 下载实现 ────────────────────────────────────────────────────

    private fun downloadWithMirrors(
        repoId: String,
        remoteName: String,
        target: File,
        prefs: AppPrefs,
        onProgress: (Float) -> Unit
    ): Boolean {
        val urls = mirrorUrls(repoId, remoteName)
        for (url in urls) {
            try {
                if (downloadFile(url, target, prefs, onProgress)) return true
            } catch (e: Exception) {
                Log.w(TAG, "mirror failed: $url", e)
            }
            // 失败尝试下一个镜像；保留 target 残留（半截文件）供下一镜像用 Range 续传，
            // downloadFile 内部会在服务端不支持 Range 时删除并全量重下
        }
        return false
    }

    private fun mirrorUrls(repoId: String, remoteName: String): List<String> {
        val file = remoteName
        return listOf(
            "https://huggingface.co/$repoId/resolve/main/$file",
            "https://hf-mirror.com/$repoId/resolve/main/$file",
            "https://modelscope.cn/models/$repoId/resolve/master/$file"
        )
    }

    /** 文本/JSON 类资产（词表/bpe/tokens.json）：不做二进制魔数校验，仅要求非空 */
    private fun isTextAsset(fileName: String): Boolean =
        fileName.endsWith(".txt") || fileName.endsWith(".model") ||
            fileName.endsWith(".bpe") || fileName.endsWith(".json")

    /** 二进制模型文件：需要大小 + 魔数校验 */
    private fun isBinaryModel(fileName: String): Boolean =
        fileName.endsWith(".onnx") || fileName.endsWith(".gguf") || fileName.endsWith(".bin")

    /** 代理/镜像把错误页当 200 返回时，Content-Type 通常是 text/html：一律拒收 */
    private fun isHtmlContentType(contentType: String?): Boolean {
        if (contentType.isNullOrBlank()) return false
        val type = contentType.substringBefore(';').trim().lowercase()
        return type == "text/html" || type.endsWith("/html") || "html" in type
    }

    /**
     * ONNX 为 protobuf：序列化按字段号升序，首个字段 ir_version(field 1, varint) 的
     * 首字节恒为 0x08；个别发布也见 ASCII "ONNX" 文本头。两种都接受。
     */
    private fun hasOnnxMagic(file: File): Boolean {
        val header = ByteArray(4)
        val n = file.inputStream().use { it.read(header) }
        if (n < 1) return false
        if (header[0] == 0x08.toByte()) return true
        return n >= 4 && String(header).startsWith("ONNX")
    }

    /**
     * 下载 [url] 到 [target]，返回是否成功。支持断点续传：目标文件已存在（半截文件）
     * 时带 HTTP Range 头从断点继续；服务端忽略 Range（返回 200）或 Range 越界（416，
     * 半截文件恰好等于/超过总大小）时删除残留全量重下。失败时**保留**残留文件供下次
     * 续传（网络中断/杀进程后重进设置页续传的主路径），由调用方回退下一镜像。
     *
     * 校验强化（问题 3）：
     * 1. HTTP 非 200/206 / Content-Type 为 HTML 错误页（代理损坏数据）→ 拒收；
     * 2. Content-Length/Content-Range 与实收字节不一致（截断/代理串流）→ 判损坏；
     * 3. 二进制模型文件需满足最小大小 + ONNX 魔数，文本资产（tokens/bpe）仅要求非空。
     */
    private fun downloadFile(
        url: String,
        target: File,
        prefs: AppPrefs,
        onProgress: (Float) -> Unit,
        expectedSize: Long = -1L
    ): Boolean =
        // 代理认证作用域覆盖连接全生命周期（建连 → 传输 → 断开）；
        // SOCKS 带认证不受支持（忽略用户名密码，由 open() 记录日志提示）
        ProxyHelper.withProxy(prefs, ProxyHelper.Usage.DOWNLOAD) { proxy ->
            try {
                var existing = if (target.exists()) target.length() else 0L
                var conn = openDownloadConnection(url, proxy, prefs, existing)
                try {
                    var code = conn.responseCode

                    // 服务端忽略 Range（200）或 Range 越界（416）：删除残留，重开连接全量重下
                    if (existing > 0 &&
                        (code == HttpURLConnection.HTTP_OK || code == 416)
                    ) {
                        conn.disconnect()
                        if (!target.delete()) {
                            Log.w(TAG, "Cannot delete stale file ${target.name}")
                            return@withProxy false
                        }
                        existing = 0L
                        conn = openDownloadConnection(url, proxy, prefs, 0L)
                        code = conn.responseCode
                    }

                    if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                        Log.w(TAG, "HTTP $code for $url")
                        return@withProxy false
                    }

                    // 代理/镜像可能把错误页当 200/206 返回：HTML body 一律拒收
                    if (isHtmlContentType(conn.contentType)) {
                        Log.w(TAG, "Rejecting HTML body for $url (contentType=${conn.contentType})")
                        return@withProxy false
                    }

                    val resumed = code == HttpURLConnection.HTTP_PARTIAL
                    val startOffset = if (resumed) existing else 0L
                    val total = resolveTotalBytes(conn, startOffset)
                    var written = startOffset
                    RandomAccessFile(target, "rw").use { raf ->
                        raf.seek(startOffset)
                        BufferedInputStream(conn.inputStream).use { input ->
                            val buffer = ByteArray(16 * 1024)
                            var n: Int
                            while (input.read(buffer).also { n = it } >= 0) {
                                raf.write(buffer, 0, n)
                                written += n
                                if (total > 0) {
                                    onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                                }
                            }
                        }
                    }

                    if (!validateDownloaded(target.name, target, total, written, expectedSize)) {
                        target.delete()
                        return@withProxy false
                    }

                    onProgress(1f)
                    true
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "download failed: $url", e)
                // 保留残留文件：网络中断/超时是断点续传的主场景
                false
            }
        }

    /** 打开下载连接；目标已有 [existingBytes] 字节时带 Range 头请求续传 */
    private fun openDownloadConnection(
        url: String,
        proxy: java.net.Proxy?,
        prefs: AppPrefs,
        existingBytes: Long
    ): HttpURLConnection {
        val conn = ProxyHelper.open(url, proxy, prefs)
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = true
        if (existingBytes > 0) {
            conn.setRequestProperty("Range", "bytes=$existingBytes-")
        }
        return conn
    }

    /** 期望总字节数：206 用 Content-Range 的 total，200 用 Content-Length；未知返回 -1 */
    private fun resolveTotalBytes(conn: HttpURLConnection, resumedBytes: Long): Long {
        val contentRange = conn.getHeaderField("Content-Range")
        val totalFromRange = contentRange?.substringAfterLast('/')?.trim()?.toLongOrNull()
        if (totalFromRange != null && totalFromRange > 0) return totalFromRange
        val length = conn.contentLengthLong
        return if (length > 0) resumedBytes + length else -1
    }

    /** 计算文件 SHA-256 并与期望十六进制串比对（忽略大小写）；文件缺失/IO 异常/不匹配均返回 false。 */
    private fun verifySha256(file: File, expectedHex: String): Boolean {
        if (expectedHex.isBlank() || !file.exists()) return false
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var n: Int
                while (input.read(buffer).also { n = it } >= 0) {
                    digest.update(buffer, 0, n)
                }
            }
            val actualHex = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            if (!actualHex.equals(expectedHex, ignoreCase = true)) {
                Log.w(TAG, "SHA-256 mismatch for ${file.name}: got $actualHex, expected $expectedHex")
                false
            } else {
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "SHA-256 verification failed for ${file.name}: ${e.message}")
            false
        }
    }

    /** 校验已下载文件，失败返回 false（调用方负责删除残留）。错误统一归因网络/代理。 */
    private fun validateDownloaded(
        fileName: String,
        target: File,
        expected: Long,
        downloaded: Long,
        expectedSize: Long = -1L
    ): Boolean {
        // 调用方显式锚定期望大小（如 GitHub zip 整包 247MB）→ 实收必须完全一致
        if (expectedSize > 0 && downloaded != expectedSize) {
            Log.w(TAG, "Size mismatch for $fileName: got $downloaded, expected $expectedSize")
            return false
        }
        // Content-Length 存在但与实收不一致 → 截断/损坏
        if (expected > 0 && downloaded != expected) {
            Log.w(TAG, "Truncated download for $fileName: got $downloaded, expected $expected")
            return false
        }
        // 二进制模型：必须超过最小有效大小；文本资产（tokens/bpe）仅要求非空
        if (isTextAsset(fileName)) {
            if (downloaded <= 0) {
                Log.w(TAG, "Empty text asset for $fileName")
                return false
            }
        } else if (downloaded < MIN_VALID_MODEL_BYTES) {
            Log.w(TAG, "Suspiciously small file for $fileName: $downloaded bytes")
            return false
        }
        // 二进制模型魔数校验
        if (isBinaryModel(fileName) && !hasOnnxMagic(target)) {
            Log.w(TAG, "Bad ONNX magic for $fileName")
            return false
        }
        return true
    }
}
