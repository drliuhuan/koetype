package com.drliuhuan.sayboardpro.llm

import android.content.Context
import android.util.Log
import com.drliuhuan.sayboardpro.AppPrefs
import com.drliuhuan.sayboardpro.Constants
import com.drliuhuan.sayboardpro.CrashLogger
import com.drliuhuan.sayboardpro.net.ProxyHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * A downloadable GGUF model preset offered in the device-local LLM settings.
 */
data class LlmModelPreset(
    /** Stable identifier, also used to build the file name on disk. */
    val name: String,
    /** Human readable name shown in the UI. */
    val displayName: String,
    /** Direct download link (ModelScope resolve/master). */
    val url: String,
    /** Approximate size, shown next to the preset. */
    val sizeLabel: String
)

/**
 * Built-in GGUF model catalog. These are small Chinese-capable instruct models
 * (Q4_K_M quantisation) that fit comfortably in on-device RAM. The download
 * source is ModelScope (directly reachable from mainland China; hf-mirror is
 * no longer maintained, so it is not offered as a default mirror).
 */
object LlmModelCatalog {
    val presets: List<LlmModelPreset> = listOf(
        LlmModelPreset(
            name = "qwen2.5-1.5b-instruct-q4_k_m",
            displayName = "Qwen2.5 1.5B Instruct (Q4_K_M)",
            url = "https://modelscope.cn/models/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/master/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            sizeLabel = "~1.0 GB"
        ),
        LlmModelPreset(
            name = "qwen2.5-0.5b-instruct-q4_k_m",
            displayName = "Qwen2.5 0.5B Instruct (Q4_K_M)",
            url = "https://modelscope.cn/models/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/master/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            sizeLabel = "~0.4 GB"
        )
    )

    /** File name on disk for a preset. */
    fun fileNameFor(presetName: String): String = "$presetName.gguf"
}

/**
 * Downloads GGUF models from ModelScope straight into the app's model directory,
 * using java.net.HttpURLConnection so no extra dependency is needed. Supports
 * resuming via the HTTP Range header and reports progress.
 */
object ModelDownloader {
    private const val TAG = "ModelDownloader"

    /** A GGUF file smaller than this is definitely corrupt/incomplete. */
    const val MIN_VALID_GGUF_BYTES: Long = 100L * 1024 * 1024 // 100 MB
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val BUFFER_SIZE = 256 * 1024
    private const val MAX_REDIRECTS = 5

    /** Directory that holds downloaded .gguf files: files/Models/llm/ */
    fun modelsDirectory(context: Context): File =
        File(Constants.getModelsDirectory(context), "llm").also { it.mkdirs() }

    /** 代理把错误页当 200/206 返回时 Content-Type 通常是 text/html：一律拒收 */
    private fun isHtmlContentType(contentType: String?): Boolean {
        if (contentType.isNullOrBlank()) return false
        val type = contentType.substringBefore(';').trim().lowercase()
        return type == "text/html" || type.endsWith("/html") || "html" in type
    }

    /** Target file on disk for a given file name. */
    fun targetFile(context: Context, fileName: String): File =
        File(modelsDirectory(context), fileName)

    /** A GGUF file is considered complete if it exists, is > 100 MB and starts with the GGUF magic. */
    fun isValidGguf(file: File): Boolean {
        if (!file.exists() || file.isDirectory || file.length() < MIN_VALID_GGUF_BYTES) return false
        val magic = file.inputStream().use { ins ->
            val header = ByteArray(4)
            val n = ins.read(header)
            n == 4 &&
                header[0] == 0x47.toByte() && // 'G'
                header[1] == 0x47.toByte() && // 'G'
                header[2] == 0x55.toByte() && // 'U'
                header[3] == 0x46.toByte()    // 'F'
        }
        return magic
    }

    /**
     * Downloads [url] into [dest], resuming an interrupted download if the
     * server supports byte ranges. [onProgress] is called with (bytes so far,
     * total bytes, in percent 0..1); [onProgress] total may be -1 when unknown.
     *
     * Returns the downloaded file on success. A corrupt/truncated file already on
     * disk is deleted first so a fresh download starts cleanly.
     *
     * 代理："模型下载走代理"开关开启时（[AppPrefs.proxyForDownload]），走配置的代理；
     * SOCKS 带认证不受支持（忽略用户名密码，由 [ProxyHelper.open] 记录日志提示）。
     */
    suspend fun download(
        prefs: AppPrefs,
        url: String,
        dest: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long, progress: Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            dest.parentFile?.mkdirs()
            if (dest.exists() && !isValidGguf(dest)) {
                // Truncated or corrupt leftover from a previous attempt.
                Log.w(TAG, "Deleting invalid partial file ${dest.name}")
                dest.delete()
            }

            // 代理认证作用域覆盖连接全生命周期（openConnection → 建连 → 传输 → 断开）
            ProxyHelper.withProxy(prefs, ProxyHelper.Usage.DOWNLOAD) { proxy ->
                val connection = openConnection(prefs, url, dest.length(), proxy)
                val responseCode = connection.responseCode

                // Server ignores the Range header and sends the whole file again.
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    if (dest.exists()) dest.delete()
                    Log.d(TAG, "Server does not support ranges, downloading from scratch")
                } else if (responseCode != HttpURLConnection.HTTP_PARTIAL) {
                    val message = "网络或代理异常导致下载失败：HTTP $responseCode，请重试"
                    Log.w(TAG, "Download failed: HTTP $responseCode for $url")
                    CrashLogger.w(TAG, "DL: Download failed: HTTP $responseCode for $url")
                    connection.disconnect()
                    dest.delete() // 删除残留半截文件
                    return@withContext Result.failure(IOException(message))
                }

                // 代理/镜像可能把 HTML 错误页当 200/206 返回：拒收并删除残留
                if (isHtmlContentType(connection.contentType)) {
                    val message = "网络或代理异常导致下载失败：服务器返回 HTML 错误页，请重试"
                    Log.w(TAG, "$message for $url (contentType=${connection.contentType})")
                    CrashLogger.w(TAG, "DL: $message for $url (contentType=${connection.contentType})")
                    connection.disconnect()
                    dest.delete()
                    return@withContext Result.failure(IOException(message))
                }

                val totalBytes = resolveTotalBytes(connection, dest.length())
                var written = dest.length()
                val input = connection.inputStream.buffered()
                RandomAccessFile(dest, "rw").use { raf ->
                    raf.seek(dest.length())
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive() // respect cancellation
                        val count = input.read(buffer)
                        if (count < 0) break
                        raf.write(buffer, 0, count)
                        written += count
                        val progress = if (totalBytes > 0) (written.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
                        onProgress(written, totalBytes, progress)
                    }
                }
                input.close()
                connection.disconnect()

                // Content-Range/Length 给出期望总量时，实收必须一致：截断即损坏（代理串流/中途断开）
                if (totalBytes > 0 && written != totalBytes) {
                    dest.delete()
                    Log.e(TAG, "Truncated download: got $written, expected $totalBytes bytes")
                    CrashLogger.w(TAG, "DL: Truncated download: got $written, expected $totalBytes bytes")
                    return@withContext Result.failure(
                        IllegalStateException("网络或代理异常导致下载失败：文件下载不完整（$written/$totalBytes），请重试")
                    )
                }

                if (!isValidGguf(dest)) {
                    dest.delete()
                    Log.e(TAG, "Downloaded file failed GGUF validation")
                    CrashLogger.w(TAG, "DL: Downloaded file failed GGUF validation")
                    return@withContext Result.failure(
                        IllegalStateException("网络或代理异常导致下载失败：下载的文件不是有效的 GGUF 模型，请重试")
                    )
                }
                Log.d(TAG, "Downloaded ${dest.name} (${dest.length()} bytes)")
                CrashLogger.d(TAG, "DL: downloaded ${dest.length()} bytes")
                Result.success(dest)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 取消保留部分文件，下次支持断点续传
        } catch (e: Exception) {
            Log.w(TAG, "Download failed", e)
            CrashLogger.w(TAG, "DL: Download failed: ${e.message}")
            dest.delete() // 删除残留半截文件
            Result.failure(e)
        }
    }

    /**
     * Builds a GET connection with a Range header when a partial file exists.
     * ModelScope resolve/master links redirect (301/302/307/308) to a CDN host;
     * the legacy HttpURLConnection on API < 26 does not follow 308, so redirects
     * are followed manually with the Range header preserved.
     *
     * 每个跳转 hop 都走同一个代理（[proxy]，可空=直连）；HTTP 代理认证头由
     * [ProxyHelper.open] 注入。
     */
    @Throws(IOException::class)
    private fun openConnection(
        prefs: AppPrefs,
        url: String,
        existingBytes: Long,
        proxy: Proxy?
    ): HttpURLConnection {
        var currentUrl = url
        var redirects = 0
        while (true) {
            val connection = ProxyHelper.open(currentUrl, proxy, prefs)
            connection.apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "application/octet-stream")
                if (existingBytes > 0) {
                    setRequestProperty("Range", "bytes=$existingBytes-")
                }
                // Browser-ish UA: some CDNs (incl. ModelScope) reject bare app UAs.
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) KoeType/1.0")
            }
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_MOVED_PERM || // 301
                code == HttpURLConnection.HTTP_MOVED_TEMP || // 302
                code == HttpURLConnection.HTTP_SEE_OTHER ||  // 303
                code == 307 || code == 308
            ) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank() || redirects >= MAX_REDIRECTS) {
                    throw IOException("Too many redirects for $url")
                }
                currentUrl = URL(URL(currentUrl), location).toString()
                redirects++
            } else {
                return connection
            }
        }
    }

    /** Total expected size: Content-Range for 206, Content-Length for 200. */
    private fun resolveTotalBytes(connection: HttpURLConnection, existingBytes: Long): Long {
        val contentRange = connection.getHeaderField("Content-Range") // "bytes 123-456/789"
        val totalFromRange = contentRange?.substringAfterLast('/')?.trim()?.toLongOrNull()
        if (totalFromRange != null && totalFromRange > 0) return totalFromRange
        val length = connection.contentLengthLong
        return if (length > 0) existingBytes + length else -1
    }
}
