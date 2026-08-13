package com.drliuhuan.sayboardpro.update

import android.os.Build
import com.drliuhuan.sayboardpro.BuildConfig
import com.drliuhuan.sayboardpro.CrashLogger
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/**
 * GitHub 发布版本检查：GET releases/latest 拉取最新 Release，解析版本号 / 说明 / APK 资产，
 * 与当前安装版本（BuildConfig.VERSION_NAME）做数字分段比较，并匹配本机 ABI 的下载地址。
 *
 * 全部为阻塞网络 / IO 操作，调用方必须在后台线程（Dispatchers.IO）执行。
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"

    /** GitHub 最新 Release API（KoeType 仓库） */
    private const val GITHUB_LATEST_RELEASE_URL =
        "https://api.github.com/repos/drliuhuan/koetype/releases/latest"

    /** 连接 / 读取超时（ms） */
    private const val NETWORK_TIMEOUT_MS = 10_000

    /** 检查结果：latestVersion 去 v 前缀；releaseName 为发行版标题；errorMessage 非空表示检查失败 */
    data class UpdateInfo(
        val latestVersion: String,
        val hasUpdate: Boolean,
        val downloadUrl: String?,
        val releaseNotes: String,
        val releaseName: String = "",
        val errorMessage: String? = null
    )

    /** 资产条目：文件名 + 下载直链 */
    private data class AssetInfo(val fileName: String, val downloadUrl: String)

    /**
     * 检查是否有新版本。
     * @param proxy 可选代理（null=直连）；与模型下载共用 [com.drliuhuan.sayboardpro.net.ProxyHelper.proxyForDownload] 的代理设置
     */
    fun checkLatest(proxy: Proxy? = null): UpdateInfo {
        var conn: HttpURLConnection? = null
        try {
            val connection = if (proxy != null) URL(GITHUB_LATEST_RELEASE_URL).openConnection(proxy)
            else URL(GITHUB_LATEST_RELEASE_URL).openConnection()
            conn = connection as HttpURLConnection
            connection.connectTimeout = NETWORK_TIMEOUT_MS
            connection.readTimeout = NETWORK_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "KoeType/${BuildConfig.VERSION_NAME}")

            val code = connection.responseCode
            if (code !in 200..299) {
                return UpdateInfo(
                    latestVersion = "",
                    hasUpdate = false,
                    downloadUrl = null,
                    releaseNotes = "",
                    errorMessage = "GitHub 接口返回 HTTP $code"
                )
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).readText()
            return parseRelease(body)
        } catch (e: Exception) {
            CrashLogger.w(TAG, "checkLatest failed: ${e.message}")
            return UpdateInfo(
                latestVersion = "",
                hasUpdate = false,
                downloadUrl = null,
                releaseNotes = "",
                errorMessage = e.message ?: "网络错误"
            )
        } finally {
            conn?.disconnect()
        }
    }

    /** 解析 releases/latest 响应体 */
    private fun parseRelease(body: String): UpdateInfo {
        val json = JSONObject(body)
        val tagName = json.optString("tag_name", "").removePrefix("v")
        val notes = json.optString("body", "")
        val assetsJson = json.optJSONArray("assets") ?: JSONArray()
        val assets = ArrayList<AssetInfo>()
        for (i in 0 until assetsJson.length()) {
            val asset = assetsJson.optJSONObject(i) ?: continue
            assets.add(
                AssetInfo(
                    fileName = asset.optString("name", ""),
                    downloadUrl = asset.optString("browser_download_url", "")
                )
            )
        }
        return UpdateInfo(
            latestVersion = tagName,
            hasUpdate = compareVersions(tagName, BuildConfig.VERSION_NAME) > 0,
            downloadUrl = findAbiAsset(assets),
            releaseNotes = notes,
            releaseName = json.optString("name", tagName)
        )
    }

    /**
     * 按本机 ABI 找 APK 资产：Build.SUPPORTED_ABIS 已按优先级排序（本机首选 ABI 在前，
     * 如 arm64 设备首项 "arm64-v8a"），逐个 ABI 找文件名含该 ABI 字符串的资产。
     * 找不到匹配 ABI 返回 null（由 UI 提示用户手动下载）。
     */
    private fun findAbiAsset(assets: List<AssetInfo>): String? {
        for (abi in Build.SUPPORTED_ABIS) {
            val asset = assets.firstOrNull { it.fileName.contains(abi) }
            if (asset != null && asset.downloadUrl.isNotBlank()) return asset.downloadUrl
        }
        return null
    }

    /**
     * 简单数字分段版本比较：去 v 前缀后按 '.' 分段逐段比较，不足段补 0。
     * 返回 >0 表示 a 比 b 新。
     * "0.11" vs "0.1.3"：分段 [0,11] vs [0,1,3] → 第 2 段 11>1 → 判新（正确）。
     */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.removePrefix("v").split('.')
        val pb = b.removePrefix("v").split('.')
        val max = maxOf(pa.size, pb.size)
        for (i in 0 until max) {
            val na = pa.getOrNull(i)?.toIntOrNull() ?: 0
            val nb = pb.getOrNull(i)?.toIntOrNull() ?: 0
            if (na != nb) return na.compareTo(nb)
        }
        return 0
    }
}
