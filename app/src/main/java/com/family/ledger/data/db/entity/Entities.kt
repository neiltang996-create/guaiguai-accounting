package com.family.ledger.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** 资产/账本归属维度 —— 本项目的核心扩展。 */
enum class OwnerType { USER, FAMILY }

/** 资产类型，对齐钱迹的账户类型语义。 */
enum class AssetType {
    CASH,          // 现金
    SAVINGS,       // 储蓄卡
    CREDIT,        // 信用卡（负资产）
    PREPAID,       // 充值卡/储值
    VIRTUAL,       // 虚拟账户（微信零钱、支付宝余额、小荷包）
    INVEST,        // 投资（余额宝、基金）
    RECEIVABLE,    // 应收（借出）
    PAYABLE,       // 应付（借入）
    OTHER;

    val isLiability: Boolean get() = this == CREDIT || this == PAYABLE
}

/** 交易类型，对齐钱迹 CSV 的「类型」列。 */
enum class TxnType(val cn: String) {
    EXPENSE("支出"),
    INCOME("收入"),
    TRANSFER("转账"),
    REFUND("退款"),
    REPAYMENT("还款"),
    BALANCE_ADJUST("余额校准");

    companion object {
        fun fromCn(v: String?): TxnType? = entries.firstOrNull { it.cn == v?.trim() }
    }
}

/** 交易来源。 */
enum class TxnSource {
    MANUAL,
    AUTO_ALIPAY,
    AUTO_WECHAT,
    AUTO_OTHER,
    IMPORT_QIANJI,
    REPEAT,
    REFUND_LINK;

    val isAuto: Boolean get() = this == AUTO_ALIPAY || this == AUTO_WECHAT || this == AUTO_OTHER
}

/** 家庭。固定双人家庭。 */
@Serializable
@Entity(tableName = "family")
data class FamilyEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

/** 固定人物。deviceId/isMe 仅为兼容旧库保留；新数据不使用这两个字段。 */
@Entity(
    tableName = "family_member",
    indices = [Index("familyId"), Index("deviceId")],
)
@Serializable
data class FamilyMemberEntity(
    @PrimaryKey val id: String,
    val familyId: String,
    val displayName: String,
    val deviceId: String?,
    val isMe: Boolean = false,
    val joinedAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

/** 账本（多账本）。 */
@Serializable
@Entity(tableName = "book", indices = [Index("ownerFamilyId"), Index("ownerUserId")])
data class BookEntity(
    @PrimaryKey val id: String,
    val name: String,
    val ownerType: OwnerType,
    val ownerUserId: String? = null,
    val ownerFamilyId: String? = null,
    val currency: String = "CNY",
    val archived: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

/**
 * 资产 / 账户。
 *
 * 关键设计：**余额不直接存储、不允许直接编辑**。
 * 当前余额 = 最近一次校准锚点的真实余额 + 该锚点之后所有流水的净额；
 * 无锚点时 = openingBalance + 全部流水净额。
 * 这样两台手机永远不会因为「同时改余额」而冲突。
 */
@Entity(
    tableName = "asset",
    indices = [Index("ownerType"), Index("ownerFamilyId"), Index("ownerUserId"), Index("archived")],
)
@Serializable
data class AssetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val ownerType: OwnerType,
    val ownerUserId: String? = null,
    val ownerFamilyId: String? = null,
    val type: AssetType = AssetType.OTHER,
    /** 资产分组名（钱迹的「资产分组」），如「家庭共享资产」。 */
    val groupName: String? = null,
    val currency: String = "CNY",
    /** 期初余额（分）。仅作为推导起点，之后只由流水与校准改变。 */
    val openingBalance: Long = 0L,
    /** 信用卡等额度，仅展示用。 */
    val creditLimit: Long = 0L,
    val includeInNetWorth: Boolean = true,
    /** 该资产是否由夫妻共同使用（用于记账页默认选中与统计口径）。 */
    val sharedForFamily: Boolean = false,
    val iconKey: String? = null,
    val note: String? = null,
    val sortOrder: Int = 0,
    val archived: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

/**
 * 余额校准锚点。
 * 用户看到真实余额（如支付宝小荷包）与 App 推导值不一致时，
 * 不改余额，而是插入一个锚点 + 一笔「余额校准」流水，保证全程可审计。
 */
@Serializable
@Entity(tableName = "balance_anchor", indices = [Index("assetId")])
data class BalanceAnchorEntity(
    @PrimaryKey val id: String,
    val assetId: String,
    /** 校准时刻的真实余额（分）。 */
    val realBalance: Long,
    val at: Long,
    val note: String? = null,
    val createdByDeviceId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

/** 分类（支持二级）。 */
@Serializable
@Entity(tableName = "category", indices = [Index("parentId"), Index("kind")])
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val parentId: String? = null,
    /** EXPENSE / INCOME */
    val kind: String,
    val iconKey: String? = null,
    val sortOrder: Int = 0,
    val archived: Boolean = false,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

/** 标签。 */
@Serializable
@Entity(tableName = "tag")
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

/**
 * 交易流水 —— 全项目最核心的表。
 * 至少区分：记账人 / 付款人 / 消费人 / 资产 / 账本 / 分类 / 标签。
 */
@Entity(
    tableName = "txn",
    indices = [
        Index("bookId"), Index("assetId"), Index("toAssetId"), Index("occurredAt"),
        Index("categoryId"), Index("type"), Index("externalId"), Index("sourceFingerprint"),
    ],
)
@Serializable
data class TxnEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val type: TxnType,
    /** 金额（分），恒为正数，方向由 type + assetId/toAssetId 决定。 */
    val amount: Long,
    val currency: String = "CNY",
    val occurredAt: Long,

    /** 支出=付款资产；收入=收款资产；转账/还款=转出资产。 */
    val assetId: String? = null,
    /** 转账/还款=转入资产。 */
    val toAssetId: String? = null,

    val categoryId: String? = null,
    val subCategoryId: String? = null,

    /** 记账人（谁记的这笔账）。 */
    val recorderMemberId: String? = null,
    /** 付款人（这笔钱实际谁付的）。 */
    val payerMemberId: String? = null,
    /** 消费人（这笔钱花在谁身上）。 */
    val consumerMemberId: String? = null,

    /** 逗号分隔的标签 id。 */
    val tagIds: String? = null,
    val note: String? = null,

    val reimbursable: Boolean = false,
    val reimbursedAmount: Long = 0L,
    /** 手续费（分）。 */
    val fee: Long = 0L,
    /** 优惠券抵扣（分）。 */
    val coupon: Long = 0L,
    /** 钱迹的「不计收支」。 */
    val excludeFromStats: Boolean = false,

    /** 关联账单（退款/还款关联原单）。 */
    val relatedTxnId: String? = null,
    /** REFUND / REIMBURSEMENT；旧退款缺省为 REFUND，保持旧客户端可解码。 */
    val recoveryKind: String? = null,
    /** 逗号分隔的本地图片路径。 */
    val imagePaths: String? = null,

    val source: TxnSource = TxnSource.MANUAL,
    val manuallyConfirmed: Boolean? = null,
    /** 自动记账去重指纹。 */
    val sourceFingerprint: String? = null,
    /** 外部 ID（钱迹 CSV 的 qj... ID），导入去重用。 */
    val externalId: String? = null,
    /** 商户名（自动记账写入，供商户分类学习使用）。 */
    val merchant: String? = null,

    val createdByDeviceId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

/** 自动记账待确认账单。 */
@Entity(
    tableName = "pending_bill",
    indices = [Index("fingerprint", unique = true), Index("status"), Index("createdAt")],
)
@Serializable
data class PendingBillEntity(
    @PrimaryKey val id: String,
    val sourcePackage: String,
    val source: TxnSource,
    val amount: Long,
    val merchant: String?,
    val occurredAt: Long,
    val rawText: String,
    /** 去重指纹，唯一索引。 */
    val fingerprint: String,
    /** 推测的付款资产。 */
    val guessedAssetId: String? = null,
    /** 推测的分类。 */
    val guessedCategoryId: String? = null,
    val guessedSubCategoryId: String? = null,
    /** PENDING / CONFIRMED / IGNORED / DUPLICATE */
    val status: String = STATUS_PENDING,
    val txnId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_CONFIRMED = "CONFIRMED"
        const val STATUS_IGNORED = "IGNORED"
        const val STATUS_DUPLICATE = "DUPLICATE"
    }
}

/** 自动记账原始日志，对齐钱迹 auto_bill_log 的可观测性设计。 */
@Serializable
@Entity(tableName = "auto_bill_log", indices = [Index("timeMs")])
data class AutoBillLogEntity(
    @PrimaryKey val id: String,
    val timeMs: Long,
    /** RECEIVED / PARSED / DEDUPED / GUESSED / CONFIRMED / IGNORED / ERROR */
    val action: String,
    val pendingBillId: String? = null,
    val txnId: String? = null,
    val matchingMode: String? = null,
    val requestId: String? = null,
    val entryJson: String,
)

/** 商户 → 分类/资产 学习规则。 */
@Serializable
@Entity(tableName = "merchant_rule", indices = [Index("pattern", unique = true)])
data class MerchantRuleEntity(
    @PrimaryKey val id: String,
    /** 归一化后的商户关键字。 */
    val pattern: String,
    val categoryId: String? = null,
    val subCategoryId: String? = null,
    val assetId: String? = null,
    val bookId: String? = null,
    val consumerId: String? = null,
    val hits: Int = 0,
    val lastUsedAt: Long = 0L,
    val updatedAt: Long,
)

/** 同步操作日志（oplog）。 */
@Serializable
@Entity(tableName = "sync_op", indices = [Index("deviceId"), Index("entityTable", "entityId")])
data class SyncOpEntity(
    /** "$deviceId:$seq" */
    @PrimaryKey val opId: String,
    val deviceId: String,
    val seq: Long,
    val entityTable: String,
    val entityId: String,
    /** UPSERT / DELETE */
    val opType: String,
    val payloadJson: String,
    /** 用于 last-writer-wins 的混合逻辑时钟毫秒值。 */
    val hlc: Long,
    val createdAt: Long,
)

/** 同步游标等键值状态。 */
@Serializable
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long,
)

/** 一个人可拥有多台设备；切换身份只改本设备，不修改历史账单。 */
@Serializable
@Entity(tableName = "device", indices = [Index("personId")])
data class DeviceEntity(
    @PrimaryKey val id: String,
    val personId: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
)
