# KoeType

**KoeType** — 本地优先的安卓语音输入法（Android IME）

> **🌐 语言说明 / Language Notice**
> 本项目主要面向中文使用环境。英文界面尚未完善，英文使用环境未经验证，如有问题请以中文环境表现为准。
> This project is primarily designed for Chinese usage. The English UI is not yet polished, and the English environment has **not been fully verified**. If you encounter issues, please refer to the Chinese environment as the baseline.

语音识别、标点恢复、断句纠错全程本地处理，可选在线 API 增强。开箱即用，模型一键下载。
On-device speech recognition, punctuation restoration and sentence correction — all processed locally, with optional online API enhancement. Works out of the box with one-tap model download.

## 功能 / Features

- **流式语音识别**：sherpa-onnx 中文流式 Zipformer int8 模型，本地离线识别，边说边出字
  **Streaming speech recognition**: sherpa-onnx Chinese streaming Zipformer int8 model, fully offline, character-by-character output
- **热词偏置**：自定义词库自动注入识别器热词，专有名词、药品名、术语识别更准
  **Hotword biasing**: custom vocabulary auto-injected as recognizer hotwords for better accuracy on proper nouns and domain terms
- **标点恢复**：本地标点模型自动断句加标点，不依赖网络
  **Punctuation restoration**: local punctuation model, no network required
- **LLM 纠错**：可选在线 API 或设备端本地模型（llama.cpp，≤1.5B）对识别结果智能纠错断句
  **LLM correction**: optional online API or on-device local model (llama.cpp, ≤1.5B) for intelligent correction
- **在线语音识别**（可选）：OpenAI Whisper 兼容 API（BYOK）
  **Online speech recognition** (optional): OpenAI Whisper-compatible API (BYOK)
- **长按/点按双模式**：长按说话松开上屏，点按切换开关；连续说话自动排队，不吞句
  **Press-and-hold / tap modes**: hold to speak and release to commit; tap to toggle; consecutive utterances auto-queue
- **模型常驻**：识别模型独立进程常驻（前台服务保活，可设置关闭），键盘弹出即用、零等待
  **Resident model process**: recognition model lives in a separate process (foreground-service keep-alive, configurable), zero waiting on keyboard show
- **三进程架构**：IME / 模型 / 设置页进程隔离，切换输入法零残留、不闪退
  **Three-process architecture**: IME / model / settings isolated — no focus residue on IME switch, no crashes
- **代理支持**：HTTP / SOCKS5 协议，模型下载 / 在线识别 / LLM 纠错三个独立开关
  **Proxy support**: HTTP / SOCKS5, with independent toggles for model download / online ASR / LLM correction
- **模型加载状态可见**：键盘顶部实时显示模型加载进度，失败可一键重试
  **Visible model loading state**: live progress bar on top of keyboard, one-tap retry on failure

## 模型文件 / Model Files

本仓库托管 KoeType 使用的模型文件（Git LFS）。全部模型均为 Apache-2.0 许可，可自由使用与再分发，但需保留上游版权声明（见下）。
This repository hosts the model files used by KoeType (Git LFS). All models are Apache-2.0 licensed — free to use and redistribute, with upstream copyright notices retained (see below).

| 模型 / Model | 用途 / Purpose | 大小 / Size | 上游来源 / Upstream |
|---|---|---|---|
| `models/sherpa-zh-int8/` | 中文流式识别（encoder/decoder/joiner/tokens） / Chinese streaming ASR | ~167 MB | [csukuangfj/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30) |
| `models/punct/` | 标点恢复（model.int8.onnx + tokens.json） / Punctuation | ~80 MB | [ranger810/sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8](https://huggingface.co/ranger810/sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8) |

### SHA-256 校验 / Checksums

| 文件 / File | SHA-256 |
|---|---|
| models/sherpa-zh-int8/zh_encoder.int8.onnx | 5ac51e27981bb4dab01bb9be4958453ba50c3b61c063ddda0eab23fd3671aa4f |
| models/sherpa-zh-int8/zh_decoder.onnx | 06522ad63cec0fdf6809f4e1db9bb4f7d710c34582e3b35db62ac60eccafac7e |
| models/sherpa-zh-int8/zh_joiner.int8.onnx | （见 Git LFS 元数据 / see Git LFS metadata） |
| models/sherpa-zh-int8/zh_tokens.txt | （见 Git LFS 元数据 / see Git LFS metadata） |
| models/punct/model.int8.onnx | （见 Git LFS 元数据 / see Git LFS metadata） |
| models/punct/tokens.json | （见 Git LFS 元数据 / see Git LFS metadata） |

## 快速开始 / Quick Start

1. 从 [Releases](https://github.com/drliuhuan/koetype/releases) 下载最新 APK 并安装 / Download the latest APK from [Releases](https://github.com/drliuhuan/koetype/releases) and install
2. 系统设置 → 输入法 → 启用 **KoeType** 并设为默认（或与其他输入法共存，随时切换） / Settings → Language & input → enable **KoeType** and set as default (or coexist with other IMEs)
3. 打开 KoeType 应用 → 设置页 → 下载模型（GitHub 直连；国内网络可在设置中切换 ModelScope / HF 镜像源） / Open the KoeType app → Settings → download models (GitHub direct; switch to ModelScope / HF mirrors in settings for CN networks)
4. 在任意输入框长按 KoeType 键盘的麦克风键开始语音输入 / Press and hold the mic key on the KoeType keyboard to start voice input

## 架构 / Architecture

```
┌─ IME 进程（键盘 UI / 录音 / 文本提交）──────┐
│  每次收起键盘自动重建，绑定零残留             │
└──────────────┬──────────────────────────────┘
               │ AIDL（音频流 + 识别回调）
┌──────────────▼──────────────────────────────┐
│ 模型进程 :stt（识别 / 标点 / 本地LLM 常驻）   │
│  前台服务保活（可设置关闭）                   │
└──────────────────────────────────────────────┘
```

- 识别模型独立进程常驻：键盘弹出即用，切换输入法 / 锁屏后依然秒开 / Model process keeps running: instant readiness after keyboard show, IME switch, or screen lock
- 设置页独立于 IME 进程：随意使用语音输入，互不影响 / Settings isolated from IME process: voice input freely usable, no interference

## 下载链接（应用内默认）/ Download URLs (in-app default)

模型下载默认走本仓库 raw 直链（GitHub），国内用户可在应用内切换 ModelScope 源。
Model downloads default to this repository's raw links (GitHub); CN users can switch to ModelScope in-app.

- sherpa 中文识别模型：`https://raw.githubusercontent.com/drliuhuan/koetype/main/models/sherpa-zh-int8/...`
- 标点模型：`https://raw.githubusercontent.com/drliuhuan/koetype/main/models/punct/...`

## 本地 LLM 模型（可选）/ Local LLM Models (optional)

Qwen2.5-1.5B/0.5B Instruct GGUF 由 ModelScope 官方仓库提供，许可 Apache-2.0 / Provided by the official ModelScope repos, Apache-2.0:

- https://modelscope.cn/models/Qwen/Qwen2.5-1.5B-Instruct-GGUF
- https://modelscope.cn/models/Qwen/Qwen2.5-0.5B-Instruct-GGUF

## 致谢 / Acknowledgements

KoeType 建立在众多优秀开源项目之上，向每一位原作者致以诚挚感谢。
KoeType stands on the shoulders of many great open-source projects. Sincere thanks to every original author:

- **sherpa-onnx**（[k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)，Apache-2.0）— 端侧流式语音识别框架 / on-device streaming ASR framework
- **中文流式识别模型**（[csukuangfj/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30)，Apache-2.0）
- **标点恢复模型**（[ranger810/sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8](https://huggingface.co/ranger810/sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8)，Apache-2.0）
- **llama.cpp**（[ggerganov/llama.cpp](https://github.com/ggerganov/llama.cpp)，MIT）— 设备端 LLM 推理引擎 / on-device LLM inference engine
- **Qwen2.5 Instruct GGUF**（[ModelScope](https://modelscope.cn/models/Qwen/Qwen2.5-1.5B-Instruct-GGUF)，Apache-2.0）— 阿里 Qwen 团队 / Alibaba Qwen team
- **Sayboard**（[ElishaAz/Sayboard](https://github.com/ElishaAz/Sayboard)，GPL-3.0，作者 Elisha Azaria）— KoeType 的产品启发来源之一 / one of KoeType's product inspirations

完整致谢见 [THANKS](THANKS) / Full acknowledgements in [THANKS](THANKS).

## 捐赠与赞助 / Donations

**Buy me some tokens. ⚡**

KoeType 完全免费开源。如果你觉得它有用，欢迎扫码支持作者继续开发——每一份心意都会变成更多的 token，变成更好的功能。
KoeType is completely free and open source. If you find it useful, feel free to scan and support the author — every token counts, and it all goes back into making this project better.

| 微信支付 / WeChat Pay | 支付宝 / Alipay |
|---|---|
| ![WeChat Pay](assets/donate/wechat.png) | ![Alipay](assets/donate/alipay.jpg) |

> **重要声明 / Important Notice**：捐赠是对开发的支持，**不代表商业授权**。任何商业使用仍须通过 [GitHub Issues](https://github.com/drliuhuan/koetype/issues) 联系作者签署书面授权协议。
> Donations are a gesture of support and **do NOT constitute a commercial license**. Any commercial use still requires a written license agreement from the author via [GitHub Issues](https://github.com/drliuhuan/koetype/issues).

## 贡献者 / Contributors

- **Claude Code** — 代码实现 / code implementation
- **DeepSeek** — 推理模型（驱动 Claude Code 与 Hermes 的底层 LLM）/ LLM inference model powering Claude Code and Hermes
- **DeepSeek Harness** — 代码实现 / code implementation
- **Hermes** — 架构方案、日志分析、编译打包与交付 / architecture, log analysis, builds & delivery
- **drliuhuan** — 产品设计、需求定义、测试验证 / product design, requirements, testing

## 许可 / License

代码与仓库内容遵循 **PolyForm Noncommercial License 1.0.0**（见 [LICENSE](LICENSE)）：
Code and repository content are governed by the **PolyForm Noncommercial License 1.0.0** (see [LICENSE](LICENSE)):

- **非商业用途完全免费**：个人 / 教育 / 慈善 / 公共机构可自由使用、复制、修改与分发 / **Non-commercial use is completely free**: personal, educational, charitable and public institutions may freely use, copy, modify and redistribute
- **商业使用须获得作者书面许可** / **Commercial use requires the author's prior written permission**
- 中文摘要见 LICENSE 头部 / Chinese summary at the top of LICENSE

**模型文件版权归各自上游作者所有**，遵循其上游许可（Apache-2.0 / MIT，详见模型表格与 THANKS），使用与再分发须保留上游版权声明。
**Model files belong to their respective upstream authors** and follow their upstream licenses (Apache-2.0 / MIT, see model table and THANKS). Upstream copyright notices must be retained.
