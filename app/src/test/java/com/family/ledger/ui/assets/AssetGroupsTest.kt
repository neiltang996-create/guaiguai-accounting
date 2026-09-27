package com.family.ledger.ui.assets

import com.family.ledger.data.db.entity.*
import com.family.ledger.data.repo.AssetRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class AssetGroupsTest {
    private fun asset(name: String, type: AssetType, group: String? = null) = AssetEntity(
        id = "a", name = name, type = type, groupName = group, ownerType = OwnerType.FAMILY,
        createdAt = 0, updatedAt = 0,
    )

    @Test fun legacyOwnershipGroupsDoNotHideAccountKinds() {
        assertEquals("支付宝", AssetGroups.of(asset("支付宝小荷包(示例日常)", AssetType.VIRTUAL, AssetRepository.GROUP_FAMILY)))
        assertEquals("微信", AssetGroups.of(asset("微信零钱（用户 B）", AssetType.VIRTUAL, AssetRepository.GROUP_PERSONAL)))
        assertEquals("支付宝", AssetGroups.of(asset("余额宝", AssetType.INVEST)))
        assertEquals("充值卡", AssetGroups.of(asset("职工普惠", AssetType.VIRTUAL)))
    }

    @Test fun bankCardLinkedToPaymentPlatformIsStillACard() {
        assertEquals("信用卡", AssetGroups.of(asset("支付宝绑定的信用卡", AssetType.CREDIT)))
        assertEquals("银行卡", AssetGroups.of(asset("微信绑定储蓄卡", AssetType.SAVINGS)))
    }

    @Test fun manualGroupOverridesSuggestionWithoutChangingFinancialTypeOrOwnership() {
        val original = asset("余额宝", AssetType.INVEST, "投资理财")
        assertEquals("投资理财", AssetGroups.of(original))
        assertEquals(AssetType.INVEST, original.type)
        assertEquals(OwnerType.FAMILY, original.ownerType)
        assertEquals("旅行备用金", AssetGroups.of(original.copy(groupName = "旅行备用金")))
    }

    @Test fun unknownAccountsAreNotAssumedToBeDebt() {
        assertEquals("其他", AssetGroups.of(asset("示例往来账户", AssetType.OTHER)))
        assertEquals("应收款", AssetGroups.of(asset("借给朋友", AssetType.RECEIVABLE)))
        assertEquals("应付款", AssetGroups.of(asset("借款", AssetType.PAYABLE)))
    }
}
