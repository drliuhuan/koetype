package com.drliuhuan.sayboardpro.stt

import android.os.Handler
import android.os.Looper
import com.drliuhuan.sayboardpro.AppPrefs
import com.drliuhuan.sayboardpro.CrashLogger
import com.drliuhuan.sayboardpro.net.ProxyHelper
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Whisper 兼容 API provider（在线）。
 *
 * 兼容 OpenAI /v1/audio/transcriptions 规范的服务，可用 BYOK 填任意端点：
 * - Groq:          https://api.groq.com/openai/v1/audio/transcriptions
 * - 硅基流动:       https://api.siliconflow.cn/v1/audio/transcriptions
 * - 智谱 GLM:       https://open.bigmodel.cn/api/paas/v4/audio/transcriptions
 * - 本地 faster-whisper / Ollama 等兼容端点
 *
 * 实现：录音期间缓冲 PCM，[finish] 时封装成 WAV，用 HttpURLConnection 以
 * multipart/form-data 上传，解析返回 JSON 的 "text" 字段。文件型 provider 无 partial。
 * 参考 OpenTypeless 的 whisper_compat.rs。
 *
 * baseUrl/apiKey/model/language 支持编辑：默认从 [prefs] 实时读取（设置页改动即生效）；
 * 也可通过 [configOverride] 显式指定（"测试连接"等临时配置场景），为 null 时读 prefs。
 * 日常识别由 [com.drliuhuan.sayboardpro.stt.SttEngine] 用默认构造（读 prefs），两者一致。
 */
class WhisperApiProvider(
    private val prefs: AppPrefs,
    private val listener: SttProvider.Listener,
    private val configOverride: WhisperConfig? = null
) : SttProvider {

    /** 临时覆盖的 Whisper 配置；为 null 时全部读取 [prefs] */
    data class WhisperConfig(
        val baseUrl: String = "",
        val apiKey: String = "",
        val model: String = "",
        val language: String = ""
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private val pcmBuffer = ByteArrayOutputStream()

    private val baseUrl: String get() = (configOverride?.baseUrl ?: prefs.whisperBaseUrl).trim()
    private val apiKey: String get() = (configOverride?.apiKey ?: prefs.whisperApiKey).trim()
    private val model: String get() = (configOverride?.model ?: prefs.whisperModel).trim()
    private val language: String get() = (configOverride?.language ?: prefs.whisperLanguage).trim()

    override val name: String = "Whisper API"
    override val supportsPartialResults: Boolean = false

    override fun prepare(onReady: (Boolean, String?) -> Unit) {
        if (baseUrl.isEmpty() || model.isEmpty()) {
            onReady(false, "请先在设置页填写 Whisper 接口地址和模型名称")
            return
        }
        onReady(true, null)
    }

    override fun acceptWaveform(samples: ShortArray, length: Int) {
        // 小端写入 16bit PCM
        for (i in 0 until length) {
            val v = samples[i].toInt()
            pcmBuffer.write(v and 0xFF)
            pcmBuffer.write((v shr 8) and 0xFF)
        }
    }

    override fun finish() {
        executor.execute {
            try {
                val pcm = pcmBuffer.toByteArray()
                pcmBuffer.reset()
                if (pcm.isEmpty()) {
                    mainHandler.post { listener.onFinal("") }
                    return@execute
                }
                val wav = WavEncoder.encode(pcm, com.drliuhuan.sayboardpro.Constants.SAMPLE_RATE)
                val text = upload(wav)
                mainHandler.post {
                    if (text.isNullOrBlank()) listener.onFinal("")
                    else listener.onFinal(text.trim())
                }
            } catch (e: Exception) {
                CrashLogger.e(TAG, "transcription failed", e)
                mainHandler.post { listener.onError("转写失败：${e.message ?: "未知错误"}") }
            }
        }
    }

    override fun cancel() {
        pcmBuffer.reset()
    }

    override fun release() {
        executor.shutdown()
        pcmBuffer.reset()
    }

    // ── HTTP 上传 ───────────────────────────────────────────────────

    /** 规范化为完整 transcription 端点 */
    private fun normalizedEndpoint(): String {
        val base = baseUrl.trim().trimEnd('/')
        return if (base.endsWith("/audio/transcriptions")) base
        else "$base/audio/transcriptions"
    }

    private fun upload(wav: ByteArray): String? {
        val endpoint = normalizedEndpoint()

        var attempt = 0
        while (true) {
            try {
                // 代理认证作用域覆盖请求全生命周期（打开 → 建连 → 上传 → 读响应 → 断开）；
                // "在线语音识别走代理"开关开启时走代理；SOCKS 带认证不受支持（忽略用户名密码）
                val result = ProxyHelper.withProxy(prefs, ProxyHelper.Usage.STT) { proxy ->
                    val conn = ProxyHelper.open(endpoint, proxy, prefs)
                    try {
                        conn.requestMethod = "POST"
                        conn.doOutput = true
                        conn.connectTimeout = 20000
                        conn.readTimeout = 60000
                        conn.setRequestProperty("Accept", "application/json")
                        if (apiKey.isNotEmpty()) {
                            conn.setRequestProperty("Authorization", "Bearer $apiKey")
                        }

                        val boundary = "----KoeType" + UUID.randomUUID().toString().replace("-", "")
                        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

                        val body = buildMultipartBody(boundary, model, language, wav)
                        conn.setFixedLengthStreamingMode(body.size)

                        BufferedOutputStream(conn.outputStream).use { out -> out.write(body) }

                        val code = conn.responseCode

                        val responseStream =
                            if (code in 200..299) conn.inputStream else conn.errorStream
                        val responseBody = responseStream?.let { readAll(it) } ?: ""

                        if (code in 200..299) {
                            val text = try {
                                JSONObject(responseBody).optString("text", "")
                            } catch (e: Exception) {
                                responseBody // 某些实现直接返回纯文本
                            }
                            text.ifBlank { null }
                        } else {
                            throw IOException("HTTP $code: ${responseBody.take(200)}")
                        }
                    } finally {
                        conn.disconnect()
                    }
                }
                // 成功拿到结果（可能为 null=空文本）
                return result
            } catch (e: Exception) {
                // 5xx 与 IOException 都走统一退避重试（原 5xx 内联重试合并到此处）
                val isRetryable = e is IOException
                if (isRetryable && attempt < MAX_RETRIES) {
                    attempt++
                    Thread.sleep(1000L * (1L shl (attempt - 1)))
                    continue
                }
                throw e
            }
        }
    }

    private fun buildMultipartBody(
        boundary: String,
        model: String,
        language: String,
        wav: ByteArray
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val crlf = "\r\n".toByteArray(Charsets.UTF_8)

        fun textField(name: String, value: String) {
            out.write("--$boundary$crlf".toByteArray(Charsets.UTF_8))
            out.write("Content-Disposition: form-data; name=\"$name\"$crlf$crlf".toByteArray(Charsets.UTF_8))
            out.write(value.toByteArray(Charsets.UTF_8))
            out.write(crlf)
        }

        fun fileField() {
            out.write("--$boundary$crlf".toByteArray(Charsets.UTF_8))
            out.write(
                "Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"$crlf".toByteArray(Charsets.UTF_8)
            )
            out.write("Content-Type: audio/wav$crlf$crlf".toByteArray(Charsets.UTF_8))
            out.write(wav)
            out.write(crlf)
        }

        textField("model", model)
        if (language.isNotEmpty()) textField("language", language)
        fileField()
        out.write("--$boundary--$crlf".toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    private fun readAll(input: java.io.InputStream): String {
        val bis = BufferedInputStream(input)
        val sb = StringBuilder()
        val buf = ByteArray(4096)
        while (true) {
            val n = bis.read(buf)
            if (n < 0) break
            sb.append(String(buf, 0, n, Charsets.UTF_8))
        }
        return sb.toString()
    }

    companion object {
        private const val TAG = "WhisperApiProvider"
        private const val MAX_RETRIES = 2
    }
}
