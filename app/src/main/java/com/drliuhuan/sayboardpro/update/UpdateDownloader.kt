package com.drliuhuan.sayboardpro.update

import com.drliuhuan.sayboardpro.BuildConfig
import com.drliuhuan.sayboardpro.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/**
 * APK 下载：流式下载到目标文件，汇报进度（0..1），失败清理残留。
 * 网络 / IO 在 Dispatchers.IO 执行，调用方保证 UI 更新回主线程。
 */
object UpdateDownloader {

    private const val TAG = "UpdateDownloader"

    /** 连接超时（ms） */
    private const val CONNECT_TIMEOUT_MS = 15_000

    /** 读取超时（ms）：APK 体积大，留足传输时间 */
    private const val READ_TIMEOUT_MS = 30_000

    /**
     * 流式下载 [url] 到 [target]，通过 [onProgress] 汇报进度（0f..1f）。
     * @return true=成功；false=失败（HTTP 错误 / 截断 / 空文件，已删除残留）
     * @param proxy 可选代理（null=直连）；与模型下载共用代理设置
     */
    suspend fun download(
        url: String,
        target: File,
        proxy: Proxy? = null,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val connection = if (proxy != null) URL(url).openConnection(proxy)
            else URL(url).openConnection()
            conn = connection as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "KoeType/${BuildConfig.VERSION_NAME}")

            val code = connection.responseCode
            if (code !in 200..299) {
                CrashLogger.w(TAG, "HTTP $code for $url")
                target.delete()
                return@withContext false
            }

            val expected = connection.contentLengthLong
            var downloaded = 0L
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { out ->
                BufferedInputStream(connection.inputStream).use { input ->
                    val buffer = ByteArray(16 * 1024)
                    var n: Int
                    while (input.read(buffer).also { n = it } >= 0) {
                        out.write(buffer, 0, n)
                        downloaded += n
                        if (expected > 0) {
                            onProgress((downloaded.toFloat() / expected).coerceIn(0f, 1f))
                        }
                    }
                }
            }

            // 完整性校验：Content-Length 存在但实收不一致 → 截断 / 损坏；空文件一律失败
            if (expected > 0 && downloaded != expected) {
                CrashLogger.w(TAG, "Truncated download for $url: got $downloaded, expected $expected")
                target.delete()
                return@withContext false
            }
            if (downloaded <= 0) {
                CrashLogger.w(TAG, "Empty download for $url")
                target.delete()
                return@withContext false
            }

            onProgress(1f)
            true
        } catch (e: Exception) {
            CrashLogger.e(TAG, "download failed: $url", e)
            target.delete()
            false
        } finally {
            conn?.disconnect()
        }
    }
}
