package com.family.ledger.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 「家庭同步服务器」协议（纯 Kotlin，可单测）。
 *
 * 背景：用户明确反对「自己填 WebDAV 服务器 / 账号 / 密码」。家里的 NAS 常驻在线，
 * 于是把同步改成「两台手机都连家里的同步服务」—— 在家走局域网地址，在外面走公网地址，
 * **同一条代码路径**，用户什么都不用填。
 *
 * 服务端接口（由 lead 部署在 NAS 上，Python 单文件 + Docker）：
 * ```
 * GET  /health          → 200 {"app":"familyledger","v":1}
 * GET  /devices         → 200 {"devices":["dev-a","dev-b"]}
 * GET  /ops/{deviceId}  → 200 text/plain，每行一个 SyncOpEntity 的 JSON；没有则 200 + 空体
 * POST /ops             → body 为 JSONL（每行含 deviceId），按 deviceId 分组追加；返回 {"applied":N}
 * GET  /family          → 200 {"familyId":"XXXXXXXX"} 或 {"familyId":null}（还没人登记）
 * POST /family          → body {"familyId":"XXXXXXXX"}；首次 200 {"created":true}；
 *                          已被别人登记且不同 → 409（**绝不覆盖**）；相同 → 200 {"created":false}
 * 请求头：X-Family-Id: <familyId>        数据端点（/ops、/devices）必填，缺失或不一致返回 403
 *        Authorization: Bearer <token>   可选，服务端配了口令则必填
 * 存储：服务端目录 <familyId>/<deviceId>.jsonl，只追加
 * ```
 *
 * ⚠️ **鉴权差异（刻意如此，别"顺手统一"）**：`/health` 与 `/family` **只校验 token、
 * 不要求 `X-Family-Id``** —— 首次配对时客户端还不知道对方的家庭码，鸡生蛋问题：
 * 第一台手机必须先 `GET /family`（返回 null）再 `POST /family` 登记自己的家庭码。
 * 数据端点（`/ops`、`/devices`）才同时要求 token + `X-Family-Id`，那才是数据隔离边界。
 *
 * 客户端这边**只搬运 op 日志**：合并仍然由 [SyncEngine] 按 LWW 处理，
 * 应用远端 op 只走 `sync_op` 表 upsert，**绝不经过 SyncJournal**（否则两端无限回环）。
 */
object HomeServerProtocol {

    const val APP_ID = "familyledger"

    /** 协议版本，服务端与客户端必须一致（不兼容时 health 直接判失败）。 */
    const val PROTOCOL_VERSION = 1

    const val HEADER_FAMILY_ID = "X-Family-Id"

    /** URL 路径段（客户端用 `HttpUrl.addPathSegment` 拼，不手拼字符串）。 */
    const val SEG_HEALTH = "health"
    const val SEG_DEVICES = "devices"
    const val SEG_OPS = "ops"
    const val SEG_COUNT = "count"
    const val SEG_FAMILY = "family"

    const val PATH_HEALTH = "/$SEG_HEALTH"
    const val PATH_DEVICES = "/$SEG_DEVICES"
    const val PATH_OPS = "/$SEG_OPS"
    const val PATH_FAMILY = "/$SEG_FAMILY"

    /**
     * 访问口令的**主**请求头。
     *
     * 为什么不用 `Authorization`：外网走群晖 QuickConnect 中继时会做一次跨域 307 跳转，
     * OkHttp（与 curl 一样）在**跨域跳转时会按安全策略丢掉 `Authorization`**，
     * 请求到了服务端就变成没带口令 → 401。自定义头不会被丢。
     */
    const val HEADER_SYNC_TOKEN = "X-Sync-Token"

    /** 兼容老服务端 / 局域网直连的写法（`Authorization: Bearer <token>`）。 */
    const val HEADER_AUTHORIZATION = "Authorization"

    val JSON: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    // ------------------------------------------------------------------ 响应体

    @Serializable
    data class Health(
        val app: String = "",
        val v: Int = 0,
        val familyId: String = "",
        val name: String = "",
    )

    @Serializable
    data class DeviceList(val devices: List<String> = emptyList())

    @Serializable
    data class Applied(val applied: Int = 0, val bad: Int = 0, val error: String = "")

    @Serializable
    data class Count(val count: Int = 0)

    /** POST /ops 的结果：服务端写入了多少行、忽略了多少行坏数据。 */
    data class PostResult(val applied: Int, val bad: Int = 0)

    /**
     * `/family` 的请求体与响应体。
     * 服务端只认一本账：第一台手机登记自己的家庭码，第二台手机读到不一样的家庭码后自动加入
     * —— 这就是「零输入配对」。
     */
    @Serializable
    data class FamilyInfo(
        val familyId: String? = null,
        val created: Boolean = false,
        val error: String = "",
    )

    /** 登记家庭码的结果：conflict=true 表示服务器上已有**另一个**家庭码（绝不会被覆盖）。 */
    data class FamilyClaim(val familyId: String, val created: Boolean = false, val conflict: Boolean = false)

    /** 解析 /health；不是本应用的响应返回 null（避免把路由器登录页当成同步服务）。 */
    fun parseHealth(text: String?): Health? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty() || !t.startsWith("{")) return null
        val h = runCatching { JSON.decodeFromString(Health.serializer(), t) }.getOrNull() ?: return null
        if (h.app != APP_ID) return null
        if (h.v != PROTOCOL_VERSION) return null
        return h
    }

    /** 解析 /devices；解析不了就返回空表（协议允许服务端不提供设备列表）。 */
    fun parseDevices(text: String?): List<String> {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return emptyList()
        // 兼容两种写法：{"devices":[...]} 或直接 ["dev-a","dev-b"]
        if (t.startsWith("{")) {
            return runCatching { JSON.decodeFromString(DeviceList.serializer(), t) }
                .getOrNull()?.devices.orEmpty()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        }
        return runCatching { kotlinx.serialization.json.Json.decodeFromString<List<String>>(t) }
            .getOrNull().orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    /** 解析 POST /ops 的 `{"applied":N,"bad":M}`；解析不了返回 0。 */
    fun parseApplied(text: String?): Int {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return 0
        return runCatching { JSON.decodeFromString(Applied.serializer(), t) }.getOrNull()?.applied ?: 0
    }

    /** 解析 POST /ops 的完整结果。 */
    fun parsePostResult(text: String?): PostResult {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return PostResult(0, 0)
        val a = runCatching { JSON.decodeFromString(Applied.serializer(), t) }.getOrNull()
            ?: return PostResult(0, 0)
        return PostResult(a.applied, a.bad)
    }

    /** 解析 `GET /family`：`{"familyId":"X"}` → "X"；`{"familyId":null}` / 空体 → null（还没人登记）。 */
    fun parseFamily(text: String?): String? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return null
        return runCatching { JSON.decodeFromString(FamilyInfo.serializer(), t) }
            .getOrNull()?.familyId?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** `POST /family` 的请求体。 */
    fun familyClaimBody(familyId: String): String =
        JSON.encodeToString(FamilyInfo.serializer(), FamilyInfo(familyId = familyId))

    /** 解析 `POST /family` 的 `created` 字段。 */
    fun parseCreated(text: String?): Boolean =
        runCatching { JSON.decodeFromString(FamilyInfo.serializer(), text?.trim().orEmpty()) }
            .getOrNull()?.created ?: false

    /** 解析 `GET /ops/{deviceId}/count` 的 `{"count":N}`；解析不了返回 -1（未知）。 */
    fun parseCount(text: String?): Int {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return -1
        return runCatching { JSON.decodeFromString(Count.serializer(), t) }.getOrNull()?.count ?: -1
    }

    /** 服务端能在 body 里带中文原因时用它（便于排查）。 */
    fun parseServerError(text: String?): String? =
        runCatching { JSON.decodeFromString(Applied.serializer(), text?.trim().orEmpty()) }
            .getOrNull()?.error?.takeIf { it.isNotBlank() }

    // ------------------------------------------------------------------ 文案

    /** HTTP 状态码 → 用户可读中文。 */
    fun httpMessage(code: Int, url: String): String = when (code) {
        400 -> "家里的服务器拒绝了这次请求（400）：$url"
        401 -> "家里的服务器需要同步口令（401）：请到「设置 → 同步」填写访问口令"
        403 -> "家里的服务器不认识这本账（403）：家庭码不一致，请确认两台手机用的是同一个家庭码"
        404 -> "家里的服务器上没有这个接口（404）：请确认 NAS 上的同步服务是新版本"
        413 -> "要上传的账目数据太大了（413），请稍后再试"
        500, 502, 503, 504 -> "家里的服务器暂时不可用（HTTP $code），请稍后再试"
        in 500..599 -> "家里的服务器出错了（HTTP $code），请稍后再试"
        else -> "家里的服务器请求失败（HTTP $code）：$url"
    }

    /**
     * 所有候选地址都连不上时的中文提示。
     * 第 ③ 条是用户真机的真实情况：他开着 VPN，VPN 可能拦截局域网流量。
     */
    fun noServerMessage(reasons: List<String>): String {
        val head = "连不上家里的同步服务。请确认：① 在家时手机连的是家里的 Wi-Fi；" +
            "② 家里的 NAS 已开机、同步服务正在运行；" +
            "③ 检查所填 HTTPS 地址或私有网络是否可达；" +
            "④ 如果手机开了 VPN，先关掉试试（VPN 可能拦截局域网）。"
        if (reasons.isEmpty()) return head
        return "$head（${reasons.joinToString("；")}）"
    }

    /**
     * 网络异常 → 中文原因。刻意不用 [SyncErrors.translate]：那套文案是给 WebDAV 写的，
     * 而且会把 OkHttp 的英文原文（Failed to connect to ...）直接抛给用户。
     */
    fun networkMessage(t: Throwable?): String = when (t) {
        null -> "网络错误"
        is SyncException -> t.message ?: "同步失败"
        is java.net.ConnectException -> "连不上服务器（可能不在家、NAS 没开机，或手机开着 VPN 拦截了局域网）"
        is java.net.SocketTimeoutException -> "连接服务器超时（检查网络后重试）"
        is java.net.UnknownHostException -> "解析服务器地址失败（检查手机网络）"
        is java.io.IOException -> "网络错误：${t.message ?: t.javaClass.simpleName}"
        else -> "同步失败：${t.message ?: t.javaClass.simpleName}"
    }

    /**
     * 本机已有真实流水、而服务器上已经有另一份账本时，不自动合并的提示。
     *
     * 注意：**不要再引导用户「去家庭页输入配对码」** —— 家庭码 UI 已经删掉了
     * （家里就两个人 + 一台自己的 NAS，不需要配对流程）。
     * 这里要说清「数据没丢」并给一个真正可执行的下一步。
     */
    fun familyConflictMessage(localTxnCount: Int): String =
        "这台手机已经记了 $localTxnCount 笔账，而家里的账本上已经有另一份数据，" +
            "为了避免两份账混在一起，App 这次没有自动合并。你的账单都还在本机、没有丢。" +
            "请先到「设置 → 数据」导出 CSV 备份，在确认备份后人工处理合并。"

    /** 自动加入失败（极少见）时的提示。 */
    fun familyJoinFailedMessage(): String =
        "没能自动加入家里的账本。你的账单都在本机没有丢；" +
            "请确认家里 NAS 上的同步服务在运行，稍后 App 会自动重试。"

    /** 服务器可达但一次数据都没交换时的提示。 */
    fun serverReachableNoData(serverName: String): String =
        "已连接「$serverName」：当前没有需要同步的新账目"
}
