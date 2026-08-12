# KoeType

**KoeType** — 本地优先的安卓语音输入法（Android IME）。

语音识别、标点恢复、断句纠错全部在设备端完成（可选在线 API 增强）。隐私安全：语音数据不离开手机。

## 功能

- **流式语音识别**：sherpa-onnx 中文流式 Zipformer int8 模型（本地、离线）
- **热词偏置**：自定义词库自动注入识别器热词，专有名词识别更准
- **标点恢复**：本地标点模型自动断句加标点（不依赖 LLM）
- **LLM 纠错**：可选在线 API 或设备端本地模型（llama.cpp, ≤1.5B）对识别结果纠错断句
- **在线语音识别**（可选）：OpenAI Whisper 兼容 API（BYOK）
- **代理支持**：HTTP / SOCKS5 协议，模型下载 / 在线识别 / LLM 纠错三个独立开关

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
| models/sherpa-zh-int8/zh_joiner.int8.onnx | b34584dc6f561089e1d747fedebb3765f2caa72c927ef54d7ca55e5ae40a814b |
| models/sherpa-zh-int8/zh_tokens.txt | 6193c7ea1c96d0d9a1e9652789b40d13a8a913b434a5451e93158f5a09fd6652 |
| models/punct/punct_int8.onnx | 65a3fb9f5ad7bfb96bf69e0dc4481df97f6ee60513c1d94ce981ba6effd524b1 |
| models/punct/punct_tokens.json | c960ab87bccea4aa15cf49a59f71973c2c330b46668048cd8da253749ec71ee3 |

## 版权与许可

### 本仓库代码与再分发模型

- 仓库内容（模型文件的重新打包与托管）以 **Apache License 2.0** 授权，见 [LICENSE](./LICENSE)。
- 模型文件本身由各自上游作者以 Apache-2.0 发布；本仓库仅作镜像托管，**不改变上游许可**。再分发时请同时保留本文件的来源说明。

### 第三方组件致谢

| 组件 | 项目 | 许可 |
|---|---|---|
| sherpa-onnx | [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | Apache-2.0 |
| llama.cpp | [ggerganov/llama.cpp](https://github.com/ggerganov/llama.cpp) | MIT |
| Sayboard（界面与架构参考） | [ElishaAz/Sayboard](https://github.com/ElishaAz/Sayboard) | GPL-3.0（仅参考，未复制代码） |

> ⚠️ 注意：KoeType 本体为独立实现，未包含 Sayboard 代码；如后续引入任何 GPL 组件，需按 GPL-3.0 开源对应部分。

### Qwen 本地模型（设备端 LLM 纠错，可选下载）

Qwen2.5-1.5B/0.5B Instruct GGUF 由 ModelScope 官方仓库提供，许可 Apache-2.0：
- https://modelscope.cn/models/Qwen/Qwen2.5-1.5B-Instruct-GGUF
- https://modelscope.cn/models/Qwen/Qwen2.5-0.5B-Instruct-GGUF

## 下载链接（应用内默认）

模型下载默认走本仓库 raw 直链（GitHub），国内用户可在应用内切换 ModelScope 源：

- sherpa 中文识别模型：`https://raw.githubusercontent.com/drliuhuan/koetype/main/models/sherpa-zh-int8/...`
- 标点模型：`https://raw.githubusercontent.com/drliuhuan/koetype/main/models/punct/...`
