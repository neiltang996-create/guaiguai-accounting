package com.family.ledger.sync

/** Runtime override, last successful endpoint, then optional private build defaults. */
object ServerEndpoint {
    const val DEFAULT_PORT = 47822
    const val DEFAULT_SERVER_NAME = "家里的 NAS"
    const val DEFAULT_LAN_URL = com.family.ledger.BuildConfig.DEFAULT_LAN_URL
    const val DEFAULT_WAN_URL = com.family.ledger.BuildConfig.DEFAULT_WAN_URL
    const val DEFAULT_TOKEN = com.family.ledger.BuildConfig.DEFAULT_SYNC_TOKEN
    val BUILT_IN = listOf(DEFAULT_LAN_URL, DEFAULT_WAN_URL).filter { it.isNotBlank() }

    /** 上次成功地址的缓存键（`sync_state`）。 */
    const val STATE_LAST_GOOD_URL = "homeServerUrl"

    /** 上次成功同步时间 / 上次同步的对方设备（`sync_state`）。 */
    const val STATE_LAST_SYNC_AT = "homeLastSyncAt"
    const val STATE_LAST_PEERS = "homeLastPeers"

    /** 实际使用的同步口令：用户覆盖优先，否则用内置默认值。 */
    fun token(overrideToken: String): String = overrideToken.trim().ifBlank { DEFAULT_TOKEN }

    /** 去掉首尾空白与结尾斜杠。 */
    fun normalize(url: String): String = url.trim().trimEnd('/')

    /**
     * 候选地址顺序：**用户覆盖 → 上次成功 → 内置局域网 → 内置公网**。
     * 空串自动丢弃，重复地址只留第一个（`distinct`）。
     */
    fun candidates(overrideUrl: String = "", lastGoodUrl: String? = null): List<String> {
        val all = ArrayList<String>(4)
        all += normalize(overrideUrl)
        all += normalize(lastGoodUrl.orEmpty())
        BUILT_IN.forEach { all += normalize(it) }
        return all.filter { it.isNotEmpty() }.distinct()
    }

    /** 是否内网地址（只做字面量判断，用于给用户显示「局域网 / 公网」）。 */
    fun isPrivate(url: String): Boolean {
        val host = hostOf(url) ?: return false
        if (host == "localhost" || host.endsWith(".local")) return true
        val parts = host.split('.')
        if (parts.size != 4) return false
        val nums = parts.map { it.toIntOrNull() ?: return false }
        return when {
            nums[0] == 10 -> true
            nums[0] == 192 && nums[1] == 168 -> true
            nums[0] == 172 && nums[1] in 16..31 -> true
            nums[0] == 127 -> true
            else -> false
        }
    }

    /** 「局域网地址 / 公网地址 / 自定义地址」，用于拼中文提示。 */
    fun label(url: String): String = when {
        normalize(url).isEmpty() -> "未设置地址"
        isPrivate(url) -> "局域网地址"
        else -> "公网地址"
    }

    private fun hostOf(url: String): String? {
        val afterScheme = url.trim().substringAfter("://", "").substringBefore('/')
        if (afterScheme.isEmpty()) return null
        // 形如 host:port；IPv6 暂不支持（家用场景不会用）
        return afterScheme.substringBefore(':').substringBefore('@').lowercase().ifBlank { null }
    }
}
