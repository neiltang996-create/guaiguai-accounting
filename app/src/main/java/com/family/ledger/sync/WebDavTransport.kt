package com.family.ledger.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * 最小 WebDAV 客户端：只用到 PUT / GET / MKCOL / PROPFIND。
 *
 * 选 WebDAV 而不是自建服务端，是因为买个坚果云就能用；两台手机各写一个文件，不需要并发控制。
 * - PUT    上传自己的设备日志
 * - GET    下载对方的设备日志（404 = 对方还没同步过）
 * - MKCOL  建目录（405 = 已存在，视为成功）
 * - PROPFIND 列目录（用来发现对方的设备文件；服务器禁用时返回空表，不影响主流程）
 *
 * 所有失败都抛 [SyncException]，message 是中文，可直接展示给用户。
 */
class WebDavTransport(
    baseUrl: String,
    private val user: String,
    private val password: String,
    private val client: OkHttpClient = defaultClient(),
    private val maxAttempts: Int = 3,
) {

    private val base: String = baseUrl.trim().trimEnd('/')
    private val auth: String? =
        if (user.isBlank() && password.isBlank()) null else Credentials.basic(user, password)

    /** PUT 文本；成功返回。 */
    suspend fun putText(url: String, text: String) {
        val body = text.toRequestBody(JSON_LINES)
        val r = send(request(url).put(body).build())
        if (!r.ok) throw SyncException(SyncErrors.http(r.code, url))
    }

    /** GET 文本；404（不存在）返回 null。 */
    suspend fun getText(url: String): String? {
        val r = send(request(url).get().build())
        if (r.code == 404) return null
        if (!r.ok) throw SyncException(SyncErrors.http(r.code, url))
        return r.body
    }

    /** MKCOL 建单层目录；405（已存在）视为成功。 */
    suspend fun mkcol(url: String) {
        val r = send(request(url).method("MKCOL", null).build())
        if (r.ok || r.code == 405 || r.code == 301 || r.code == 302) return
        throw SyncException(SyncErrors.http(r.code, url))
    }

    /** 由浅到深依次建目录（父目录缺失会 409，所以顺序不能反）。 */
    suspend fun ensureCollections(urls: List<String>) {
        for (u in urls) {
            if (u.isBlank()) continue
            mkcol(u)
        }
    }

    /** PROPFIND Depth:1 列出目录下的文件名（只取 basename）。任何失败都返回空表。 */
    suspend fun listFileNames(dirUrl: String): List<String> {
        return try {
            val url = if (dirUrl.endsWith("/")) dirUrl else "$dirUrl/"
            val r = send(request(url).method("PROPFIND", null).header("Depth", "1").build())
            if (!r.ok) emptyList() else parseHrefs(r.body)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 组装请求；地址不合法时给出中文提示（而不是抛 IllegalArgumentException）。 */
    private fun request(url: String): Request.Builder {
        val b = try {
            Request.Builder().url(url)
        } catch (e: IllegalArgumentException) {
            throw SyncException("同步地址格式不正确：请填写 http(s):// 开头的 WebDAV 目录地址", e)
        }
        auth?.let { b.header("Authorization", it) }
        return b
    }

    private class HttpResult(val code: Int, val body: String) {
        val ok: Boolean get() = code in 200..299
    }

    /**
     * 带重试的请求。
     * 网络类异常（超时/断连）与 5xx（服务器抽风）都会重试，其余 HTTP 状态码原样返回给上层判断。
     * PUT 是幂等的，重试安全。
     */
    private suspend fun send(req: Request): HttpResult = withContext(Dispatchers.IO) {
        var lastError: IOException? = null
        var lastServerError: HttpResult? = null
        var attempt = 1
        while (attempt <= maxAttempts) {
            try {
                val result = client.newCall(req).execute().use { resp ->
                    HttpResult(resp.code, resp.body?.string().orEmpty())
                }
                if (result.code in 500..599 && attempt < maxAttempts) {
                    lastServerError = result
                } else {
                    return@withContext result
                }
            } catch (e: SSLException) {
                throw SyncException(SyncErrors.translate(e), e)
            } catch (e: IllegalArgumentException) {
                throw SyncException("同步地址格式不正确：请填写 http(s):// 开头的 WebDAV 目录地址", e)
            } catch (e: IOException) {
                lastError = e
            }
            attempt++
            if (attempt <= maxAttempts) delay(RETRY_BASE_DELAY_MS * attempt)
        }
        lastServerError?.let { return@withContext it }
        throw SyncException(SyncErrors.translate(lastError), lastError)
    }

    companion object {
        private val JSON_LINES = "application/x-ndjson; charset=utf-8".toMediaType()
        private const val RETRY_BASE_DELAY_MS = 400L
        private const val TIMEOUT_SECONDS = 30L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        private val HREF = Regex("""<[^>]*?href[^>]*?>(.*?)</[^>]*?href>""", RegexOption.IGNORE_CASE)
        private val XML_ENTITY = Regex("""&(amp|lt|gt|quot|apos|#\d+);""")

        /**
         * 从 PROPFIND 的 XML 里抠出**文件名**。
         *
         * PROPFIND Depth:1 会把自己这个 collection（href 以 `/` 结尾）也返回，
         * 那不是文件，必须过滤掉 —— 否则 `listFileNames` 会多出一个目录项。
         */
        fun parseHrefs(xml: String): List<String> =
            HREF.findAll(xml)
                .map { it.groupValues[1].trim() }
                .map { decodeXml(it).substringBefore('?') }
                .filterNot { it.trimEnd().endsWith("/") }
                .mapNotNull { href ->
                    val name = href.trimEnd('/').substringAfterLast('/')
                    if (name.isBlank()) null else runCatching { URLDecoder.decode(name, Charsets.UTF_8.name()) }.getOrDefault(name)
                }
                .distinct()
                .toList()

        private fun decodeXml(s: String): String = XML_ENTITY.replace(s) { m ->
            when (val e = m.groupValues[1].lowercase()) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> e.removePrefix("#").toIntOrNull()?.let { n -> n.toChar().toString() } ?: m.value
            }
        }
    }
}
