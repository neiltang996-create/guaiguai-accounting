package com.family.ledger.ui.add

import com.family.ledger.data.repo.AssetWithBalance

/**
 * 记账页的「上次选择」记忆（进程内即可，不需要跨进程持久化）。
 * 家庭共享资产优先 —— 这是两口子记同一笔账时的默认心智。
 */
object AddBillDefaults {

    var lastAssetId: String? = null
    var lastToAssetId: String? = null

    /** 默认付款/收款资产：上次用的 → 家庭共享资产 → 个人资产。 */
    fun pickDefaultAsset(
        family: List<AssetWithBalance>,
        personal: List<AssetWithBalance>,
    ): String? {
        val all = family + personal
        lastAssetId?.let { id -> all.firstOrNull { it.asset.id == id }?.let { return it.asset.id } }
        return family.firstOrNull()?.asset?.id ?: personal.firstOrNull()?.asset?.id
    }

    /** 默认转入资产：上次用的 → 家庭共享资产（且不同于转出）→ 任意。 */
    fun pickDefaultToAsset(
        family: List<AssetWithBalance>,
        personal: List<AssetWithBalance>,
        fromId: String?,
    ): String? {
        val all = family + personal
        lastToAssetId?.let { id ->
            all.firstOrNull { it.asset.id == id && it.asset.id != fromId }?.let { return it.asset.id }
        }
        return all.firstOrNull { it.asset.id != fromId }?.asset?.id
    }
}
