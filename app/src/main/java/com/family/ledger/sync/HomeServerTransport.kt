package com.family.ledger.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 「家庭同步服务器」HTTP 客户端（OkHttp 已在依赖里，不新增依赖）。
 *
 * 为什么是这个方案：用户明确反对「自己填 WebDAV 服务器 / 账号 / 密码」。
 * 家里的 NAS 常驻在线，两台手机都连它 —— 在家走局域网地址、在外面走公网地址，
 * 客户端只有这一条代码路径，用户什么都不用填。
 *
 * 职责边界：只做 HTTP 搬运。合并由 [SyncEngine]（LWW）负责，
 * 应用远端 op 只走 `sync_op` 表 upsert，**绝不经过 SyncJournal**（否则两端无限回环）。
 *
 * 失败处理：所有失败都抛 [SyncException]，message 是可直接展示的中文；
 * [ping] 是唯一不抛异常的探活入口（用于逐个候选地址试连）。不崩、不 ANR（全部在 IO 线程）。
 */
class HomeServerTransport(
    baseUrl: String,
    familyId: String,
    token: String = "",
    /** 传数据用的客户端（读超时宽松些，首次全量上传可能几百 KB）。 */
    private val client: OkHttpClient = sharedDataClient(),
    private val maxAttempts: Int = 2,
    private val retryDelayMs: Long = RETRY_BASE_DELAY_MS,
    /** 探活用的客户端：候选地址最多试 3~4 秒，不能让用户干等。 */
    private val probeClient: OkHttpClient = sharedProbeClient(),
) {

    /** 规范化后的服务端根地址（无尾斜杠）。 */
    val base: String = ServerEndpoint.normalize(baseUrl)

    private val family: String = familyId.trim()
    private val tokenValue: String = token.trim()

    /** 最近一次失败的中文原因（探活诊断用）。 */
    @Volatile
    var lastError: String? = null
        private set

    /** 探活：可达返回 true，不可达返回 false（中文原因在 [lastError]）。**不抛异常**。 */
    suspend fun ping(): Boolean = try {
        health()
        true
    } catch (t: Throwable) {
        lastError = HomeServerProtocol.networkMessage(t)
        false
    }

    /** GET /health。不是本应用的响应会抛中文 [SyncException]。 */
    suspend fun health(): HomeServerProtocol.Health {
        val u = url(HomeServerProtocol.SEG_HEALTH)
        val r = send(probeClient, u, "GET", null)
        if (!r.ok) throw fail(httpFailure(r.code, u, r.body))
        val h = HomeServerProtocol.parseHealth(r.body)
            ?: throw fail(
                SyncException("这个地址不是乖乖记账的同步服务（返回内容不认识）：${u.host}:${u.port}，请确认 NAS 上的服务已启动")
            )
        // 服务端把 familyId 带回来时校验一下，避免连到别人家的服务器
        if (h.familyId.isNotBlank() && !h.familyId.trim().equals(family, ignoreCase = true)) {
            throw fail(SyncException("这个地址上放的是另一本家庭账，不是你们家的账本，请检查同步地址"))
        }
        lastError = null
        return h
    }

    /**
     * GET /family —— 服务器上登记的家庭码（null = 还没人登记）。
     *
     * 这个接口**只校验 token、不要求 `X-Family-Id`**（客户端此时还不知道家庭码），
     * 所以零输入配对能成立：第一台登记，第二台读到不一样的家庭码后自动加入。
     */
    suspend fun getFamily(): String? {
        val u = url(HomeServerProtocol.SEG_FAMILY)
        val r = send(client, u, "GET", null)
        if (!r.ok) throw fail(httpFailure(r.code, u, r.body))
        return HomeServerProtocol.parseFamily(r.body)
    }

    /**
     * POST /family 登记本机家庭码。
     * 服务器上已有**另一个**家庭码时返回 [HomeServerProtocol.FamilyClaim.conflict] = true，
     * 并带上那一个（服务端绝不覆盖已有家庭）。
     */
    suspend fun claimFamily(familyId: String): HomeServerProtocol.FamilyClaim {
        val u = url(HomeServerProtocol.SEG_FAMILY)
        val body = HomeServerProtocol.familyClaimBody(familyId).toRequestBody(JSON_LINES)
        val r = send(client, u, "POST", body)
        if (r.code == 409) {
            return HomeServerProtocol.FamilyClaim(
                familyId = HomeServerProtocol.parseFamily(r.body).orEmpty(),
                created = false,
                conflict = true,
            )
        }
        if (!r.ok) throw fail(httpFailure(r.code, u, r.body))
        return HomeServerProtocol.FamilyClaim(
            familyId = HomeServerProtocol.parseFamily(r.body) ?: familyId,
            created = HomeServerProtocol.parseCreated(r.body),
            conflict = false,
        )
    }

    /** GET /devices，拿服务器上已有日志的设备列表。老服务端没有这个接口时返回空表。 */
    suspend fun devices(): List<String> {
        val u = url(HomeServerProtocol.SEG_DEVICES)
        val r = send(client, u, "GET", null)
        if (r.code == 404) return emptyList()
        if (!r.ok) throw fail(httpFailure(r.code, u, r.body))
        return HomeServerProtocol.parseDevices(r.body)
    }

    /**
     * GET /ops/{deviceId}/count —— 服务器上该设备已有多少行。
     * 返回负数表示服务端没这个接口（客户端退化为只用本地缓存计数）。
     */
    suspend fun opsCount(deviceId: String): Int {
        val u = url(HomeServerProtocol.SEG_OPS, deviceId, HomeServerProtocol.SEG_COUNT)
        val r = send(client, u, "GET", null)
        if (r.code == 404) return -1
        if (!r.ok) throw fail(httpFailure(r.code, u, r.body))
        return HomeServerProtocol.parseCount(r.body)
    }

    /** GET /ops/{deviceId}，返回该设备的 op 日志（JSONL）。没有日志返回空串。 */
    suspend fun getOps(deviceId: String): String {
        val id = deviceId.trim()
        if (id.isEmpty()) return ""
        val u = url(HomeServerProtocol.SEG_OPS, id)
        val r = send(client, u, "GET", null)
        if (r.code == 404) return ""
        if (!r.ok) throw fail(httpFailure(r.code, u, r.body))
        return r.body
    }

    /**
     * POST /ops，把 JSONL 追加到服务器（服务端按每行的 deviceId 分组）。
     * 服务端只追加、不去重 —— 重复的 op 在客户端是幂等的（`sync_op.opId` 是主键），
     * 所以超时后重试最多留下重复行，不会写坏数据。
     */
    suspend fun postOps(jsonl: String): HomeServerProtocol.PostResult {
        if (jsonl.isBlank()) return HomeServerProtocol.PostResult(0, 0)
        val u = url(HomeServerProtocol.SEG_OPS)
        val r = send(client, u, "POST", jsonl.toRequestBody(JSON_LINES))
        if (!r.ok) throw fail(httpFailure(r.code, u, r.body))
        return HomeServerProtocol.parsePostResult(r.body)
    }

    suspend fun putAttachment(reference: String, bytes: ByteArray) {
        val hash = requireNotNull(com.family.ledger.attachments.ReceiptReference.hash(reference))
        require(bytes.size <= com.family.ledger.attachments.ReceiptReference.MAX_BYTES)
        require(com.family.ledger.attachments.ReceiptReference.of(bytes) == reference)
        val u = url("attachments", hash)
        val result = send(client, u, "POST", bytes.toRequestBody("image/jpeg".toMediaType()))
        if (!result.ok) throw SyncException("截图上传失败（${result.code}），稍后同步会重试")
    }

    suspend fun getAttachment(reference: String): ByteArray = withContext(Dispatchers.IO) {
        val hash = requireNotNull(com.family.ledger.attachments.ReceiptReference.hash(reference))
        val request = Request.Builder().url(url("attachments", hash))
            .header(HomeServerProtocol.HEADER_FAMILY_ID, family)
            .header(HomeServerProtocol.HEADER_SYNC_TOKEN, tokenValue).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw SyncException("截图尚未同步（${response.code}），稍后会重试")
            val body = response.body ?: throw SyncException("截图内容为空")
            val limit = com.family.ledger.attachments.ReceiptReference.MAX_BYTES
            if (body.contentLength() > limit) throw SyncException("截图过大")
            val bytes = body.byteStream().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var count = input.read(buffer)
                while (count >= 0) {
                    if (out.size() + count > limit) throw SyncException("截图过大")
                    out.write(buffer, 0, count); count = input.read(buffer)
                }
                out.toByteArray()
            }
            if (com.family.ledger.attachments.ReceiptReference.of(bytes) != reference) throw SyncException("截图校验失败")
            bytes
        }
    }

    // ---------------------------------------------------------------- 内部

    private class HttpResult(val code: Int, val body: String) {
        val ok: Boolean get() = code in 200..299
    }

    private fun url(vararg segments: String): HttpUrl {
        val parsed = base.toHttpUrlOrNull()
            ?: throw SyncException("同步服务器地址不正确：${base.ifBlank { "（空地址）" }}")
        val b = parsed.newBuilder()
        for (seg in segments) if (seg.isNotBlank()) b.addPathSegment(seg)
        return b.build()
    }

    private fun fail(t: Throwable): Throwable {
        lastError = HomeServerProtocol.networkMessage(t)
        return t
    }

    private fun httpFailure(code: Int, url: HttpUrl, body: String): SyncException {
        val head = HomeServerProtocol.httpMessage(code, url.toString())
        val detail = HomeServerProtocol.parseServerError(body)
        return SyncException(if (detail != null) "$head（服务端：$detail）" else head)
    }

    /** 带重试的请求：网络异常 / 5xx 重试，其余状态码原样交给上层判断。 */
    private suspend fun send(
        http: OkHttpClient,
        url: HttpUrl,
        method: String,
        body: RequestBody?,
    ): HttpResult =
        withContext(Dispatchers.IO) {
            var lastIo: IOException? = null
            var last5xx: HttpResult? = null
            var attempt = 1
            while (attempt <= maxAttempts) {
                val result: HttpResult? = try {
                    val builder = Request.Builder()
                        .url(url)
                        .header(HomeServerProtocol.HEADER_FAMILY_ID, family)
                    if (tokenValue.isNotEmpty()) {
                        // 主用自定义头：跨域 307 跳转（QuickConnect 中继）不会把它丢掉
                        builder.header(HomeServerProtocol.HEADER_SYNC_TOKEN, tokenValue)
                        // 兼容老服务端 / 局域网直连
                        builder.header(HomeServerProtocol.HEADER_AUTHORIZATION, "Bearer $tokenValue")
                    }
                    if (method == "POST") builder.post(body ?: EMPTY_BODY) else builder.get()
                    http.newCall(builder.build()).execute().use { resp ->
                        HttpResult(resp.code, resp.body?.string().orEmpty())
                    }
                } catch (t: IOException) {
                    lastIo = t
                    null
                }
                if (result != null) {
                    if (result.code in 500..599 && attempt < maxAttempts) last5xx = result
                    else return@withContext result
                }
                attempt++
                if (attempt <= maxAttempts) delay(retryDelayMs * (attempt - 1))
            }
            val io = lastIo
            if (io != null) throw SyncException(HomeServerProtocol.networkMessage(io), io)
            last5xx?.let { return@withContext it }
            throw SyncException("家里的服务器没有响应，请稍后再试")
        }

    companion object {
        private val JSON_LINES = "application/x-ndjson; charset=utf-8".toMediaType()
        private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(JSON_LINES)
        private const val RETRY_BASE_DELAY_MS = 400L

        /** 进程内共用一个数据客户端：连接池 / 线程池复用，不要每次同步都新建。 */
        private val DATA: OkHttpClient by lazy { dataClient() }

        /** 进程内共用一个探活客户端。 */
        private val PROBE: OkHttpClient by lazy { probeClient() }

        fun sharedDataClient(): OkHttpClient = DATA

        fun sharedProbeClient(): OkHttpClient = PROBE

        /** 进程内共用的「外网」数据 / 探活客户端（中继慢，超时放宽）。 */
        private val WAN_DATA: OkHttpClient by lazy { wanDataClient() }
        private val WAN_PROBE: OkHttpClient by lazy { wanProbeClient() }

        fun sharedWanDataClient(): OkHttpClient = WAN_DATA

        fun sharedWanProbeClient(): OkHttpClient = WAN_PROBE

        /**
         * 按地址选超时策略建 transport：**局域网用短超时**（最多等 3~4 秒，不让用户干等），
         * **外网（QuickConnect 中继）用长超时**（中继比局域网慢得多，但不会卡死）。
         */
        fun forUrl(baseUrl: String, familyId: String, token: String): HomeServerTransport {
            val lan = ServerEndpoint.isPrivate(baseUrl)
            return HomeServerTransport(
                baseUrl = baseUrl,
                familyId = familyId,
                token = token,
                client = if (lan) sharedDataClient() else sharedWanDataClient(),
                probeClient = if (lan) sharedProbeClient() else sharedWanProbeClient(),
            )
        }

        /** 传数据的客户端：读超时放宽（首次全量上传可能几百 KB），仍然不会长时间挂死。 */
        fun dataClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        /**
         * 局域网探活客户端：手机端要快速在「局域网 / 外网」之间选一个能用的，
         * 局域网这条最多等 3~4 秒，不能让用户对着转圈等太久。
         */
        fun probeClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()

        /** 外网数据客户端：QuickConnect 中继比局域网慢得多，超时按 lead 的建议放宽。 */
        fun wanDataClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        /** 外网探活客户端：只 GET /health，给中继留足建连 + 跳转时间，但别到 60 秒那么久。 */
        fun wanProbeClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
