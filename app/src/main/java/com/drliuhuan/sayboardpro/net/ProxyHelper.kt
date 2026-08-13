package com.drliuhuan.sayboardpro.net

import android.util.Base64
import com.drliuhuan.sayboardpro.AppPrefs
import com.drliuhuan.sayboardpro.CrashLogger
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * 共享代理工具：读取 [AppPrefs] 的代理设置，构造 [Proxy]（null=直连），
 * 并统一处理代理认证。
 *
 * 认证方案（简单可靠）：
 * - HTTP 代理：在 [open] 里直接加 `Proxy-Authorization: Basic base64(user:pass)` 头
 *   （HTTP 代理一般认这个头，无全局状态）；
 * - SOCKS5/SOCKS5H：Android 的 java.net SOCKS 实现不支持 Authenticator 认证
 *   （Android 没有 Authenticator.setDefault），因此 SOCKS 配置了用户名/密码时不认证，
 *   仅记录日志提示"Android 不支持 SOCKS 认证，请使用无认证代理"；多数无认证的
 *   SOCKS5 代理不认证也能直连。
 *
 * 三个用途独立开关：[proxyForDownload]（模型下载）、[proxyForStt]（Whisper API）、
 * [proxyForLlm]（LLM 纠错）。认证不依赖任何全局状态，[withProxy] 零开销。
 */
object ProxyHelper {
    private const val TAG = "ProxyHelper"

    /** 代理用途：决定读哪个独立开关 */
    enum class Usage { DOWNLOAD, STT, LLM }

    /** 模型下载走代理的 [Proxy]；未启用/未开下载开关/配置无效时返回 null（直连） */
    fun proxyForDownload(prefs: AppPrefs): Proxy? = buildProxy(prefs, prefs.proxyForDownload)

    /** 在线语音识别（Whisper API）走代理的 [Proxy]；直连时返回 null */
    fun proxyForStt(prefs: AppPrefs): Proxy? = buildProxy(prefs, prefs.proxyForStt)

    /** LLM 纠错（在线 API）走代理的 [Proxy]；直连时返回 null */
    fun proxyForLlm(prefs: AppPrefs): Proxy? = buildProxy(prefs, prefs.proxyForLlm)

    @PublishedApi
    internal fun forUsage(prefs: AppPrefs, usage: Usage): Proxy? = when (usage) {
        Usage.DOWNLOAD -> proxyForDownload(prefs)
        Usage.STT -> proxyForStt(prefs)
        Usage.LLM -> proxyForLlm(prefs)
    }

    private fun buildProxy(prefs: AppPrefs, used: Boolean): Proxy? {
        // 总开关关闭或该用途独立开关关闭 → 直连
        if (!prefs.proxyEnabled || !used) return null
        val host = prefs.proxyHost.trim()
        val port = prefs.proxyPort
        if (host.isEmpty() || port !in 1..65535) {
            CrashLogger.w(TAG, "代理已启用但主机/端口无效: $host:$port，本次请求直连")
            return null
        }
        // socks5h 与 socks5 一样走 SOCKS；区别是域名由代理服务器解析（避免本地 DNS 污染），
        // 对 java.net 的 Proxy 类型无差别，都映射为 Proxy.Type.SOCKS
        val type = when (prefs.proxyProtocol) {
            AppPrefs.PROXY_PROTOCOL_SOCKS5, AppPrefs.PROXY_PROTOCOL_SOCKS5H -> Proxy.Type.SOCKS
            else -> Proxy.Type.HTTP
        }
        return Proxy(type, InetSocketAddress(host, port))
    }

    /**
     * 打开连接（可能走代理），返回 [HttpURLConnection]。
     * HTTP 代理有用户名/密码时加 `Proxy-Authorization` 头（HTTP 代理一般认这个头）；
     * SOCKS 协议下 Android 不支持 Authenticator 认证，用户名/密码被忽略并记录日志提示。
     */
    fun open(url: String, proxy: Proxy?, prefs: AppPrefs): HttpURLConnection {
        val conn = if (proxy != null) URL(url).openConnection(proxy)
        else URL(url).openConnection()
        val http = conn as HttpURLConnection
        val user = prefs.proxyUser.trim()
        val pass = prefs.proxyPass
        val hasCredential = user.isNotEmpty() || pass.isNotEmpty()
        when (proxy?.type()) {
            Proxy.Type.HTTP -> if (hasCredential) {
                val credential = Base64.encodeToString(
                    "$user:$pass".toByteArray(Charsets.UTF_8), Base64.NO_WRAP
                )
                http.setRequestProperty("Proxy-Authorization", "Basic $credential")
            }
            Proxy.Type.SOCKS -> if (hasCredential) {
                CrashLogger.w(TAG, "Android 不支持 SOCKS 认证，请使用无认证代理；已忽略代理用户名/密码")
            }
            else -> Unit // 直连
        }
        return http
    }

    /**
     * 在代理作用域内执行 [block]。
     *
     * - [block] 收到 [Proxy]？，可为 null（直连）；
     * - 认证只在 [open] 里处理（HTTP 加 `Proxy-Authorization` 头；SOCKS 忽略用户名/密码并提示），
     *   [withProxy] 不安装任何全局状态，零开销；
     * - 必须用 inline 才能让 suspend 调用点在块内直接调用挂起函数。
     */
    inline fun <R> withProxy(prefs: AppPrefs, usage: Usage, block: (Proxy?) -> R): R {
        val proxy = forUsage(prefs, usage)
        return block(proxy)
    }
}
