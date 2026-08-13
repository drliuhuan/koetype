package com.drliuhuan.sayboardpro.stt

/**
 * STT（语音转文字）provider 抽象接口。
 *
 * 设计参考 OpenTypeless 的 src-tauri/src/stt/mod.rs：
 * - connect/send_audio/recv_transcript/disconnect 在此映射为 prepare/acceptWaveform/finish。
 * - 文件型 provider（Whisper 兼容 API）缓冲音频，finish 时一次性上传返回最终文本；
 *   流式 provider（Sherpa）在 acceptWaveform 期间持续产出 partial/final 段。
 *
 * 每个 provider 自己决定是否需要异步线程，事件必须通过 [Listener] 在主线程回调。
 */
interface SttProvider {

    /** 展示名（设置页/键盘上显示） */
    val name: String

    /** 是否支持实时 partial 结果 */
    val supportsPartialResults: Boolean

    /**
     * 异步初始化（Sherpa 加载模型、Whisper 校验配置）。
     * 完成后必须回调 [onReady]，成功时 isReady=true。
     */
    fun prepare(onReady: (isReady: Boolean, errorMessage: String?) -> Unit)

    /** 喂入一帧 PCM 音频（16kHz 单声道 16bit），录音线程调用 */
    fun acceptWaveform(samples: ShortArray, length: Int)

    /** 录音结束，请求最终结果。provider 应通过 Listener.onFinal/onError 返回结果 */
    fun finish()

    /** 取消本次识别，丢弃未提交内容 */
    fun cancel()

    /**
     * 会话清理：停止当前识别、结束解码线程，但**保留常驻资源**（如 sherpa recognizer），
     * 使下一次 [prepare] 可以幂等复用，减少二次调用延迟。
     */
    fun release()

    /**
     * 彻底销毁：释放常驻资源（模型/线程池），进程或 IME 服务销毁时调用。
     * 默认与 [release] 相同；持有长驻资源（如 sherpa recognizer）的 provider 必须重写。
     */
    fun destroy() {
        release()
    }

    /** provider 事件监听 */
    interface Listener {
        /** 实时部分结果（上屏为 composing 文本） */
        fun onPartial(text: String)

        /** 最终结果（上屏为已提交文本） */
        fun onFinal(text: String)

        fun onError(message: String)
    }
}
