package com.family.ledger.sync

import android.util.Log
import com.family.ledger.FamilyLedgerApp
import com.family.ledger.data.SettingsStore
import com.family.ledger.data.db.AppDatabase
import com.family.ledger.data.db.entity.SyncOpEntity
import com.family.ledger.data.db.entity.SyncStateEntity
import com.family.ledger.data.sync.SyncJournal
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

data class SyncStatus(
    val running: Boolean = false,
    val lastSyncAt: Long = 0L,
    val message: String = "",
    val pushed: Int = 0,
    val pulled: Int = 0,
    /** 家里的同步服务是否可达：**1 = 可达，0 = 不可达**（界面用它显示「已连接…」）。 */
    val peersFound: Int = 0,
    /** 服务器名，例如「家里的 NAS」。 */
    val peerName: String? = null,
)

data class SyncResult(
    val success: Boolean,
    val pushed: Int = 0,
    val pulled: Int = 0,
    val message: String = "",
)

/**
 * 双端同步入口。
 *
 * 传输有两条，**默认那条完全零配置**：
 *   1. **家里的同步服务器（默认）**：家里的 NAS 常驻在线，地址编译期内置、自动探测，
 *      在家走局域网地址、在外面走公网地址 —— 用户什么都不用填。
 *   2. WebDAV（可选，收在「高级选项」里）：服务器连不上时兜底。
 *
 * 一次同步做的事（走服务器时，见 [HomeServerEngine]）：
 *   1. 依次探测候选地址，谁先 `health` 通就用谁，并缓存到 `sync_state`
 *   2. `GET /ops/{对方deviceId}` 拉对方的日志，交给 [SyncEngine] 按 LWW 合并
 *   3. 把本机 journal 用 `POST /ops` 推上去（只推增量）
 *   4. 写 `settings.lastSyncAt / lastSyncMessage`，更新 [observeStatus]
 *
 * 合并应用远端 op 时**只走 DAO upsert，不经过 SyncJournal**（见 [RoomSyncEntityStore]），
 * 否则两端会互相把对方的 op 再记一遍，无限回环。
 */
class SyncRepository(
    private val db: AppDatabase,
    @Suppress("unused") // 构造签名已冻结。应用远端 op 时故意不使用它，避免回环。
    private val journal: SyncJournal,
    private val settings: SettingsStore,
) {
    private val _status = MutableStateFlow(
        SyncStatus(
            running = false,
            lastSyncAt = settings.lastSyncAt,
            message = settings.lastSyncMessage,
        )
    )
    private val mutex = Mutex()

    /**
     * 同步是否可用。**零配置默认可用**：`lanSyncEnabled` 默认打开，
     * 用户不需要填服务器地址、账号、密码。
     */
    fun isConfigured(): Boolean = !com.family.ledger.BuildConfig.DEMO_MODE && (settings.lanSyncEnabled || isWebDavConfigured())

    /** WebDAV（高级选项）是否配置完整。 */
    fun isWebDavConfigured(): Boolean =
        !com.family.ledger.BuildConfig.DEMO_MODE && settings.syncUrl.isNotBlank() && settings.syncUser.isNotBlank()

    fun observeStatus(): Flow<SyncStatus> = _status.asStateFlow()

    /** 自动同步开关（设置页的「自动同步」总开关）。 */
    fun isEnabled(): Boolean = settings.lanSyncEnabled || settings.syncEnabled

    /**
     * 外部触发（前台服务 / 定时器）因「开关未打开」等原因跳过时，
     * 也要把原因写进 `lastSyncMessage` 并通过 [observeStatus] 发出去。
     * 否则用户点了「立即同步」看不到任何反馈，会以为功能坏了。
     */
    fun reportSkipped(message: String): SyncResult = fail(message)

    /** 距上次同步是否已超过 [minIntervalMs]。 */
    fun isDue(minIntervalMs: Long = DEFAULT_INTERVAL_MS): Boolean {
        val last = maxOf(settings.lastSyncAt, _status.value.lastSyncAt)
        return last <= 0L || System.currentTimeMillis() - last >= minIntervalMs
    }

    /**
     * 「打开 App 自动同步一次」用：只在已开启同步、已初始化、且距上次同步超过
     * [minIntervalMs] 时真正执行；否则返回 null（调用方不需要处理）。
     */
    suspend fun syncIfDue(minIntervalMs: Long = DEFAULT_INTERVAL_MS): SyncResult? {
        if (!settings.identityChosen || !isEnabled()) return null
        if (!isDue(minIntervalMs)) return null
        if (mutex.isLocked) return null
        // 首次安装时「家庭初始化」（MainActivity 里异步跑的 ensureBootstrap）可能还没完成，
        // 这里不直接跳过，交给 syncNowAuto 等一小会儿（见 awaitFamilyReady）。
        return syncNowAuto()
    }

    /**
     * 手动 WebDAV 同步（「高级选项」里的按钮）：忽略开关与间隔，但必须已配置 WebDAV。
     * 普通用户走 [syncNowAuto]，完全不需要这个。
     */
    suspend fun syncNow(): SyncResult {
        if (com.family.ledger.BuildConfig.DEMO_MODE) return fail("演示模式不启用网络同步")
        if (!isWebDavConfigured()) {
            val msg = if (settings.syncUrl.isBlank()) {
                "尚未配置 WebDAV 同步地址：请到「设置 → 同步 → 高级选项」填写坚果云等 WebDAV 目录地址"
            } else {
                "尚未配置 WebDAV 账号：请到「设置 → 同步 → 高级选项」填写账号与应用密码"
            }
            return fail(msg)
        }
        // 没 bootstrap 就没有 familyId，拼不出正确的云端目录（两边会各写各的），直接挡住
        if (familyIdOrNull() == null) {
            return fail("请先完成初始化再同步")
        }
        if (!mutex.tryLock()) {
            return SyncResult(success = false, message = "同步正在进行中，请稍后再试")
        }
        try {
            _status.value = SyncStatus(
                running = true,
                lastSyncAt = settings.lastSyncAt,
                message = "正在同步…",
            )
            return runSync()
        } catch (t: Throwable) {
            val msg = SyncErrors.translate(t)
            Log.w(TAG, "同步失败", t)
            return fail(msg)
        } finally {
            mutex.unlock()
            if (_status.value.running) _status.value = _status.value.copy(running = false)
        }
    }

    /**
     * **自动同步（默认路径）**：家里的服务器优先；服务器不可达且 WebDAV 已配置时回落 WebDAV；
     * 都不行就给一段能照着排查的中文提示（含「在家连家里 Wi-Fi / 外面确认 NAS 在线 / VPN 先关掉」）。
     */
    suspend fun syncNowAuto(): SyncResult {
        if (com.family.ledger.BuildConfig.DEMO_MODE) return fail("演示模式不启用网络同步")
        if (!settings.identityChosen) return fail("请先选择当前使用者")
        if (familyIdOrNull() == null && !awaitFamilyReady()) {
            return fail("账本还在初始化，请稍后重试")
        }
        if (!isEnabled()) return fail("同步未开启：请到「设置 → 同步」打开「自动同步」")
        if (!mutex.tryLock()) {
            return SyncResult(success = false, message = "同步正在进行中，请稍后再试")
        }
        try {
            _status.value = SyncStatus(
                running = true,
                lastSyncAt = settings.lastSyncAt,
                message = "正在同步…",
            )
            // 1) 家里的同步服务器（零配置）
            val home = runCatching { syncHomeLocked() }
                .getOrElse { HomeSyncResult(serverOk = false, message = SyncErrors.translate(it)) }
            if (home.serverOk) return publishHome(home)

            // 2) 服务器连不上时才回落 WebDAV（高级选项里配了才走）
            if (isWebDavConfigured()) {
                val dav = runCatching { runSync() }
                    .getOrElse { fail(SyncErrors.translate(it)) }
                if (dav.success) return dav
                return fail("${home.message}；另外 WebDAV 同步也没成功：${dav.message}")
            }

            // 3) 两条路都不行
            return fail(home.message)
        } catch (t: Throwable) {
            Log.w(TAG, "自动同步失败", t)
            return fail(SyncErrors.translate(t))
        } finally {
            mutex.unlock()
            if (_status.value.running) _status.value = _status.value.copy(running = false)
        }
    }

    // ---------------------------------------------------------------- 家里的同步服务器

    /**
     * 依次探测候选地址（用户覆盖 → 上次成功 → 内置局域网 → 内置公网），谁先通用谁。
     * 局域网排在最前是因为手机在家时走 NAT 回环连公网地址，有些路由器不支持（hairpin）。
     *
     * 连上之后先跑一次**零输入配对握手**（[HomeFamilyHandshake]）：服务器上只认一本账，
     * 第一台手机登记、第二台自动加入 —— 用户一个字符都不用输。
     * 握手可能改变本机家庭码，这时要用新家庭码**重建 transport**（服务器目录跟着家庭码走）再同步 ops。
     */
    private suspend fun syncHomeLocked(): HomeSyncResult {
        val familyId = familyIdOrNull()
            ?: return HomeSyncResult(serverOk = false, message = "请先完成初始化再同步")
        val candidates = ServerEndpoint.candidates(settings.homeServerUrl, cachedServerUrl())
        if (candidates.isEmpty()) {
            return HomeSyncResult(serverOk = false, message = HomeServerProtocol.noServerMessage(emptyList()))
        }
        val reasons = ArrayList<String>(candidates.size)
        for (url in candidates) {
            // /health 只校验 token（客户端此刻可能还没有对方的家庭码），先探活
            val probe = HomeServerTransport.forUrl(url, familyId, ServerEndpoint.token(settings.homeServerToken))
            if (!probe.ping()) {
                val reason = probe.lastError ?: "未知原因"
                reasons += "${ServerEndpoint.label(url)}（$url）：$reason"
                Log.i(TAG, "同步服务器候选不可用：$url → $reason")
                continue
            }
            val handshake = runCatching { HomeFamilyHandshake(homeFamilyBridge()).run(probe, familyId) }
                .getOrElse { HomeFamilyHandshake.Result(familyId) }
            if (handshake.blocked) {
                // 服务器可达，但本机已有数据、家庭码又不一致：绝不自动改，交给用户决定
                return HomeSyncResult(
                    serverOk = true,
                    complete = false,
                    message = handshake.message.ifBlank { HomeServerProtocol.familyJoinFailedMessage() },
                    serverUrl = url,
                )
            }
            val effectiveFamily = handshake.familyId?.takeIf { it.isNotBlank() } ?: familyId
            val transport = if (effectiveFamily == familyId) {
                probe
            } else {
                HomeServerTransport.forUrl(url, effectiveFamily, ServerEndpoint.token(settings.homeServerToken))
            }
            val engine = HomeServerEngine(
                transport = transport,
                merge = SyncEngine(RoomSyncEntityStore(db)),
                myDeviceId = settings.deviceId,
                state = roomHomeState(),
                localOps = { ownOps() },
                knownPeers = { knownPeerDeviceIds() },
            )
            val result = engine.sync()
            if (result.serverOk) {
                FamilyLedgerApp.containerOrNull()?.family?.ensureBootstrap()
                val prefix = if (handshake.adopted) "${handshake.message}；" else ""
                val attachments = runCatching { syncAttachments(transport, effectiveFamily) }
                return if (attachments.isSuccess) result.copy(message = prefix + result.message)
                else result.copy(complete = false, message = prefix + result.message + "；" +
                    (attachments.exceptionOrNull()?.message ?: "截图等待同步"))
            }
            reasons += "${ServerEndpoint.label(url)}（$url）：${result.message}"
            Log.i(TAG, "同步服务器候选不可用：$url → ${result.message}")
        }
        return HomeSyncResult(serverOk = false, message = HomeServerProtocol.noServerMessage(reasons))
    }

    /**
     * 零输入配对用到的本机能力：数本机真实流水 + 调 [com.family.ledger.data.repo.FamilyRepository.adoptFamilyCode]。
     *
     * 容器通过 [FamilyLedgerApp.containerOrNull] 取（和 [SyncService] 的做法一致），
     * 这样不用改已经冻结的 [SyncRepository] 构造签名；单测里走 [HomeFamilyHandshake] 的假 bridge。
     */
    private fun homeFamilyBridge(): HomeFamilyBridge = object : HomeFamilyBridge {

        override suspend fun localTxnCount(): Int = runCatching { db.txnDao().count() }.getOrDefault(0)

        override suspend fun adoptFamily(familyId: String): Boolean {
            val family = runCatching { FamilyLedgerApp.containerOrNull()?.family }.getOrNull() ?: return false
            val adopted = runCatching { family.adoptFamilyCode(familyId) }.getOrNull() ?: return false
            return adopted.familyId.equals(familyId.trim(), ignoreCase = true)
        }
    }

    private suspend fun syncAttachments(transport: HomeServerTransport, familyId: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val files = com.family.ledger.attachments.ReceiptFiles(FamilyLedgerApp.get())
        val references = db.txnDao().all().filter { !it.deleted }.flatMap {
            com.family.ledger.attachments.ReceiptReference.fromPaths(it.imagePaths)
        }.distinct()
        for (reference in references) {
            val file = requireNotNull(files.file(reference))
            val key = "attachmentUploaded:$familyId:${com.family.ledger.attachments.ReceiptReference.hash(reference)}"
            val now = System.currentTimeMillis()
            if (!file.exists()) {
                files.put(reference, transport.getAttachment(reference))
                db.syncDao().putState(SyncStateEntity(key, now.toString(), now))
            } else {
                val uploadedAt = db.syncDao().getState(key)?.toLongOrNull() ?: 0L
                if (now - uploadedAt > FULL_UPLOAD_INTERVAL_MS) {
                    transport.putAttachment(reference, file.readBytes())
                    db.syncDao().putState(SyncStateEntity(key, now.toString(), now))
                }
            }
        }
    }

    private suspend fun cachedServerUrl(): String? =
        runCatching { db.syncDao().getState(ServerEndpoint.STATE_LAST_GOOD_URL) }.getOrNull()

    /** [HomeSyncState] 的 Room 实现：`sync_state` 表。 */
    private fun roomHomeState(): HomeSyncState = object : HomeSyncState {
        override suspend fun get(key: String): String? = db.syncDao().getState(key)

        override suspend fun put(key: String, value: String) {
            db.syncDao().putState(SyncStateEntity(key, value, System.currentTimeMillis()))
        }
    }

    /** 本机（当前 deviceId）的全部 op，按 seq 升序 —— 推给服务器的就是这些。 */
    private suspend fun ownOps(): List<SyncOpEntity> {
        val deviceId = settings.deviceId
        return db.syncDao().allOps().filter { it.deviceId == deviceId }.sortedBy { it.seq }
    }

    /**
     * 已知的其它设备 deviceId：家庭成员表（配对后会同步过来）+ 历史同步记录。
     * 服务器自己的 `/devices` 是第三路来源（见 [HomeServerEngine.discoverPeers]）。
     */
    private suspend fun knownPeerDeviceIds(): List<String> {
        val deviceId = settings.deviceId
        val peers = LinkedHashSet<String>()
        runCatching { db.deviceDao().all() }
            .getOrNull()
            ?.forEach { m -> m.id.trim().takeIf { it.isNotEmpty() && it != deviceId }?.let { peers += it } }
        listOf(KEY_PEERS, ServerEndpoint.STATE_LAST_PEERS).forEach { key ->
            runCatching { db.syncDao().getState(key) }
                .getOrNull()
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() && it != deviceId }
                ?.let { peers += it }
        }
        return peers.toList()
    }

    /**
     * 等家庭初始化完成。
     *
     * 首启时 `MainActivity` 是**异步**跑 `ensureBootstrap()` 的，而「回到前台自动同步」
     * 几乎同时触发 —— 不等的话，用户装完 App 第一次打开会「什么都没发生」，
     * 必须退出重进才会同步。这里最多等 [timeoutMs]，之后按失败处理（不会 ANR：全程挂起）。
     */
    private suspend fun awaitFamilyReady(timeoutMs: Long = 5000L, pollMs: Long = 250L): Boolean {
        if (familyIdOrNull() != null) return true
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(pollMs)
            if (familyIdOrNull() != null) return true
        }
        return false
    }

    private fun publishHome(home: HomeSyncResult): SyncResult {
        val now = System.currentTimeMillis()
        if (home.complete) settings.lastSyncAt = now
        settings.lastSyncMessage = home.message
        _status.value = SyncStatus(
            running = false,
            lastSyncAt = settings.lastSyncAt,
            message = home.message,
            pushed = home.pushed,
            pulled = home.pulled,
            peersFound = 1,
            peerName = home.serverName,
        )
        Log.i(TAG, "服务器同步完成：${home.message}（${home.serverUrl}）")
        return SyncResult(success = home.complete, pushed = home.pushed, pulled = home.pulled, message = home.message)
    }

    // ---------------------------------------------------------------- WebDAV（可选）

    private suspend fun runSync(): SyncResult {
        val deviceId = settings.deviceId
        val familyId = familyIdOrNull() ?: return fail("请先完成初始化再同步")
        val transport = WebDavTransport(settings.syncUrl, settings.syncUser, settings.syncPassword)
        val baseDir = SyncPaths.baseDir(settings.syncUrl)
        val familyDir = SyncPaths.familyDir(settings.syncUrl, familyId)

        transport.ensureCollections(listOf(baseDir, familyDir))

        val engine = SyncEngine(RoomSyncEntityStore(db))

        // 1) 上传自己的设备日志
        var pushed = push(transport, familyDir, deviceId)
        // 2) 拉取并合并对方的日志
        val pulled = pull(transport, familyDir, deviceId, engine)
        // 3) 闭环：同步过程中本机可能又记了新账，再补推一次
        pushed += push(transport, familyDir, deviceId)

        val now = System.currentTimeMillis()
        val msg = "同步完成：上传 $pushed 条，接收 $pulled 条"
        settings.lastSyncAt = now
        settings.lastSyncMessage = msg
        _status.value = SyncStatus(
            running = false,
            lastSyncAt = now,
            message = msg,
            pushed = pushed,
            pulled = pulled,
            peersFound = 1,
            peerName = "WebDAV",
        )
        Log.i(TAG, "$msg（家庭 $familyId，设备 $deviceId）")
        return SyncResult(success = true, pushed = pushed, pulled = pulled, message = msg)
    }

    /** 上传本机 oplog。返回本次新推上去的 op 条数（没有新数据时返回 0）。 */
    private suspend fun push(transport: WebDavTransport, familyDir: String, deviceId: String): Int {
        val dao = db.syncDao()
        val ops = dao.allOps().filter { it.deviceId == deviceId }.sortedBy { it.seq }
        if (ops.isEmpty()) return 0

        val uploadedSeq = dao.getState(KEY_UPLOADED_SEQ)?.toLongOrNull() ?: 0L
        val fresh = ops.count { it.seq > uploadedSeq }
        val lastFullAt = dao.getState(KEY_LAST_FULL_UPLOAD)?.toLongOrNull() ?: 0L
        val now = System.currentTimeMillis()
        // 平时只推增量；每 24h 强制整份重传一次，防止云端文件被误删/清空后本地永远不再上传
        val forceFull = now - lastFullAt >= FULL_UPLOAD_INTERVAL_MS
        if (fresh == 0 && !forceFull) return 0

        val body = SyncEngine.journalText(ops)
        val url = "$familyDir/${SyncPaths.fileName(deviceId)}"
        transport.putText(url, body)
        dao.putState(SyncStateEntity(KEY_UPLOADED_SEQ, ops.last().seq.toString(), now))
        dao.putState(SyncStateEntity(KEY_LAST_FULL_UPLOAD, now.toString(), now))
        Log.i(TAG, "已上传 $url（新增 $fresh 条 / 共 ${ops.size} 条）")
        return fresh
    }

    /** 下载所有已知设备的日志并合并。返回实体更新条数。 */
    private suspend fun pull(
        transport: WebDavTransport,
        familyDir: String,
        deviceId: String,
        engine: SyncEngine,
    ): Int {
        var applied = 0
        for (peer in peerDeviceIds(transport, familyDir, deviceId)) {
            val text = transport.getText("$familyDir/${SyncPaths.fileName(peer)}") ?: continue
            val ops = engine.parseJournal(text).filter { it.deviceId == peer }
            if (ops.isEmpty()) continue
            val appliedHere = engine.applyOps(ops)
            applied += appliedHere
            rememberPeer(peer)
            Log.i(TAG, "已合并 $peer 的日志（${ops.size} 条 op，更新 $appliedHere 个实体）")
        }
        return applied
    }

    /**
     * 对方设备 ID 的来源（按可靠性排序）：
     *   1. 家庭成员表里登记的 deviceId（配对后会同步过来）
     *   2. 之前同步见过的设备（记在 sync_state 里）
     *   3. PROPFIND 列目录得到的 `*.jsonl`（首次同步就靠它发现对方；服务器禁用 PROPFIND 时为空）
     */
    private suspend fun peerDeviceIds(
        transport: WebDavTransport,
        familyDir: String,
        deviceId: String,
    ): List<String> {
        val peers = LinkedHashSet<String>(knownPeerDeviceIds())
        runCatching { transport.listFileNames(familyDir) }
            .getOrNull()
            ?.mapNotNull { SyncPaths.deviceIdOfFileName(it) }
            ?.filter { it != deviceId }
            ?.let { peers += it }
        return peers.toList()
    }

    private suspend fun rememberPeer(peer: String) {
        val dao = db.syncDao()
        val known = dao.getState(KEY_PEERS)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        if (peer in known) return
        val merged = (known + peer).distinct().joinToString(",")
        dao.putState(SyncStateEntity(KEY_PEERS, merged, System.currentTimeMillis()))
    }

    /** 已 bootstrap 的家庭 id；还没初始化时返回 null（此时不允许同步）。 */
    private fun familyIdOrNull(): String? =
        settings.familyId?.trim()?.takeIf { it.isNotEmpty() }

    private fun fail(message: String): SyncResult {
        settings.lastSyncMessage = message
        _status.value = SyncStatus(
            running = false,
            lastSyncAt = settings.lastSyncAt,
            message = message,
            peersFound = 0,
            peerName = null,
        )
        return SyncResult(success = false, message = message)
    }

    companion object {
        private const val TAG = "FamilyLedgerSync"

        /**
         * **回到前台**自动同步的去抖间隔：30 秒。
         *
         * 为什么要这么短：这是夫妻两人共用一本账，「我在外面记一笔、你在家打开就想看到」
         * 是核心场景。原先前后台都用 15 分钟，导致 A 手机记完账、立刻打开 B 手机，
         * B 要等最多 15 分钟才同步 —— 体感就是「同步坏了」。
         * 30 秒足够挡住「反复切前后台」造成的重复请求，又基本做到「打开就是最新的」。
         */
        const val FOREGROUND_INTERVAL_MS = 30 * 1000L

        /** 后台闹钟间隔：15 分钟（`setInexactRepeating` 的下限，也是省电与及时性的平衡）。 */
        const val DEFAULT_INTERVAL_MS = 15 * 60 * 1000L

        private const val FULL_UPLOAD_INTERVAL_MS = 24 * 60 * 60 * 1000L

        private const val KEY_UPLOADED_SEQ = "uploadedSeq"
        private const val KEY_LAST_FULL_UPLOAD = "lastFullUploadAt"
        private const val KEY_PEERS = "peerDevices"
    }
}
