package com.family.ledger.ui.settings

import com.family.ledger.core.TimeFmt
import com.family.ledger.sync.SyncStatus

/**
 * 「同步」区块的纯文案逻辑（不依赖 Compose，方便单测与复用）。
 *
 * 设计原则：用户打开设置页，第一眼必须是「自动同步已开启，什么都不用填」。
 *
 * 当前架构（lead 2026-09-26 决定）：家里 NAS 上跑一个常驻同步服务，两台手机都连它 ——
 * 在家自动走局域网地址，在外面自动走公网地址。地址由 App 自己探测并缓存，
 * **用户不需要填任何东西**，也不需要注册账号。WebDAV 只是可选补充，收在高级选项里。
 */
internal data class SyncUiState(
    val running: Boolean = false,
    val lastSyncAt: Long = 0L,
    val message: String = "",
    val pushed: Int = 0,
    val pulled: Int = 0,
    /** 家里的同步服务是否可达：>0 = 可达。 */
    val peersFound: Int = 0,
    /** 同步服务的名字，例如「家里的 NAS」。 */
    val peerName: String? = null,
) {
    /** 同步服务在界面上的叫法；拿不到名字时用中性说法，绝不显示 IP / 设备 ID。 */
    val peerLabel: String
        get() = peerName?.trim()?.takeIf { it.isNotEmpty() } ?: SYNC_SERVER_FALLBACK
}

internal const val SYNC_SERVER_FALLBACK = "家里的同步服务"

/**
 * 仓库层状态 → UI 状态。
 *
 * task-6 已落地 `peersFound`（家里的服务器是否可达）与 `peerName`（服务名，如「家里的 NAS」）。
 */
internal fun SyncStatus.toUiState(): SyncUiState = SyncUiState(
    running = running,
    lastSyncAt = lastSyncAt,
    message = message,
    pushed = pushed,
    pulled = pulled,
    peersFound = peersFound,
    peerName = peerName,
)

internal enum class SyncTone { OK, RUNNING, WARN, IDLE }

internal data class SyncPresentation(
    val primary: String,
    val detail: String? = null,
    val tone: SyncTone = SyncTone.IDLE,
)

/** 连不上家里的同步服务时的说法（对用户来说「家里的服务」比 IP 地址好懂得多）。 */
internal const val SYNC_UNREACHABLE_PRIMARY = "暂时连不上家里的同步服务。"
internal const val SYNC_UNREACHABLE_GUIDE =
    "App 会自动重试。可以检查：① 在家时手机连的是家里 Wi-Fi；② 家里的同步服务（NAS）开着；" +
        "③ 开着 VPN 的话先关掉试试。"

/**
 * 把状态映射成「状态行 + 说明行」。
 *
 * 覆盖：开关关闭 / 正在连接或同步 / 已连上服务 / 还没试过 / 连不上（或报错）。
 */
internal fun syncPresentation(enabled: Boolean, s: SyncUiState, now: Long): SyncPresentation {
    if (!enabled) {
        return SyncPresentation(
            primary = "自动同步已关闭",
            detail = "请先配置自己的同步服务，再打开开关。",
            tone = SyncTone.IDLE,
        )
    }
    if (s.running) {
        return if (s.peersFound > 0) {
            SyncPresentation(
                primary = "正在和「${s.peerLabel}」同步…",
                detail = "正在交换账本数据，请不要关闭本页。",
                tone = SyncTone.RUNNING,
            )
        } else {
            SyncPresentation(
                primary = "正在连接家里的同步服务…",
                detail = "正在连接你配置的自托管服务。",
                tone = SyncTone.RUNNING,
            )
        }
    }
    if (s.peersFound > 0 && s.lastSyncAt > 0L) {
        return SyncPresentation(
            primary = "已连接「${s.peerLabel}」· ${relativeSyncTime(s.lastSyncAt, now)} · " +
                "拉取 ${s.pulled} 条 / 推送 ${s.pushed} 条",
            // 平时只显示时间戳；但「已自动加入家里的账本」这类一次性消息要露出来，
            // 否则用户永远不知道这台手机是怎么跟对方合并成一本账的
            detail = buildString {
                append("上次同步：").append(TimeFmt.toCsv(s.lastSyncAt))
                if (isNoteworthyMessage(s.message)) append("\n").append(s.message)
            },
            tone = SyncTone.OK,
        )
    }
    if (s.peersFound > 0) {
        return SyncPresentation(
            primary = "已连接「${s.peerLabel}」，点「立即同步」马上交换数据",
            tone = SyncTone.OK,
        )
    }
    // 还从来没同步过、也没有任何报错：不要一上来就说「连不上」，
    // 那会让用户以为功能坏了（其实只是还没试过）
    if (s.lastSyncAt == 0L && s.message.isBlank()) {
        return SyncPresentation(
            primary = "等待连接家里的同步服务…",
            detail = "App 会自动连上它；也可以点「立即同步」马上试一次。",
            tone = SyncTone.IDLE,
        )
    }
    // 已经同步成功过、只是这一刻状态里没有服务信息（例如走了 WebDAV）：如实说"同步完成"，不要谎报连上了服务
    if (s.lastSyncAt > 0L && looksLikeSyncDone(s.message)) {
        return SyncPresentation(
            primary = "最近一次同步已完成 · 拉取 ${s.pulled} 条 / 推送 ${s.pushed} 条",
            detail = "上次同步：${TimeFmt.toCsv(s.lastSyncAt)}",
            tone = SyncTone.OK,
        )
    }
    // 需要用户动手才能解决的错误（没初始化、家庭码不一致等）原样显示
    if (mentionsActionNeeded(s.message)) {
        return SyncPresentation(primary = s.message, tone = SyncTone.WARN)
    }
    // ★ 服务其实是通的，只是这次没有新数据要交换 —— 绝不能说成「连不上」。
    // 这个分支必须放在「连不上」之前：`peersFound == 0` 并不等于失败，
    // 服务器可达但没有新账目时也会是 0，早先会把「已连接「家里的 NAS」：当前没有需要同步的新账目」
    // 和「暂时连不上家里的同步服务。」同时显示出来，自相矛盾。
    if (looksLikeServerReached(s.message)) {
        return SyncPresentation(
            primary = "已连接家里的同步服务",
            detail = buildString {
                if (s.lastSyncAt > 0L) {
                    append("上次同步：").append(TimeFmt.toCsv(s.lastSyncAt)).append("\n")
                }
                append(s.message)
            },
            tone = SyncTone.OK,
        )
    }
    // 连不上 / 其他失败：主行给友好说法，详情用仓库层的中文原因（里面带具体排查建议）。
    // WebDAV 相关的提示不进主视图 —— 那是高级选项里的事，不该在首屏吓用户。
    if (s.message.isNotBlank() && !mentionsWebDav(s.message)) {
        return SyncPresentation(
            primary = SYNC_UNREACHABLE_PRIMARY,
            detail = s.message,
            tone = SyncTone.WARN,
        )
    }
    return SyncPresentation(
        primary = SYNC_UNREACHABLE_PRIMARY,
        detail = SYNC_UNREACHABLE_GUIDE,
        tone = SyncTone.WARN,
    )
}

/**
 * 「刚刚同步 / N 分钟前同步 / N 小时前同步 / 某天同步」。
 * 用相对时间是因为用户关心的是「我刚才记的账对方收到了吗」，而不是时间戳。
 */
internal fun relativeSyncTime(at: Long, now: Long = System.currentTimeMillis()): String {
    val delta = now - at
    return when {
        delta < 60_000L -> "刚刚同步"
        delta < 3_600_000L -> "${delta / 60_000L} 分钟前同步"
        delta < 86_400_000L -> "${delta / 3_600_000L} 小时前同步"
        else -> "${TimeFmt.toDay(at)} 同步"
    }
}

private fun looksLikeSyncDone(message: String): Boolean =
    message.contains("完成") || message.contains("成功")

/**
 * 消息是否说明「服务器是通的」。
 *
 * 与 [looksLikeSyncDone] 的区别：同步成功但**没有新数据**时，仓库层给的是一句
 * 「已连接「家里的 NAS」：当前没有需要同步的新账目」—— 它既不含「完成」也不含「成功」，
 * 早先因此掉进「连不上」分支，于是主行说连不上、详情说已连接。
 */
private fun looksLikeServerReached(message: String): Boolean =
    message.contains("已连接") || message.contains("没有需要同步的新账目")

/**
 * 值得在首屏露出来的消息：自动加入账本、家庭码相关（可能需要手动配对）。
 * 其余成功消息（只是条数统计）不用重复展示，主行已经写了拉取/推送条数。
 */
private fun isNoteworthyMessage(message: String): Boolean =
    message.contains("自动加入") || message.contains("家庭码")

/** 这些提示说明用户还得做点什么（初始化 / 配对 / 打开开关），必须原样告诉他。 */
private fun mentionsActionNeeded(message: String): Boolean =
    listOf("未初始化", "初始化", "未开启", "没开启", "家庭码").any { message.contains(it) }

private fun mentionsWebDav(message: String): Boolean =
    message.contains("WebDAV", ignoreCase = true) || message.contains("坚果云")

/**
 * 本机在对方手机上显示的名字。
 * 零配置：没设过就按昵称自动生成「XX 的手机」，对方同步时看到的就是这个名字。
 */
internal fun autoDeviceName(displayName: String): String {
    val base = displayName.trim()
    return if (base.isEmpty()) DEFAULT_DEVICE_NAME else "$base 的手机"
}

internal const val DEFAULT_DEVICE_NAME = "我的手机"

/** 设备名是否还是「跟着昵称自动生成」的状态（此时改昵称应同步更新设备名）。 */
internal fun isAutoDeviceName(name: String, displayName: String): Boolean {
    val current = name.trim()
    return current.isEmpty() || current == DEFAULT_DEVICE_NAME || current == autoDeviceName(displayName)
}
