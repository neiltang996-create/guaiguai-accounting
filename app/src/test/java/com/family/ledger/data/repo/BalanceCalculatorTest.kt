package com.family.ledger.data.repo

import com.family.ledger.data.db.dao.TxnBalanceRow
import com.family.ledger.data.db.entity.AssetEntity
import com.family.ledger.data.db.entity.AssetType
import com.family.ledger.data.db.entity.BalanceAnchorEntity
import com.family.ledger.data.db.entity.OwnerType
import com.family.ledger.data.db.entity.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BalanceCalculatorTest {

    private val HOUR = 3_600_000L

    private fun asset(
        id: String,
        name: String = id,
        type: AssetType = AssetType.VIRTUAL,
        owner: OwnerType = OwnerType.USER,
        opening: Long = 0L,
        inNetWorth: Boolean = true,
    ) = AssetEntity(
        id = id,
        name = name,
        ownerType = owner,
        ownerFamilyId = if (owner == OwnerType.FAMILY) "fam-1" else null,
        type = type,
        openingBalance = opening,
        includeInNetWorth = inNetWorth,
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun row(
        id: String,
        type: TxnType,
        amount: Long,
        assetId: String? = null,
        toAssetId: String? = null,
        fee: Long = 0L,
        coupon: Long = 0L,
        at: Long = HOUR,
        exclude: Boolean = false,
    ) = TxnBalanceRow(
        id = id,
        type = type,
        amount = amount,
        fee = fee,
        coupon = coupon,
        assetId = assetId,
        toAssetId = toAssetId,
        occurredAt = at,
        excludeFromStats = exclude,
    )

    private fun anchor(id: String, assetId: String, real: Long, at: Long) = BalanceAnchorEntity(
        id = id,
        assetId = assetId,
        realBalance = real,
        at = at,
        createdByDeviceId = "dev-1",
        createdAt = at,
        updatedAt = at,
    )

    // ---------- 基本方向 ----------

    @Test
    fun `opening balance alone`() {
        val a = asset("a", opening = 100_00L)
        assertEquals(100_00L, BalanceCalculator.currentBalance(a, emptyList(), emptyList()))
    }

    @Test
    fun `expense subtracts amount plus fee minus coupon`() {
        val a = asset("a", opening = 100_00L)
        val t = row("t1", TxnType.EXPENSE, amount = 30_00L, assetId = "a", fee = 1_00L, coupon = 5_00L)
        // 100 - (30 + 1 - 5) = 74
        assertEquals(74_00L, BalanceCalculator.currentBalance(a, emptyList(), listOf(t)))
    }

    @Test
    fun `income adds amount minus fee`() {
        val a = asset("a")
        val t = row("t1", TxnType.INCOME, amount = 50_00L, assetId = "a", fee = 2_00L)
        assertEquals(48_00L, BalanceCalculator.currentBalance(a, emptyList(), listOf(t)))
    }

    @Test
    fun `refund credits back to the original asset`() {
        val a = asset("a", opening = 100_00L)
        val spend = row("t1", TxnType.EXPENSE, 30_00L, assetId = "a")
        val refund = row("t2", TxnType.REFUND, 30_00L, assetId = "a", at = 2 * HOUR)
        assertEquals(100_00L, BalanceCalculator.currentBalance(a, emptyList(), listOf(spend, refund)))
    }

    @Test
    fun `transfer moves money between two assets`() {
        val from = asset("from", opening = 100_00L)
        val to = asset("to", opening = 0L)
        val t = row("t1", TxnType.TRANSFER, 40_00L, assetId = "from", toAssetId = "to")
        assertEquals(60_00L, BalanceCalculator.currentBalance(from, emptyList(), listOf(t)))
        assertEquals(40_00L, BalanceCalculator.currentBalance(to, emptyList(), listOf(t)))
    }

    @Test
    fun `repayment behaves like a transfer onto the credit card`() {
        val savings = asset("sav", opening = 100_00L, type = AssetType.SAVINGS)
        val card = asset("card", opening = 0L, type = AssetType.CREDIT)
        val t = row("t1", TxnType.REPAYMENT, 25_00L, assetId = "sav", toAssetId = "card")
        assertEquals(75_00L, BalanceCalculator.currentBalance(savings, emptyList(), listOf(t)))
        // 还款让信用卡余额上升（欠款减少）
        assertEquals(25_00L, BalanceCalculator.currentBalance(card, emptyList(), listOf(t)))
        // 展示时信用卡欠款显示为正
        assertEquals(-25_00L, BalanceCalculator.displayBalance(card, 25_00L))
    }

    @Test
    fun `balance adjustment transaction itself has no effect`() {
        val a = asset("a", opening = 100_00L)
        val t = row("t1", TxnType.BALANCE_ADJUST, 999_00L, assetId = "a")
        assertEquals(100_00L, BalanceCalculator.currentBalance(a, emptyList(), listOf(t)))
    }

    // ---------- 校准锚点 ----------

    @Test
    fun `anchor replaces history before it`() {
        val a = asset("a", opening = 100_00L)
        val before = row("t1", TxnType.EXPENSE, 30_00L, assetId = "a", at = 1 * HOUR)
        val after = row("t2", TxnType.EXPENSE, 10_00L, assetId = "a", at = 5 * HOUR)
        val an = anchor("an1", "a", real = 200_00L, at = 3 * HOUR)
        // 锚点说明 3 小时时真实余额是 200；之后又花了 10 → 190
        assertEquals(190_00L, BalanceCalculator.currentBalance(a, listOf(an), listOf(before, after)))
    }

    @Test
    fun `latest anchor wins when several exist`() {
        val a = asset("a", opening = 100_00L)
        val an1 = anchor("an1", "a", real = 200_00L, at = 3 * HOUR)
        val an2 = anchor("an2", "a", real = 500_00L, at = 8 * HOUR)
        assertEquals(500_00L, BalanceCalculator.currentBalance(a, listOf(an1, an2), emptyList()))
    }

    @Test
    fun `anchor of another asset is ignored`() {
        val a = asset("a", opening = 100_00L)
        val other = anchor("an-x", "b", real = 999_00L, at = HOUR)
        assertEquals(100_00L, BalanceCalculator.currentBalance(a, listOf(other), emptyList()))
    }

    @Test
    fun `reconciliationDelta reports the gap or null`() {
        assertEquals(50_00L, BalanceCalculator.reconciliationDelta(100_00L, 150_00L))
        assertEquals(-30_00L, BalanceCalculator.reconciliationDelta(100_00L, 70_00L))
        assertNull(BalanceCalculator.reconciliationDelta(100_00L, 100_00L))
    }

    // ---------- 批量与净值 ----------

    @Test
    fun `batch balances match per-asset computation`() {
        val a = asset("a", opening = 100_00L)
        val b = asset("b", opening = 50_00L)
        val c = asset("c", opening = 0L)
        val txns = listOf(
            row("t1", TxnType.EXPENSE, 30_00L, assetId = "a"),
            row("t2", TxnType.INCOME, 20_00L, assetId = "b"),
            row("t3", TxnType.TRANSFER, 10_00L, assetId = "a", toAssetId = "c"),
            row("t4", TxnType.EXPENSE, 7_00L, assetId = "c", at = 2 * HOUR),
        )
        val batch = BalanceCalculator.balancesFor(listOf(a, b, c), emptyList(), txns)
        assertEquals(BalanceCalculator.currentBalance(a, emptyList(), txns), batch["a"])
        assertEquals(BalanceCalculator.currentBalance(b, emptyList(), txns), batch["b"])
        assertEquals(BalanceCalculator.currentBalance(c, emptyList(), txns), batch["c"])
        assertEquals(60_00L, batch["a"])   // 100 - 30 - 10
        assertEquals(70_00L, batch["b"])   // 50 + 20
        assertEquals(3_00L, batch["c"])    // 0 + 10 - 7
    }

    @Test
    fun `assets excluded from net worth contribute zero`() {
        val visible = asset("v", opening = 100_00L)
        val hidden = asset("h", opening = 500_00L, inNetWorth = false)
        assertEquals(100_00L, BalanceCalculator.netWorthContribution(visible, 100_00L))
        assertEquals(0L, BalanceCalculator.netWorthContribution(hidden, 500_00L))
    }

    // ---------- 核心场景：夫妻共用的同一份资产 ----------

    @Test
    fun `family shared asset aggregates both spouses spending`() {
        // 支付宝小荷包：两个人各自在自己手机上记，余额是同一份
        val xiaohebao = asset(
            id = "xhb",
            name = "支付宝小荷包(示例日常)",
            owner = OwnerType.FAMILY,
            opening = 5000_00L,
        )
        val husband = listOf(
            row("h1", TxnType.EXPENSE, 192_00L, assetId = "xhb", at = 1 * HOUR),
            row("h2", TxnType.EXPENSE, 45_00L, assetId = "xhb", at = 2 * HOUR),
        )
        val wife = listOf(
            row("w1", TxnType.EXPENSE, 339_60L, assetId = "xhb", at = 3 * HOUR),
            row("w2", TxnType.REFUND, 45_00L, assetId = "xhb", at = 4 * HOUR),
        )
        val balance = BalanceCalculator.currentBalance(xiaohebao, emptyList(), husband + wife)
        // 5000 - 192 - 45 - 339.60 + 45 = 4468.40
        assertEquals(4468_40L, balance)
    }

    @Test
    fun `two devices deriving the same op set agree on the balance`() {
        // 双端同步的本质要求：同一组流水 → 同一个余额，与设备无关
        val shared = asset("xhb", owner = OwnerType.FAMILY, opening = 1000_00L)
        val deviceAOps = listOf(row("a1", TxnType.EXPENSE, 10_00L, assetId = "xhb", at = HOUR))
        val deviceBOps = listOf(row("b1", TxnType.EXPENSE, 20_00L, assetId = "xhb", at = 2 * HOUR))

        // A 端先本地记账，再合并 B 端流水
        val aView = BalanceCalculator.currentBalance(shared, emptyList(), deviceAOps + deviceBOps)
        // B 端顺序相反，结果必须一致
        val bView = BalanceCalculator.currentBalance(shared, emptyList(), deviceBOps + deviceAOps)
        assertEquals(aView, bView)
        assertEquals(970_00L, aView)
    }
}
