package com.family.ledger.sync

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** 同步失败。message 一律是可直接展示给用户的中文。 */
class SyncException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * WebDAV 目录约定（纯字符串运算，可单测）。
 *
 * 布局：`<syncUrl>/familyledger/<familyId>/<deviceId>.jsonl`
 *
 * 两台手机写同一个「家庭目录」，一台手机一个日志文件，互不覆盖：
 *   - 上传：只写自己 `<deviceId>.jsonl`（内容是自己的 oplog，一行一个 SyncOpEntity）
 *   - 下载：读对方的 `<deviceId>.jsonl`
 *
 * 如果用户填的 syncUrl 里已经带了 `familyledger` 这一段，就不再重复追加，
 * 于是 `https://dav.jianguoyun.com/dav/` 和 `.../dav/familyledger/` 两种填法都能用。
 */
object SyncPaths {

    /** 应用专用的根目录名。 */
    const val ROOT_DIR = "familyledger"

    /** 去掉尾部斜杠；必要时补上 familyledger 这一段。 */
    fun baseDir(syncUrl: String): String {
        val trimmed = syncUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        val path = trimmed.substringAfter("://", trimmed)
        val hasRoot = path.split('/').any { it.equals(ROOT_DIR, ignoreCase = true) }
        return if (hasRoot) trimmed else "$trimmed/$ROOT_DIR"
    }

    /**
     * 家庭目录（两台手机共用）。
     * [familyId] 必须来自 `settings.familyId`：还没初始化时由 SyncRepository 直接拒绝同步，
     * 不会用空字符串拼路径（否则两端会各写各的目录，永远同步不到一起）。
     */
    fun familyDir(syncUrl: String, familyId: String): String {
        val fam = familyId.trim()
        val base = baseDir(syncUrl)
        return if (base.isEmpty()) fam else "$base/$fam"
    }

    /** 某台手机的设备日志文件 URL。 */
    fun journalUrl(syncUrl: String, familyId: String, deviceId: String): String =
        "${familyDir(syncUrl, familyId)}/${fileName(deviceId)}"

    fun fileName(deviceId: String): String = "$deviceId.jsonl"

    /** 从 PROPFIND 列出的文件名里还原设备 ID；不是日志文件返回 null。 */
    fun deviceIdOfFileName(name: String): String? {
        val plain = name.substringAfterLast('/').trim()
        if (!plain.endsWith(".jsonl", ignoreCase = true)) return null
        return plain.dropLast(".jsonl".length).trim().ifBlank { null }
    }
}

/** 异常 / HTTP 状态码 → 用户可读中文提示。 */
object SyncErrors {

    fun translate(t: Throwable?): String = when (t) {
        null -> "同步失败：未知错误"
        is SyncException -> t.message ?: "同步失败"
        is UnknownHostException -> "无法解析同步服务器地址，请检查手机网络与同步地址"
        is SocketTimeoutException -> "连接 WebDAV 服务器超时，请检查网络后重试"
        is SSLException -> "HTTPS 安全连接失败，请检查同步地址与证书"
        is IllegalArgumentException -> "同步地址格式不正确：请填写 http(s):// 开头的 WebDAV 目录地址"
        is IOException -> "网络错误：${t.message ?: t.javaClass.simpleName}"
        else -> "同步失败：${t.message ?: t.javaClass.simpleName}"
    }

    fun http(code: Int, what: String): String = when (code) {
        400 -> "WebDAV 请求被拒绝（400）：$what"
        401 -> "WebDAV 认证失败（401）：账号或密码不对，坚果云请用「应用密码」"
        403 -> "没有权限访问 WebDAV 目录（403）：$what"
        404 -> "WebDAV 目录不存在（404）：$what"
        405 -> "WebDAV 目录已存在（405）"
        409 -> "WebDAV 目录不存在，无法在其下创建文件（409）：$what"
        423 -> "WebDAV 文件被锁定（423），请稍后重试"
        507 -> "WebDAV 空间不足（507），请清理云端空间"
        in 500..599 -> "同步服务器暂时不可用（HTTP $code），请稍后重试"
        else -> "WebDAV 请求失败（HTTP $code）：$what"
    }
}
