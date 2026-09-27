package com.family.ledger.sync

import com.family.ledger.data.db.entity.SyncOpEntity

/**
 * 服务器同步的本地状态读写（Room 的 `sync_state` 表；单测里换成内存实现）。
 * 客户端用它记「服务器上已有我多少条 op」「上次成功地址」「上次同步时间」。
 */
interface HomeSyncState {

    suspend fun get(key: String): String?

    suspend fun put(key: String, value: String)

    object None : HomeSyncState {
        override suspend fun get(key: String): String? = null
        override suspend fun put(key: String, value: String) = Unit
    }
}

/**
 * 一次「家庭服务器」同步的结果。
 *
 * [serverOk] = false 表示服务器没连上（[message] 就是中文原因）；
 * true 表示连上了（哪怕这次没有新数据）。
 */
data class HomeSyncResult(
    val serverOk: Boolean,
    val complete: Boolean = serverOk,
    val peersSeen: Int = 0,
    val pulled: Int = 0,
    val pushed: Int = 0,
    val message: String = "",
    val serverUrl: String = "",
    val serverName: String = ServerEndpoint.DEFAULT_SERVER_NAME,
)

/**
 * 「家庭服务器」同步引擎：两台手机都只跟服务器打交道，通过服务器中转收敛。
 *
 * 一次同步：
 *   1. `GET /health` 确认服务器可达（不可达直接返回中文原因）
 *   2. 找出对方的 deviceId：服务器设备列表 + 家庭成员表 + 历史同步记录
 *   3. `GET /ops/{对方deviceId}` 拉对方的日志，交给 [SyncEngine] 按 LWW 合并
 *   4. 把**本机**的 op 日志 `POST /ops` 推上去（只推增量，避免文件无限膨胀）
 *   5. 记 `sync_state`：上次成功地址、上次同步时间、已上传条数
 *
 * **绝不经过 `SyncJournal`**：应用远端 op 只走 `sync_op` 表 upsert，
 * 否则两端会把对方的 op 再记一遍，无限回环（见 [RoomSyncEntityStore]）。
 */
class HomeServerEngine(
    private val transport: HomeServerTransport,
    private val merge: SyncEngine,
    private val myDeviceId: String,
    private val state: HomeSyncState = HomeSyncState.None,
    /** 本机全部 op（仓库层只返回本机 deviceId 的，按 seq 升序）。 */
    private val localOps: suspend () -> List<SyncOpEntity> = { emptyList() },
    /** 已知的其它设备 deviceId（家庭成员表 + 历史 peer 记录）。 */
    private val knownPeers: suspend () -> List<String> = { emptyList() },
) {

    suspend fun sync(): HomeSyncResult {
        val url = transport.base
        val serverName = ServerEndpoint.DEFAULT_SERVER_NAME

        // 1) 服务器可达性
        try {
            transport.health()
        } catch (t: Throwable) {
            return HomeSyncResult(
                serverOk = false,
                message = HomeServerProtocol.networkMessage(t),
                serverUrl = url,
                serverName = serverName,
            )
        }

        val warnings = ArrayList<String>(2)

        // 2) 本机 op（只推自己的；别人的 op 由 sync_op 表留着做版本比较）
        val myOps = runCatching { localOps() }
            .getOrElse {
                warnings += "读取本机账目失败：${HomeServerProtocol.networkMessage(it)}"
                emptyList()
            }
            .filter { it.deviceId == myDeviceId }
            .sortedBy { it.seq }

        // 3) 拉取对方的日志并合并
        val peers = discoverPeers(warnings)
        var pulled = 0
        for (peer in peers) {
            try {
                val text = transport.getOps(peer)
                val ops = merge.parseJournal(text)
                    // 对方日志里不许出现「我」的 deviceId（防止伪造后被我当成本机日志再推出去）
                    .filter { it.deviceId.isNotBlank() && it.deviceId != myDeviceId }
                    .distinctBy { it.opId }
                if (ops.isNotEmpty()) pulled += merge.applyOps(ops)
            } catch (t: Throwable) {
                warnings += "拉取另一部手机的账目失败：${HomeServerProtocol.networkMessage(t)}"
            }
        }

        // 4) 推送增量
        val now = System.currentTimeMillis()
        val pushed = pushIncremental(url, myOps, warnings, now)

        // 5) 状态
        cachePut(ServerEndpoint.STATE_LAST_GOOD_URL, url)
        cachePut(ServerEndpoint.STATE_LAST_SYNC_AT, now.toString())
        if (peers.isNotEmpty()) cachePut(ServerEndpoint.STATE_LAST_PEERS, peers.joinToString(","))

        val message = when {
            warnings.isNotEmpty() ->
                "已连接「$serverName」，但有部分数据没同步成功：${warnings.joinToString("；")}"
            pulled == 0 && pushed == 0 -> HomeServerProtocol.serverReachableNoData(serverName)
            else -> "已连接「$serverName」：接收 $pulled 条，发送 $pushed 条"
        }
        return HomeSyncResult(
            serverOk = true,
            complete = warnings.isEmpty(),
            peersSeen = peers.size,
            pulled = pulled,
            pushed = pushed,
            message = message,
            serverUrl = url,
            serverName = serverName,
        )
    }

    /**
     * 只推增量：服务端 `GET /ops/{我}/count` 给出它已有多少行，本地缓存给出「我确认推过多少条」，
     * 取两者较小值作为起点（服务端文件被删就自动全量重传）。
     * 每 24 小时强制整份重传一次，防止服务端文件被误删后本地永远不再上传。
     *
     * @return 本次推上去的 op 条数
     */
    private suspend fun pushIncremental(
        url: String,
        myOps: List<SyncOpEntity>,
        warnings: MutableList<String>,
        now: Long,
    ): Int {
        val sentKnown = cacheGet(uploadKey(url))?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val serverCount = runCatching { transport.opsCount(myDeviceId) }.getOrDefault(-1)
        val baseline = (if (serverCount >= 0) minOf(serverCount, sentKnown) else sentKnown)
            .coerceIn(0, myOps.size)
        val lastFullAt = cacheGet(fullUploadKey(url))?.toLongOrNull() ?: 0L
        val forceFull = myOps.isNotEmpty() && now - lastFullAt >= FULL_UPLOAD_INTERVAL_MS
        val toSend = if (forceFull) myOps else myOps.drop(baseline)
        if (toSend.isEmpty()) {
            if (myOps.isNotEmpty()) cachePut(uploadKey(url), myOps.size.toString())
            return 0
        }
        return try {
            val result = transport.postOps(merge.toJournal(toSend))
            if (result.bad > 0) error("服务端未接收 ${result.bad} 行数据，将重试")
            if (forceFull) cachePut(fullUploadKey(url), now.toString())
            // 注意：全量重传后服务端行数会大于本机 op 数，所以这里记「本机 op 总数」而不是行数，
            // 这样下一轮仍然只推新增的 op（不会因为行数变大而漏推）。
            cachePut(uploadKey(url), myOps.size.toString())
            toSend.size
        } catch (t: Throwable) {
            warnings += "上传失败：${HomeServerProtocol.networkMessage(t)}"
            0
        }
    }

    /** 服务器上除我之外的设备：服务端设备列表 + 家庭成员表 + 历史 peer。 */
    private suspend fun discoverPeers(warnings: MutableList<String>): List<String> {
        val out = LinkedHashSet<String>()
        runCatching { transport.devices() }
            .onFailure { warnings += "获取设备列表失败：${HomeServerProtocol.networkMessage(it)}" }
            .getOrNull()
            ?.forEach { out += it.trim() }
        runCatching { knownPeers() }.getOrNull()?.forEach { out += it.trim() }
        return out.filter { it.isNotEmpty() && it != myDeviceId }.distinct()
    }

    private suspend fun cacheGet(key: String): String? = runCatching { state.get(key) }.getOrNull()

    private suspend fun cachePut(key: String, value: String) {
        runCatching { state.put(key, value) }
    }

    companion object {
        /** 强制整份重传的间隔：24 小时。 */
        const val FULL_UPLOAD_INTERVAL_MS = 24 * 60 * 60 * 1000L

        fun uploadKey(serverUrl: String): String = "uploadedCount:${ServerEndpoint.normalize(serverUrl)}"

        fun fullUploadKey(serverUrl: String): String = "lastFullUploadAt:${ServerEndpoint.normalize(serverUrl)}"
    }
}
