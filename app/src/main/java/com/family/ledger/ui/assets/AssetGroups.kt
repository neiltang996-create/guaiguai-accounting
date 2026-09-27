package com.family.ledger.ui.assets

import com.family.ledger.data.db.entity.AssetEntity
import com.family.ledger.data.db.entity.AssetType
import com.family.ledger.data.repo.AssetRepository

/** 展示分组与账户的金融类型、人物归属相互独立，不改写余额口径。 */
internal object AssetGroups {
    val names = listOf("信用卡", "银行卡", "支付宝", "微信", "充值卡", "现金", "投资理财", "应收款", "应付款", "其他")
    private val legacyNames = setOf(AssetRepository.GROUP_FAMILY, AssetRepository.GROUP_PERSONAL)

    fun explicitGroup(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() && it !in legacyNames }

    fun of(asset: AssetEntity): String = explicitGroup(asset.groupName) ?: suggested(asset.name, asset.type)

    fun suggested(name: String, type: AssetType): String = when {
        type == AssetType.CREDIT || name.contains("信用卡") -> "信用卡"
        type == AssetType.SAVINGS -> "银行卡"
        type == AssetType.RECEIVABLE -> "应收款"
        type == AssetType.PAYABLE -> "应付款"
        name.contains("支付宝") || name.contains("余额宝") || name.contains("小荷包") -> "支付宝"
        name.contains("微信") || name.contains("零钱通") -> "微信"
        type == AssetType.PREPAID || name.contains("充值卡") || name.contains("储值卡") || name.contains("职工普惠") -> "充值卡"
        name.contains("储蓄卡") || name.contains("银行卡") || name.contains("银行") -> "银行卡"
        type == AssetType.CASH -> "现金"
        type == AssetType.INVEST -> "投资理财"
        else -> "其他"
    }

    fun order(group: String): Int = names.indexOf(group).takeIf { it >= 0 } ?: names.size
}
