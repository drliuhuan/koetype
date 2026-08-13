# KoeType

**KoeType** — 本地优先的安卓语音输入法（Android IME）。

语音识别、标点恢复、断句纠错全程本地处理，可选在线 API 增强。开箱即用，模型一键下载。

## 功能

- **流式语音识别**：sherpa-onnx 中文流式 Zipformer int8 模型，本地离线识别，边说边出字
- **热词偏置**：自定义词库自动注入识别器热词，专有名词、药品名、术语识别更准
- **标点恢复**：本地标点模型自动断句加标点，不依赖网络
- **LLM 纠错**：可选在线 API 或设备端本地模型（llama.cpp，≤1.5B）对识别结果智能纠错断句
- **在线语音识别**（可选）：OpenAI Whisper 兼容 API（BYOK）
- **长按/点按双模式**：长按说话松开上屏，点按切换开关；连续说话自动排队，不吞句
- **模型常驻**：识别模型独立进程常驻（前台服务保活，可设置关闭），键盘弹出即用、零等待
- **三进程架构**：IME / 模型 / 设置页进程隔离，切换输入法零残留、不闪退
- **代理支持**：HTTP / SOCKS5 协议，模型下载 / 在线识别 / LLM 纠错三个独立开关
- **模型加载状态可见**：键盘顶部实时显示模型加载进度，失败可一键重试

## 模型文件

本仓库托管 KoeType 使用的模型文件（Git LFS）。全部模型均为 Apache-2.0 许可，可自由使用与再分发，但需保留上游版权声明（见下）。

| 模型 | 用途 | 大小 | 上游来源 |
|---|---|---|---|
| `models/sherpa-zh-int8/` | 中文流式识别（encoder/decoder/joiner/tokens） | ~167 MB | [csukuangfj/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30) |
| `models/punct/` | 标点恢复（model.int8.onnx + tokens.json） | ~80 MB | [ranger810/sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8](https://huggingface.co/ranger810/sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8) |

### SHA-256 校验

| 文件 | SHA-256 |
|---|---|
| models/sherpa-zh-int8/zh_encoder.int8.onnx | 5ac51e27981bb4dab01bb9be4958453ba50c3b61c063ddda0eab23fd3671aa4f |
| models/sherpa-zh-int8/zh_decoder.onnx | 06522ad63cec0fdf6809f4e1db9bb4f7d710c34582e3b35db62ac60eccafac7e |
| models/sherpa-zh-int8/zh_joiner.int8.onnx | （见 Git LFS 元数据） |
| models/sherpa-zh-int8/zh_tokens.txt | （见 Git LFS 元数据） |
| models/punct/model.int8.onnx | （见 Git LFS 元数据） |
| models/punct/tokens.json | （见 Git LFS 元数据） |

## 快速开始

1. 从 [Releases](https://github.com/drliuhuan/koetype/releases) 下载最新 APK 并安装
2. 系统设置 → 输入法 → 启用 **KoeType** 并设为默认（或与其他输入法共存，随时切换）
3. 打开 KoeType 应用 → 设置页 → 下载模型（GitHub 直连；国内网络可在设置中切换 ModelScope / HF 镜像源）
4. 在任意输入框长按 KoeType 键盘的麦克风键开始语音输入

## 架构

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

- 识别模型独立进程常驻：键盘弹出即用，切换输入法 / 锁屏后依然秒开
- 设置页独立于 IME 进程：随意使用语音输入，互不影响

## 下载链接（应用内默认）

模型下载默认走本仓库 raw 直链（GitHub），国内用户可在应用内切换 ModelScope 源：

- sherpa 中文识别模型：`https://raw.githubusercontent.com/drliuhuan/koetype/main/models/sherpa-zh-int8/...`
- 标点模型：`https://raw.githubusercontent.com/drliuhuan/koetype/main/models/punct/...`

## 本地 LLM 模型（可选）

Qwen2.5-1.5B/0.5B Instruct GGUF 由 ModelScope 官方仓库提供，许可 Apache-2.0：

- https://modelscope.cn/models/Qwen/Qwen2.5-1.5B-Instruct-GGUF
- https://modelscope.cn/models/Qwen/Qwen2.5-0.5B-Instruct-GGUF

## 贡献者

- **drliuhuan** — 产品设计、需求定义、测试验证
- **Hermes** — 架构方案、日志分析、编译打包与交付
- **Claude Code** — 代码实现

## 致谢

KoeType 建立在众多优秀开源项目之上，向每一位原作者致以诚挚感谢：

- **sherpa-onnx**（[k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)，Apache-2.0）— 端侧流式语音识别框架
- **中文流式识别模型**（[csukuangfj/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30)，Apache-2.0）
- **标点恢复模型**（[ranger810/sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8](https://huggingface.co/ranger810/sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8)，Apache-2.0）
- **llama.cpp**（[ggerganov/llama.cpp](https://github.com/ggerganov/llama.cpp)，MIT）— 设备端 LLM 推理引擎
- **Qwen2.5 Instruct GGUF**（[ModelScope](https://modelscope.cn/models/Qwen/Qwen2.5-1.5B-Instruct-GGUF)，Apache-2.0）— 阿里 Qwen 团队
- **SayboardPro / Sayboard** — KoeType 的移植基础

完整致谢见 [THANKS](THANKS)。

## 许可

代码与仓库内容遵循 [LICENSE](LICENSE)（KoeType License）：

- 完全开源，**免费使用、复制、修改与分发**
- **商业使用须获得作者书面许可**

**模型文件版权归各自上游作者所有**，遵循其上游许可（Apache-2.0 / MIT，详见模型表格与 THANKS），使用与再分发须保留上游版权声明。
