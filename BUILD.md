# SayboardPro（语音输入）构建说明

安卓语音输入法（IME），Kotlin + Jetpack Compose + InputMethodService。
STT 用 **sherpa-onnx**（本地离线流式）+ **Whisper 兼容 API**（在线）双 provider，
保留 SayboardNeo 的词库（9 类词性）+ LLM 纠错（断句/错别字）+ 日志导出等成熟功能。
核心新增：把用户词库**启用词条注入 sherpa 原生热词**（hotwords），大幅提升专名识别率。

## 一、环境要求

| 项 | 要求 |
|---|---|
| JDK | 17（AGP 8.x 要求） |
| Android SDK | compileSdk 34（`platforms;android-34`）、build-tools 34.x、platform-tools |
| Gradle | 8.2（wrapper 配置为 gradle-8.2-bin.zip） |
| Android Gradle Plugin | 8.2.2（根 build.gradle 已声明） |

> **关于 gradle wrapper**：构建时二选一：
> - 使用系统已安装的 gradle：`gradle assembleDebug`
> - 或先生成 wrapper：`gradle wrapper --gradle-version 8.2`，再用 `./gradlew assembleDebug`
>
> 需要本机有 `ANDROID_HOME` 环境变量（或项目根放一个 `local.properties` 写 `sdk.dir=...`）。

## 二、sherpa-onnx 集成方式（AAR）

**官方没有 Maven 坐标**（Maven Central 只有第三方 `com.bihe0832.android:lib-sherpa-onnx`，
版本旧且非官方，未采用）。采用官方发布的 **AAR**：

- AAR 已下载到 `app/libs/sherpa-onnx-1.13.5.aar`（49MB，来自
  https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.5/sherpa-onnx-1.13.5.aar ）
- `app/build.gradle` 用 `implementation files('libs/sherpa-onnx-1.13.5.aar')` 引入
- AAR 内置各 ABI 的 `libonnxruntime.so`（arm64-v8a / armeabi-v7a / x86 / x86_64），
  **无需额外加 onnxruntime-android 依赖**
- Java/Kotlin 接口包名：`com.k2fsa.sherpa.onnx`（`OnlineRecognizer` 等）
- JNI 库 `libsherpa-onnx-jni.so` 随 AAR 自动打进 APK

## 三、构建

```bash
cd SayboardPro
gradle assembleDebug
# 或
gradle wrapper --gradle-version 8.2
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

首次构建需联网下载 androidx/compose 依赖（google() / mavenCentral()）。
若网络受限可设置镜像仓库，在 `settings.gradle` 的 repositories 里追加。

## 四、SDK 组件

- `platforms;android-34`
- `build-tools;34.0.0`
- `platform-tools`
- `cmdline-tools;latest`

```bash
sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"
```

## 五、运行与使用

1. `adb install app-debug.apk`
2. 打开应用（设置页），授权麦克风。
3. 系统设置 → 语言与输入法 → 启用 "SayboardPro"。
4. 切到任意输入框，选择 "SayboardPro" 输入法。
5. 点麦克风说话，再点一下结束并上屏；或长按麦克风，松开结束。

### 配置识别引擎
- **Whisper 兼容 API（在线）**：设置页填写接口地址 / API 密钥 / 模型，可填：
  - Groq: `https://api.groq.com/openai/v1/audio/transcriptions`，模型 `whisper-large-v3-turbo`
  - 硅基流动: `https://api.siliconflow.cn/v1/audio/transcriptions`
  - 智谱 GLM / 任意 OpenAI 兼容端点
- **Sherpa 本地（离线，默认）**：设置页 → Sherpa 模型 → 选择预设下载，下载完自动设为当前模型。

## 六、Sherpa 模型下载说明

sherpa 官方模型发布为 HF 仓库里的松散文件（encoder/decoder/joiner/tokens）。
为国内可下，下载器对**每个文件**按顺序尝试多个镜像：

1. `https://huggingface.co/{repo}/resolve/main/{file}`
2. `https://hf-mirror.com/{repo}/resolve/main/{file}`（HF 国内镜像）
3. `https://modelscope.cn/models/{repo}/resolve/master/{file}`（魔搭）

预设（`SherpaModelDownloader.PRESETS`）：

| 预设 | 说明 | 文件 |
|---|---|---|
| `sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30` | 中文流式 Zipformer int8（推荐，约 100MB，以实际下载为准），modelType=zipformer2 | encoder.int8.onnx / decoder.onnx / joiner.int8.onnx / tokens.txt |
| `sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23` | 中文流式小模型 14M（更快更省内存，约 30MB，以实际下载为准），modelType=zipformer | encoder-epoch-99-avg-1.int8.onnx / decoder-epoch-99-avg-1.onnx / joiner-epoch-99-avg-1.int8.onnx / tokens.txt |

安装位置：`Android/data/com.drliuhuan.sayboardpro/files/SherpaModels/<modelName>/`。
支持自定义 zip URL（下载后解压，需包含上述 4 类文件）。

## 七、热词注入（核心价值）

sherpa-onnx 的 `OnlineRecognizer.createStream(hotwords)` 支持流级热词：
- 格式：`/` 分隔的 `词:权重`，如 `示例医院:20/示例人名:20`（权重可调，设置页默认 20）。
  注意分隔符必须是 `/`——sherpa 内部会把 `/` 替换成换行再逐行解析 `词:权重`，
  用逗号会被当成一个词条，导致热词失效
- **实现**：`SherpaProvider.buildHotwords()` 把 `CustomDictionary.enabledEntries()`（启用的词条）
  映射为热词字符串，每次会话 `createStream(hotwords)` 注入
- **词库变更即重建 recognizer**：`SttEngine.ensureProvider()` 的 providerConfigKey 含词典签名，
  词库一改，下一次 start 会重建 provider → 重新加载 OnlineRecognizer，热词立即生效
- **前置条件**（sherpa 文档明确要求，已在 `buildRecognizer` 中设置）：
  - 解码方式 `decodingMethod = "modified_beam_search"`（热词只在 beam search 下生效）
  - 中文模型 `modelingUnit = "cjkchar"`（char-based 训练；中英混排模型需 cjkchar+bpe + bpeVocab）
  - 关闭内置端点检测 `enableEndpoint = false`（静音自动结束由录音灵敏度负责）

## 八、权限

- `RECORD_AUDIO`：语音输入必需，IME 与设置页都会检查。
- `INTERNET`：Whisper 在线转写 / 模型下载。
- 说明：模型下载使用后台线程（无前台服务）。下载大模型时若切走应用可能被系统挂起，建议保持前台。

## 九、项目结构

```
SayboardPro/
├── settings.gradle / build.gradle / gradle.properties
└── app/
    ├── build.gradle              # 依赖：sherpa-onnx AAR (app/libs)
    ├── libs/sherpa-onnx-1.13.5.aar
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/                  # strings(中文)、图标、method.xml
        └── java/com/drliuhuan/sayboardpro/
            ├── SettingsActivity.kt   # 设置页入口（也是启动入口）
            ├── AppPrefs.kt           # SharedPreferences 封装（含 sherpa/Whisper/LLM 配置）
            ├── Constants.kt          # 目录与常量（SherpaModels 目录）
            ├── CrashLogger.kt        # 崩溃日志捕获 + 导出
            ├── ime/
            │   ├── SayboardProIME.kt # InputMethodService 主类
            │   ├── TextManager.kt    # partial/final 上屏 + 词典后处理 + 纠错替换
            │   ├── KeyboardView.kt   # Compose 键盘界面（点按/长按手势 + 纠错状态条）
            │   └── IMELifecycleOwner.kt
            ├── stt/
            │   ├── SttProvider.kt       # STT 抽象接口
            │   ├── SherpaProvider.kt    # 本地 sherpa-onnx（流式 + 热词注入）★核心
            │   ├── WhisperApiProvider.kt# 在线 Whisper 兼容 API
            │   ├── SttEngine.kt         # 编排：选 provider/录音路由/静音结束/词典签名重建
            │   └── WavEncoder.kt        # PCM → WAV
            ├── audio/AudioRecorder.kt   # 16kHz 单声道 PCM 录音
            ├── data/
            │   ├── CustomDictionary.kt          # 词典（词汇+词性+权重，9 类词性）
            │   └── DictionaryPostProcessor.kt   # 识别文本词汇规范化
            ├── downloader/
            │   ├── SherpaModelDownloader.kt     # sherpa 模型预设 + 多镜像下载 ★新增
            │   └── ZipTools.kt                  # 自定义 zip 解压
            ├── llm/TextCorrector.kt             # LLM 纠错（OpenAI 兼容 + thinking disabled）
            └── ui/SettingsScreen.kt             # 设置页 Compose 界面（六分区）
```
