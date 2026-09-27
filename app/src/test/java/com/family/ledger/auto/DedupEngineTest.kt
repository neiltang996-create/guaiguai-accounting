package com.family.ledger.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * `DedupEngine` 单测：三层去重 + 渠道/来源规则 + 时间桶边界。
 *
 * 引擎纯 Kotlin（只用 java.security / java.time），全部跑在 JVM 上。
 * 基准时间取「本地时间今天 10:00」，避免测试在跨零点/跨时区时抖动。
 */
class DedupEngineTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val base: Long = LocalDate.now().atTime(10, 0).atZone(zone).toInstant().toEpochMilli()

    // ---------- 第一/二/三层：指纹判重 ----------

    @Test
    fun `同金额不同商户不误判`() {
        val fpMeituan = DedupEngine.fingerprint(PayPackages.ALIPAY, 4500, "美团", base)
        val fpStarbucks = DedupEngine.fingerprint(PayPackages.ALIPAY, 4500, "星巴克", base)
        assertNotEquals(fpMeituan, fpStarbucks)

        val r = DedupEngine.decide(
            DedupEngine.Facts(
                candidate = candidate(merchant = "星巴克"),
                recent = listOf(existing(merchant = "美团", at = base)),
            )
        )
        assertEquals(DedupEngine.Action.CREATE, r.action)
        assertEquals(DedupEngine.Mode.NEW, r.mode)
    }

    @Test
    fun `同商户同金额两分钟内判重`() {
        val r = DedupEngine.decide(
            DedupEngine.Facts(
                candidate = candidate(at = base + 90_000),
                recent = listOf(existing(at = base)),
            )
        )
        assertEquals(DedupEngine.Action.CREATE_FLAGGED, r.action)
        assertEquals(DedupEngine.Mode.SAME_DAY_AMOUNT, r.mode)
        assertTrue(r.duplicatePrompt)
    }

    @Test
    fun `两条都是自动生成时只提示不丢弃`() {
        val r = DedupEngine.decide(
            DedupEngine.Facts(
                candidate = candidate(at = base + 30_000),
                recent = listOf(existing(at = base)),
            )
        )
        assertNotEquals(DedupEngine.Action.DROP, r.action)
        assertTrue(r.duplicatePrompt)
    }

    @Test
    fun `超时后不再判重`() {
        val r = DedupEngine.decide(
            DedupEngine.Facts(
                candidate = candidate(at = base + 600_000),
                recent = listOf(existing(at = base)),
            )
        )
        assertEquals(DedupEngine.Action.CREATE, r.action)

        val a = DedupEngine.fingerprintCandidates(PayPackages.ALIPAY, 4500, "美团", base)
        val b = DedupEngine.fingerprintCandidates(PayPackages.ALIPAY, 4500, "美团", base + 600_000)
        assertTrue(a.intersect(b.toSet()).isEmpty())
    }

    @Test
    fun `跨时间桶边界仍能判重`() {
        val t1 = base - (base % DedupEngine.BUCKET_MS) - 1
        val t2 = t1 + 90_000
        val overlap = DedupEngine.fingerprintCandidates(PayPackages.ALIPAY, 4500, "美团", t1)
            .intersect(DedupEngine.fingerprintCandidates(PayPackages.ALIPAY, 4500, "美团", t2).toSet())
        assertTrue(overlap.isNotEmpty())

        // 指纹命中且判定为同一次支付（双通道上报）→ 丢弃
        val r = DedupEngine.decide(
            DedupEngine.Facts(
                candidate = candidate(at = t2),
                pendingFingerprintHit = true,
            )
        )
        assertEquals(DedupEngine.Action.DROP, r.action)
        assertEquals(DedupEngine.Mode.PENDING_FP, r.mode)
    }

    @Test
    fun `同一笔支付被无障碍与通知同时看到只建一条`() {
        // 两条文本不同，但包名/金额/商户/时间桶一致 → 指纹相同
        val fpA11y = DedupEngine.fingerprint(PayPackages.ALIPAY, 4500, "美团", base)
        val fpNotice = DedupEngine.fingerprint(PayPackages.ALIPAY, 4500, "美团", base + 3_000)
        assertEquals(fpA11y, fpNotice)

        val r = DedupEngine.decide(DedupEngine.Facts(candidate = candidate(), pendingFingerprintHit = true))
        assertEquals(DedupEngine.Action.DROP, r.action)
    }

    @Test
    fun `第三层指纹命中不再丢弃而是提示`() {
        val r = DedupEngine.decide(DedupEngine.Facts(candidate = candidate(), txnFingerprintHit = true))
        assertNotEquals(
            "同一商户同金额的第二笔真实消费绝不能静默消失",
            DedupEngine.Action.DROP,
            r.action,
        )
        assertEquals(DedupEngine.Action.CREATE_FLAGGED, r.action)
        assertEquals(DedupEngine.Mode.FINGERPRINT_EXACT, r.mode)
        assertTrue(r.duplicatePrompt)
        assertEquals("fingerprint-exact", r.mode.wire)
    }

    @Test
    fun `同金额同商户两分钟内第二笔不会消失`() {
        // 第一笔已确认（流水指纹命中）→ 第二笔必须建单并提示，而不是被丢掉
        val flaggedByTxn = DedupEngine.decide(
            DedupEngine.Facts(candidate = candidate(at = base + 30_000), txnFingerprintHit = true)
        )
        assertNotEquals(DedupEngine.Action.DROP, flaggedByTxn.action)

        // 第一笔还挂在待确认里（指纹已被占用）→ 第二笔同样必须建单并提示
        val flaggedByPending = DedupEngine.decide(
            DedupEngine.Facts(candidate = candidate(at = base + 30_000), pendingFingerprintTaken = true)
        )
        assertNotEquals(DedupEngine.Action.DROP, flaggedByPending.action)
        assertEquals(DedupEngine.Action.CREATE_FLAGGED, flaggedByPending.action)
        assertEquals(DedupEngine.Mode.PENDING_FP_TAKEN, flaggedByPending.mode)
    }

    @Test
    fun `同一次支付的双通道上报才允许静默合并`() {
        val r = DedupEngine.decide(DedupEngine.Facts(candidate = candidate(), pendingFingerprintHit = true))
        assertEquals(DedupEngine.Action.DROP, r.action)
        assertEquals(DedupEngine.Mode.PENDING_FP, r.mode)
    }

    // ---------- 订单号 ----------

    @Test
    fun `订单号指纹优先于金额时间启发式`() {
        val orderId = "9000000000000000000000000010"
        val fp = DedupEngine.fingerprint(PayPackages.WECHAT, 4500, "美团", base, orderId)
        assertEquals(DedupEngine.orderFingerprint(orderId), fp)
        assertNotEquals(DedupEngine.fingerprint(PayPackages.WECHAT, 4500, "美团", base), fp)
        // 时间不同但订单号相同 → 指纹仍相同（订单号是最强依据）
        assertEquals(fp, DedupEngine.fingerprint(PayPackages.WECHAT, 4500, "美团", base + 3_600_000, orderId))
    }

    @Test
    fun `订单号命中直接丢弃`() {
        val orderId = "9000000000000000000000000010"
        val r = DedupEngine.decide(
            DedupEngine.Facts(
                candidate = candidate(orderId = orderId),
                orderFingerprintHit = true,
            )
        )
        assertEquals(DedupEngine.Action.DROP, r.action)
        assertEquals(DedupEngine.Mode.ORDER_ID, r.mode)
    }

    @Test
    fun `无订单号时订单号命中不生效`() {
        val r = DedupEngine.decide(
            DedupEngine.Facts(candidate = candidate(orderId = null), orderFingerprintHit = true)
        )
        assertEquals(DedupEngine.Action.CREATE, r.action)
    }

    // ---------- 通知文本内存去重 ----------

    @Test
    fun `同文本五分钟后不再算重复`() {
        var now = base
        val cache = DedupEngine.TextHashCache(clock = { now })
        val hash = DedupEngine.textHash("付款成功 ¥45.00")

        assertFalse(cache.markAndCheck(hash))
        now += 60_000
        assertTrue(cache.markAndCheck(hash))
        now += 6 * 60_000
        assertFalse("超出 5 分钟窗口后应重新放行", cache.markAndCheck(hash))
    }

    @Test
    fun `不同文本互不影响`() {
        var now = base
        val cache = DedupEngine.TextHashCache(clock = { now })
        assertFalse(cache.markAndCheck(DedupEngine.textHash("付款成功 ¥45.00")))
        assertFalse(cache.markAndCheck(DedupEngine.textHash("付款成功 ¥46.00")))
        assertEquals(2, cache.size())
    }

    @Test
    fun `内容hash命中直接丢弃`() {
        val r = DedupEngine.decide(DedupEngine.Facts(candidate = candidate(), contentHashSeen = true))
        assertEquals(DedupEngine.Action.DROP, r.action)
        assertEquals(DedupEngine.Mode.TEXT_HASH, r.mode)
    }

    // ---------- 渠道 / 来源 规则 ----------

    @Test
    fun `同来源渠道不同视为两笔合理消费`() {
        val r = DedupEngine.decide(
            DedupEngine.Facts(
                candidate = candidate(sourcePackage = PayPackages.WECHAT, channelLabel = "微信零钱"),
                recent = listOf(
                    existing(
                        sourcePackage = PayPackages.WECHAT,
                        channelLabel = "招商银行(8806)",
                        at = base,
                    )
                ),
            )
        )
        assertEquals("同一商户用不同渠道各付一次，不应判重", DedupEngine.Action.CREATE, r.action)
    }

    @Test
    fun `跨来源同金额同时间为同一笔消费`() {
        val r = DedupEngine.decide(
            DedupEngine.Facts(
                candidate = candidate(sourcePackage = PayPackages.WECHAT, channelLabel = "微信零钱"),
                recent = listOf(
                    existing(
                        sourcePackage = PayPackages.UNIONPAY,
                        channelLabel = "招商银行(8806)",
                        at = base + 20_000,
                    )
                ),
            )
        )
        assertEquals(DedupEngine.Action.CREATE_FLAGGED, r.action)
        assertEquals(DedupEngine.Mode.CROSS_SOURCE, r.mode)
    }

    @Test
    fun `渠道未知时同金额同时间仍提示可能重复`() {
        val r = DedupEngine.decide(
            DedupEngine.Facts(
                candidate = candidate(channelLabel = null),
                recent = listOf(existing(channelLabel = null, at = base + 10_000)),
            )
        )
        assertEquals(DedupEngine.Action.CREATE_FLAGGED, r.action)
    }

    @Test
    fun `不同资产同金额不判重`() {
        val r = DedupEngine.decide(
            DedupEngine.Facts(
                candidate = candidate(assetId = "asset-a"),
                recent = listOf(existing(assetId = "asset-b", at = base)),
            )
        )
        assertEquals(DedupEngine.Action.CREATE, r.action)
    }

    @Test
    fun `金额相差较大不判重`() {
        val r = DedupEngine.decide(
            DedupEngine.Facts(
                candidate = candidate(amount = 4500),
                recent = listOf(existing(amount = 4600, at = base)),
            )
        )
        assertEquals(DedupEngine.Action.CREATE, r.action)
    }

    @Test
    fun `跨天同金额不判重`() {
        val yesterday = base - 24 * 3_600_000L
        val r = DedupEngine.decide(
            DedupEngine.Facts(
                candidate = candidate(at = base),
                recent = listOf(existing(at = yesterday)),
            )
        )
        assertEquals(DedupEngine.Action.CREATE, r.action)
    }

    @Test
    fun `判定优先级 内容hash 高于订单号`() {
        val r = DedupEngine.decide(
            DedupEngine.Facts(
                candidate = candidate(orderId = "9000000000000000000000000010"),
                contentHashSeen = true,
                orderFingerprintHit = true,
            )
        )
        assertEquals(DedupEngine.Mode.TEXT_HASH, r.mode)
    }

    @Test
    fun `商户归一化对齐仓储口径`() {
        assertEquals("美团", DedupEngine.normalizeMerchant(" 美 团 "))
        assertEquals("美团", DedupEngine.normalizeMerchant("美团(望京店)"))
        assertEquals("星巴克", DedupEngine.normalizeMerchant("星巴克有限公司"))
        assertEquals("", DedupEngine.normalizeMerchant(null))
        assertEquals("", DedupEngine.normalizeMerchant("   "))
    }

    // ---------- 测试辅助 ----------

    private fun candidate(
        sourcePackage: String = PayPackages.ALIPAY,
        amount: Long = 4500,
        merchant: String? = "美团",
        channelLabel: String? = null,
        orderId: String? = null,
        at: Long = base,
        assetId: String? = null,
    ) = DedupEngine.Candidate(
        sourcePackage = sourcePackage,
        amountCents = amount,
        merchant = merchant,
        channelLabel = channelLabel,
        orderId = orderId,
        occurredAt = at,
        assetId = assetId,
        fingerprint = DedupEngine.fingerprint(sourcePackage, amount, merchant, at, orderId),
        orderFingerprint = orderId?.let { DedupEngine.orderFingerprint(it) },
    )

    private fun existing(
        sourcePackage: String? = PayPackages.ALIPAY,
        amount: Long = 4500,
        merchant: String? = "美团",
        channelLabel: String? = null,
        at: Long = base,
        assetId: String? = null,
    ) = DedupEngine.Existing(
        id = "t-existing",
        sourcePackage = sourcePackage,
        amountCents = amount,
        merchant = merchant,
        channelLabel = channelLabel,
        occurredAt = at,
        assetId = assetId,
    )
}
