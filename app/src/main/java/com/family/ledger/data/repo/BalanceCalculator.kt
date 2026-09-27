package com.family.ledger.data.repo

import com.family.ledger.data.db.dao.TxnBalanceRow
import com.family.ledger.data.db.entity.AssetEntity
import com.family.ledger.data.db.entity.AssetType
import com.family.ledger.data.db.entity.BalanceAnchorEntity
import com.family.ledger.data.db.entity.TxnEntity
import com.family.ledger.data.db.entity.TxnType

/**
 * 余额推导 —— 本项目最关键的不变量：
 *
 *   **任何资产的余额都不允许被直接改写。**
 *   当前余额 = 最近一次校准锚点的真实余额 + 该锚点之后所有流水的净额；
 *             若无锚点，则 = 期初余额 + 全部流水的净额。
 *
 * 这样两台手机各自记账、各自同步流水，余额天然一致，不存在「同时改余额」的冲突。
 */
object BalanceCalculator {
    private val anchorOrder = compareBy<BalanceAnchorEntity> { it.at }.thenBy { it.createdByDeviceId }.thenBy { it.id }

    /**
     * 单笔流水对某个资产造成的余额变化（分）。
     * 正数=资产增加，负数=资产减少。
     *
     * 支出：金额 + 手续费 - 优惠券 从付款资产扣除
     * 收入：金额 - 手续费 进入收款资产
     * 退款 / 报销：金额进入所选到账资产
     * 转账/还款：转出资产扣除，转入资产增加
     * 余额校准：本身不产生净额（差额已由锚点体现）
     */
    fun deltaForAsset(txn: TxnBalanceRow, assetId: String): Long {
        var delta = 0L
        val fromAmount = when (txn.type) {
            TxnType.EXPENSE -> -(txn.amount + txn.fee - txn.coupon)
            TxnType.INCOME -> (txn.amount - txn.fee)
            TxnType.REFUND -> txn.amount
            TxnType.TRANSFER, TxnType.REPAYMENT -> -txn.amount
            TxnType.BALANCE_ADJUST -> 0L
        }
        val toAmount = when (txn.type) {
            TxnType.TRANSFER, TxnType.REPAYMENT -> txn.amount
            TxnType.REFUND -> 0L
            TxnType.BALANCE_ADJUST -> 0L
            else -> 0L
        }
        if (txn.assetId == assetId) delta += fromAmount
        if (txn.toAssetId == assetId) delta += toAmount
        return delta
    }

    /**
     * 计算资产当前余额。
     * @param anchors 该资产的校准锚点（任意顺序）
     * @param txns 全部未删除流水（内部会自行过滤与排序）
     */
    fun currentBalance(
        asset: AssetEntity,
        anchors: List<BalanceAnchorEntity>,
        txns: List<TxnBalanceRow>,
    ): Long {
        val latest = anchors.filter { it.assetId == asset.id && !it.deleted }
            .maxWithOrNull(anchorOrder)
        return if (latest == null) {
            asset.openingBalance + txns.sumOf { deltaForAsset(it, asset.id) }
        } else {
            // 锚点已反映该时刻的真实余额，只累加锚点「之后」发生的流水
            latest.realBalance + txns.filter { it.occurredAt > latest.at }
                .sumOf { deltaForAsset(it, asset.id) }
        }
    }

    /** 一次性算出全部资产余额，避免 N 次遍历。 */
    fun balancesFor(
        assets: List<AssetEntity>,
        anchors: List<BalanceAnchorEntity>,
        txns: List<TxnBalanceRow>,
    ): Map<String, Long> {
        val byAssetAnchors = anchors.filter { !it.deleted }.groupBy { it.assetId }
        // 每笔流水只影响 1~2 个资产，先按资产聚合，避免 O(资产数 × 流水数)
        val deltaByAsset = HashMap<String, Long>(assets.size * 2)
        val anchorCut = HashMap<String, Long>(byAssetAnchors.size)
        byAssetAnchors.forEach { (assetId, list) ->
            anchorCut[assetId] = list.maxOf { it.at }
        }
        for (t in txns) {
            val a = t.assetId
            if (a != null) {
                // 锚点之前发生的流水不再重复计入
                val cut = anchorCut[a]
                if (cut == null || t.occurredAt > cut) {
                    deltaByAsset[a] = (deltaByAsset[a] ?: 0L) + deltaForAsset(t, a)
                }
            }
            val b = t.toAssetId
            if (b != null && b != a) {
                val cut = anchorCut[b]
                if (cut == null || t.occurredAt > cut) {
                    deltaByAsset[b] = (deltaByAsset[b] ?: 0L) + deltaForAsset(t, b)
                }
            }
        }
        val out = HashMap<String, Long>(assets.size)
        for (asset in assets) {
            val anchor = byAssetAnchors[asset.id]?.maxWithOrNull(anchorOrder)
            val base = anchor?.realBalance ?: asset.openingBalance
            out[asset.id] = base + (deltaByAsset[asset.id] ?: 0L)
        }
        return out
    }

    /**
     * 资产在净值中的贡献：负债类资产（信用卡/应付）余额为负时按负值计入。
     * 显示层用这个值。
     */
    fun netWorthContribution(asset: AssetEntity, balance: Long): Long = when {
        !asset.includeInNetWorth -> 0L
        else -> balance
    }

    /** 计算「校准到真实余额」所需的差额；返回 null 表示已经一致。 */
    fun reconciliationDelta(currentBalance: Long, realBalance: Long): Long? =
        if (currentBalance == realBalance) null else realBalance - currentBalance

    /** 信用卡等负债资产的展示符号：欠款显示为正数更符合直觉。 */
    fun displayBalance(asset: AssetEntity, balance: Long): Long =
        if (asset.type == AssetType.CREDIT || asset.type == AssetType.PAYABLE) -balance else balance
}
