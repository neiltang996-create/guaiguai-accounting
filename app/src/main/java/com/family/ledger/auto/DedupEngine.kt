package com.family.ledger.auto

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * 自动记账 · 去重引擎。
 *
 * **纯 Kotlin、零 Android 依赖**（只用 `java.security` / `java.time`），必须能在 JVM 单测里跑。
 *
 * 判定顺序（对齐成熟自动记账项目的分层设计，全部本地可解释、可观测）：
 *
 *  1. `TEXT_HASH_5MIN`   同一通知文本 5 分钟内重复出现 → **丢弃**（内存表，进程内，确实是同一条通知被重复回调）；
 *  2. `ORDER_ID`         交易订单号指纹已存在 → **丢弃**（最强依据：同一个订单号就是同一笔支付）；
 *  3. `PENDING_FP`       待确认账单匹配，且判定为「同一次支付的双通道上报」或「明确时间和原文均相同的详情页」→ **丢弃**（避免每笔支付弹两次）；
 *  4. `fingerprint-exact` 流水指纹精确命中 → **不丢弃**，建单但标记「可能已记过」；
 *  5. `PENDING_FP_TAKEN` 指纹已被更早的账单占用、但不能证明是同一次支付 → **不丢弃**，建单并提示；
 *  6. `SAME_DAY_AMOUNT_2MIN` / `CROSS_SOURCE_2MIN`
 *        同一天、金额一致、时间差 ≤ 2 分钟，但无法证明是两笔独立消费
 *        → **仍然建单，但标记为「可能已记过」并提示用户**。
 *
 * **产品底线：绝不静默丢弃用户的钱。** 对同一通知事件、同一订单号、同一明确时间的详情页或同一支付双通道上报等
 * 可以确定为同一笔的情况进行合并，其余一律建单 + 提示，让用户点「记入」或「忽略」自己决定
 * ——「同一商户、同一金额、2 分钟内两笔真实消费」（两个人各自扫码）是真实存在的。
 *
 * 第 6 层吸收了两条互相制衡的经验规则：
 *  - 「渠道不同且都非空 → 不判重」：同一商户的合理重复消费（如先用微信零钱、再用招行卡各买一杯）；
 *  - 「同金额同时间但来源不同 → 判重」：一笔消费同时来了「微信支付通知」和「银行扣款通知」。
 */
object DedupEngine {

    /** 指纹时间桶：120 秒。 */
    const val BUCKET_MS = 120_000L

    /** 「可能重复」的时间窗：2 分钟。 */
    const val DUPLICATE_WINDOW_MS = 120_000L

    /** 通知文本去重窗口：5 分钟。 */
    const val TEXT_WINDOW_MS = 300_000L

    /**
     * 「同一次支付的双通道上报」窗口：60 秒。
     * 无障碍与通知监听看到同一笔支付的时间差通常只有几百毫秒到几秒。
     */
    const val DOUBLE_SOURCE_WINDOW_MS = 60_000L

    /** 金额精度：分为单位，允许 1 分误差（对齐「|Δ金额| < 0.01 元」的判定）。 */
    const val AMOUNT_TOLERANCE_CENTS = 1L

    enum class Mode(val wire: String) {
        NEW("NEW"),
        TEXT_HASH("TEXT_HASH_5MIN"),
        ORDER_ID("ORDER_ID"),

        /** 已有记录确认属于同一次支付或同一明确时间的详情页 → 丢弃。 */
        PENDING_FP("PENDING_FP"),

        /** 流水指纹精确命中 → 不丢弃，提示「可能已记过」。 */
        FINGERPRINT_EXACT("fingerprint-exact"),

        /** 指纹已被更早的账单占用，但无法证明是同一次支付 → 不丢弃，提示。 */
        PENDING_FP_TAKEN("PENDING_FP_TAKEN"),
        SAME_DAY_AMOUNT("SAME_DAY_AMOUNT_2MIN"),
        CROSS_SOURCE("CROSS_SOURCE_2MIN"),
    }

    enum class Action {
        /** 新建待确认账单。 */
        CREATE,

        /** 新建待确认账单，但标记「可能已记过」并提示用户。 */
        CREATE_FLAGGED,

        /** 直接丢弃，不建单。 */
        DROP,
    }

    data class Result(
        val action: Action,
        val mode: Mode,
        /** 触发判定的已存在记录 id（流水或待确认账单），用于日志追溯。 */
        val relatedId: String? = null,
    ) {
        val duplicatePrompt: Boolean get() = action == Action.CREATE_FLAGGED
    }

    /** 新信号（已归一化）。 */
    data class Candidate(
        val sourcePackage: String,
        val amountCents: Long,
        val merchant: String?,
        val channelLabel: String?,
        val orderId: String?,
        val occurredAt: Long,
        val assetId: String? = null,
        /** 主指纹（有订单号时就是订单号指纹）。 */
        val fingerprint: String = "",
        /** 订单号指纹，拿不到订单号时为 null。 */
        val orderFingerprint: String? = null,
    )

    /** 已存在的一笔（流水 / 待确认账单）。 */
    data class Existing(
        val id: String? = null,
        val sourcePackage: String? = null,
        val amountCents: Long,
        val merchant: String? = null,
        val channelLabel: String? = null,
        val occurredAt: Long,
        val assetId: String? = null,
        val orderId: String? = null,
    )

    data class Facts(
        val candidate: Candidate,
        /** 相同文本在 [TextHashCache] 里 5 分钟内出现过（同一条通知被重复回调）。 */
        val contentHashSeen: Boolean = false,
        /** 订单号指纹已在流水/待确认账单里出现（同一个订单号 = 同一笔支付）。 */
        val orderFingerprintHit: Boolean = false,
        /**
         * 待确认账单指纹命中，且已确认是**同一次支付的双通道上报**
         * （来源通道不同 + [DOUBLE_SOURCE_WINDOW_MS] 内）→ 可以静默合并。
         */
        val pendingFingerprintHit: Boolean = false,
        /** 流水指纹精确命中 → 建单但提示，不丢弃。 */
        val txnFingerprintHit: Boolean = false,
        /** 指纹已被更早的账单占用，但无法证明是同一次支付 → 建单但提示，不丢弃。 */
        val pendingFingerprintTaken: Boolean = false,
        /** 候选对照集：同资产/同金额附近的流水与待确认账单。 */
        val recent: List<Existing> = emptyList(),
    )

    fun decide(facts: Facts): Result {
        if (facts.contentHashSeen) return Result(Action.DROP, Mode.TEXT_HASH)
        if (facts.candidate.orderId != null && facts.orderFingerprintHit) {
            return Result(Action.DROP, Mode.ORDER_ID)
        }
        // 同一次支付的双通道上报：静默合并，否则每笔支付都会弹两次提醒
        if (facts.pendingFingerprintHit) return Result(Action.DROP, Mode.PENDING_FP)

        // 以下都不再丢弃：建单 + 标记「可能已记过」，由用户决定记入还是忽略
        if (facts.txnFingerprintHit) return Result(Action.CREATE_FLAGGED, Mode.FINGERPRINT_EXACT)
        if (facts.pendingFingerprintTaken) return Result(Action.CREATE_FLAGGED, Mode.PENDING_FP_TAKEN)

        val dup = facts.recent.firstOrNull { isLikelySameConsumption(facts.candidate, it) }
        if (dup != null) {
            val crossSource = dup.sourcePackage != null && dup.sourcePackage != facts.candidate.sourcePackage
            return Result(
                action = Action.CREATE_FLAGGED,
                mode = if (crossSource) Mode.CROSS_SOURCE else Mode.SAME_DAY_AMOUNT,
                relatedId = dup.id,
            )
        }
        return Result(Action.CREATE, Mode.NEW)
    }

    /** 是否为「同一笔消费」的可疑重复。 */
    private fun isLikelySameConsumption(c: Candidate, e: Existing): Boolean {
        if (abs(c.amountCents - e.amountCents) > AMOUNT_TOLERANCE_CENTS) return false
        if (abs(c.occurredAt - e.occurredAt) > DUPLICATE_WINDOW_MS) return false
        if (!sameLocalDay(c.occurredAt, e.occurredAt)) return false
        // 已知资产不同 → 两笔不同消费
        if (c.assetId != null && e.assetId != null && c.assetId != e.assetId) return false
        // 商户都已知且不同 → 两笔不同消费
        if (!c.merchant.isNullOrBlank() && !e.merchant.isNullOrBlank() && c.merchant != e.merchant) return false

        val cChannel = c.channelLabel?.takeIf { it.isNotBlank() }
        val eChannel = e.channelLabel?.takeIf { it.isNotBlank() }
        if (cChannel != null && eChannel != null && cChannel != eChannel) {
            // 渠道不同且都非空：同一来源 → 认为是两笔合理消费；跨来源 → 判为同一笔消费的两条通知
            val crossSource = e.sourcePackage != null && e.sourcePackage != c.sourcePackage
            if (!crossSource) return false
        }
        return true
    }

    // ---------- 指纹 ----------

    /**
     * 主指纹：有订单号用订单号（稳定唯一），否则用「包名 + 金额 + 归一化商户 + 2 分钟时间桶」。
     *
     * 刻意不使用原始文本：**同一笔支付会同时被无障碍和通知监听看到**，两条文本必然不同，
     * 只有「包名+金额+商户+时间桶」才能把它们收敛成同一条待确认账单。
     */
    fun fingerprint(
        pkg: String?,
        amountCents: Long,
        merchant: String?,
        at: Long,
        orderId: String? = null,
    ): String {
        if (!orderId.isNullOrBlank()) return orderFingerprint(orderId)
        return sha256Hex("bill|${pkg.orEmpty().lowercase()}|$amountCents|${normalizeMerchant(merchant)}|${at / BUCKET_MS}")
    }

    fun orderFingerprint(orderId: String): String = sha256Hex("order|${orderId.trim()}")

    /**
     * 相邻时间桶的指纹候选（前/当前/后）。
     * 桶边界会切断「2 分钟内」的判定，查三个桶才能稳定判重。
     */
    fun fingerprintCandidates(pkg: String?, amountCents: Long, merchant: String?, at: Long): List<String> {
        val key = "${pkg.orEmpty().lowercase()}|$amountCents|${normalizeMerchant(merchant)}"
        val bucket = at / BUCKET_MS
        return listOf(bucket - 1, bucket, bucket + 1).map { sha256Hex("bill|$key|$it") }
    }

    /** 通知文本指纹（内存表用）。 */
    fun textHash(text: String): String = sha256Hex("text|${normalizeText(text)}")

    /** 归一化商户：与 `LedgerRepository.normalizeMerchant` 保持一致的口径（本类不能依赖 Android）。 */
    fun normalizeMerchant(raw: String?): String {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return ""
        val cleaned = s
            .replace(Regex("[（(].*?[)）]"), "")
            .replace(Regex("\\s+"), "")
            .replace(Regex("[0-9]{4,}"), "")
            .replace("有限公司", "")
            .replace("有限责任公司", "")
            .trim()
        return cleaned.ifEmpty { s }.take(24)
    }

    private fun normalizeText(text: String): String =
        text.replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim().take(PaymentTextParser.MAX_TEXT)

    private fun sameLocalDay(a: Long, b: Long): Boolean {
        val zone = ZoneId.systemDefault()
        return Instant.ofEpochMilli(a).atZone(zone).toLocalDate() ==
            Instant.ofEpochMilli(b).atZone(zone).toLocalDate()
    }

    fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    /**
     * 通知文本内存去重表（窗口默认 5 分钟）。
     *
     * 进程内、有界（按时间窗清理），不做持久化：重启后最多多识别一次，
     * 后面几层指纹去重会兜住，不会产生重复账单。
     */
    class TextHashCache(
        private val windowMs: Long = TEXT_WINDOW_MS,
        private val maxSize: Int = 256,
        private val clock: () -> Long = System::currentTimeMillis,
    ) {
        private val seen = HashMap<String, Long>()

        /** 记录并返回「窗口内是否已出现过」。 */
        @Synchronized
        fun markAndCheck(hash: String): Boolean {
            val now = clock()
            prune(now)
            val last = seen[hash]
            seen[hash] = now
            if (seen.size > maxSize) {
                seen.entries.sortedByDescending { it.value }.drop(maxSize).forEach { seen.remove(it.key) }
            }
            return last != null && now - last <= windowMs
        }

        @Synchronized
        fun prune(now: Long = clock()) {
            if (seen.isEmpty()) return
            val it = seen.entries.iterator()
            while (it.hasNext()) {
                if (now - it.next().value > windowMs) it.remove()
            }
        }

        @Synchronized
        fun size(): Int = seen.size

        @Synchronized
        fun clear() = seen.clear()
    }
}
