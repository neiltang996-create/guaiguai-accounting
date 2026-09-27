package com.family.ledger.sync

import com.family.ledger.data.db.entity.SyncOpEntity
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 测试用的「假 home server」：用 `java.net.ServerSocket` 手写极简 HTTP/1.1，
 * 行为与 lead 的真服务端（`server/familyledger_sync_server.py`）一致。
 *
 * **不能用 `com.sun.net.httpserver`**：Android 单测的编译 classpath 是 android.jar，
 * 看不到 jdk.httpserver 模块，会直接让整个单测编译失败。
 *
 * 覆盖的接口：
 * ```
 * GET  /health                → {"app":"familyledger","v":1,"time":...}
 * GET  /devices               → {"devices":[...]}
 * GET  /ops/{deviceId}        → JSONL（未知设备 200 + 空体）
 * GET  /ops/{deviceId}/count  → {"count":N}
 * POST /ops                   → 按每行 deviceId 分组追加 → {"applied":N,"bad":M}
 * 缺 X-Family-Id → 403；配了 token 而 Authorization 不对 → 401；其他路径 404；非 GET/POST 405
 * ```
 */
class FakeHomeServer(
    private val familyId: String = "FAMILY01",
    private val token: String = "",
    /** 预置服务器上已登记的家庭码（模拟「对方手机先登记了」）。 */
    registeredFamily: String? = null,
) {

    private val server: ServerSocket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress("127.0.0.1", 0))
    }

    val port: Int get() = server.localPort
    val baseUrl: String get() = "http://127.0.0.1:$port"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val opsFiles = ConcurrentHashMap<String, MutableList<String>>()
    private val pool = Executors.newFixedThreadPool(4)
    private val requests = AtomicInteger(0)
    @Volatile private var running = true

    /** 让接下来 N 个请求返回 503（用来验证客户端重试）。 */
    @Volatile var failNextRequests = 0

    /** 服务器上登记的家庭码（`/family`）。 */
    @Volatile var registeredFamily: String? = registeredFamily

    /**
     * 模拟「两台手机同时登记」的竞争：`GET /family` 仍返回 null，但 `POST /family` 立刻 409
     * （另一台手机抢先登记了这个家庭码）。
     */
    @Volatile var raceFamilyId: String? = null

    fun start(): FakeHomeServer {
        Thread({ acceptLoop() }, "fake-home-server").apply { isDaemon = true }.start()
        return this
    }

    fun stop() {
        running = false
        runCatching { server.close() }
        pool.shutdownNow()
    }

    fun requestCount(): Int = requests.get()

    /** 服务器上某设备的 op 行（原样 JSON 字符串）。 */
    fun opsOf(deviceId: String): List<String> = opsFiles[deviceId]?.toList().orEmpty()

    fun lineCount(deviceId: String): Int = opsFiles[deviceId]?.size ?: 0

    fun deviceIds(): List<String> = opsFiles.keys.sorted()

    /** 模拟服务端文件被误删：本机缓存与服务器行数不一致时应自动全量重传。 */
    fun clearOps(deviceId: String) {
        opsFiles.remove(deviceId)
    }

    /** 原始 HTTP 请求（用来验证 403 / 404 / 405 这类传输层不直接暴露的状态码）。 */
    fun raw(
        method: String,
        path: String,
        family: String? = familyId,
        auth: String? = if (token.isNotEmpty()) "Bearer $token" else null,
        syncToken: String? = if (token.isNotEmpty()) token else null,
        body: String = "",
    ): String {
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 4000
            val bytes = body.toByteArray(Charsets.UTF_8)
            val head = buildString {
                append("$method $path HTTP/1.1\r\n")
                append("Host: 127.0.0.1:$port\r\n")
                family?.let { append("${HomeServerProtocol.HEADER_FAMILY_ID}: $it\r\n") }
                auth?.let { append("${HomeServerProtocol.HEADER_AUTHORIZATION}: $it\r\n") }
                syncToken?.let { append("${HomeServerProtocol.HEADER_SYNC_TOKEN}: $it\r\n") }
                if (bytes.isNotEmpty()) append("Content-Length: ${bytes.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
            val out = s.getOutputStream()
            out.write(head.toByteArray(Charsets.UTF_8))
            if (bytes.isNotEmpty()) out.write(bytes)
            out.flush()
            return s.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    // ---------------------------------------------------------------- 内部

    private fun acceptLoop() {
        while (running) {
            val socket = try {
                server.accept()
            } catch (t: Throwable) {
                break
            }
            pool.execute { handle(socket) }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 4000
            val input = BufferedInputStream(socket.getInputStream())
            val out = socket.getOutputStream()
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0).orEmpty().uppercase()
            val target = parts.getOrNull(1).orEmpty()
            val path = target.substringBefore('?').trimEnd('/').ifBlank { "/" }

            var contentLength = 0
            var family: String? = null
            var auth: String? = null
            var syncToken: String? = null
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i <= 0) continue
                when (line.substring(0, i).trim().lowercase()) {
                    "content-length" -> contentLength = line.substring(i + 1).trim().toIntOrNull() ?: 0
                    HomeServerProtocol.HEADER_FAMILY_ID.lowercase() -> family = line.substring(i + 1).trim()
                    HomeServerProtocol.HEADER_AUTHORIZATION.lowercase() -> auth = line.substring(i + 1).trim()
                    HomeServerProtocol.HEADER_SYNC_TOKEN.lowercase() -> syncToken = line.substring(i + 1).trim()
                }
            }
            val body = readBody(input, contentLength)
            requests.incrementAndGet()

            if (failNextRequests > 0) {
                failNextRequests--
                return respond(out, 503, """{"error":"server busy"}""")
            }
            // 与真服务端一致：/health 与 /family 只校验 token（客户端此时还不知道家庭码）
            val openPath = path == HomeServerProtocol.PATH_HEALTH || path == HomeServerProtocol.PATH_FAMILY
            if (!openPath && family.isNullOrBlank()) {
                return respond(out, 403, """{"error":"缺少 X-Family-Id"}""")
            }
            // 与真服务端一致：X-Sync-Token 优先，Authorization: Bearer 兼容
            val tokenOk = token.isEmpty() || syncToken == token || auth == "Bearer $token"
            if (!tokenOk) {
                return respond(out, 401, """{"error":"token 不正确"}""")
            }

            when {
                method == "GET" && path == HomeServerProtocol.PATH_HEALTH ->
                    respond(out, 200, """{"app":"familyledger","v":1,"time":${System.currentTimeMillis()}}""")

                method == "GET" && path == HomeServerProtocol.PATH_FAMILY ->
                    respond(
                        out,
                        200,
                        """{"familyId":${if (raceFamilyId != null) "null" else familyJson(registeredFamily)}}""",
                    )

                method == "POST" && path == HomeServerProtocol.PATH_FAMILY -> {
                    val conflict = raceFamilyId
                    if (conflict != null) {
                        respond(out, 409, """{"familyId":"$conflict","error":"家庭码已存在，请改用该家庭码"}""")
                    } else {
                        val incoming = runCatching {
                            json.decodeFromString(HomeServerProtocol.FamilyInfo.serializer(), body.trim())
                                .familyId?.trim().orEmpty()
                        }.getOrDefault("")
                        val current = registeredFamily
                        when {
                            incoming.isEmpty() -> respond(out, 400, """{"error":"familyId 不合法"}""")
                            current == null -> {
                                registeredFamily = incoming
                                respond(out, 200, """{"familyId":"$incoming","created":true}""")
                            }
                            current == incoming -> respond(out, 200, """{"familyId":"$current","created":false}""")
                            else -> respond(
                                out,
                                409,
                                """{"familyId":"$current","error":"家庭码已存在，请改用该家庭码"}""",
                            )
                        }
                    }
                }

                method == "GET" && path == HomeServerProtocol.PATH_DEVICES ->
                    respond(out, 200, """{"devices":[${deviceIds().joinToString(",") { "\"$it\"" }}]}""")

                method == "GET" && path.startsWith("/ops/") && path.endsWith("/count") -> {
                    val dev = path.removePrefix("/ops/").removeSuffix("/count")
                    respond(out, 200, """{"count":${lineCount(dev)}}""")
                }

                method == "GET" && path.startsWith("/ops/") -> {
                    val dev = path.removePrefix("/ops/")
                    respond(out, 200, opsOf(dev).joinToString("\n"), "text/plain; charset=utf-8")
                }

                method == "POST" && path == HomeServerProtocol.PATH_OPS -> {
                    var applied = 0
                    var bad = 0
                    for (raw in body.split('\n')) {
                        val line = raw.trim()
                        if (line.isEmpty()) continue
                        val op = runCatching { json.decodeFromString(SyncOpEntity.serializer(), line) }.getOrNull()
                        if (op == null || op.deviceId.isBlank()) {
                            bad++
                            continue
                        }
                        opsFiles.computeIfAbsent(op.deviceId) { ArrayList() }!!.add(line)
                        applied++
                    }
                    respond(out, 200, """{"applied":$applied,"bad":$bad}""")
                }

                method != "GET" && method != "POST" -> respond(out, 405, """{"error":"method not allowed"}""")

                else -> respond(out, 404, """{"error":"not found"}""")
            }
        } catch (t: Throwable) {
            // 测试用服务端：单个连接出错不能影响其它请求
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun respond(out: OutputStream, code: Int, body: String, type: String = "application/json; charset=utf-8") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $code ${reason(code)}\r\n")
            append("Content-Type: $type\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.UTF_8))
        if (bytes.isNotEmpty()) out.write(bytes)
        out.flush()
    }

    private fun familyJson(id: String?): String = if (id.isNullOrBlank()) "null" else "\"$id\""

    private fun reason(code: Int): String = when (code) {
        400 -> "Bad Request"
        200 -> "OK"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Payload Too Large"
        else -> "Error"
    }

    private fun readLine(input: InputStream): String? {
        val buf = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.isEmpty()) null else buf.toString()
            if (b == '\n'.code) return buf.toString().trimEnd('\r')
            if (buf.length > 8192) return buf.toString()
            buf.append(b.toChar())
        }
    }

    private fun readBody(input: InputStream, length: Int): String {
        if (length <= 0) return ""
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buf, read, length - read)
            if (n < 0) break
            read += n
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }
}
