package com.family.ledger.data.sync

import com.family.ledger.data.db.entity.*
import kotlinx.serialization.json.Json

/**
 * 实体 ↔ 同步载荷 的编解码。
 * 同步只搬运「原始实体」，不做字段裁剪，保证两端结构一致。
 */
object EntityCodec {

    const val T_DEVICE = "device"
    const val T_FAMILY = "family"
    const val T_MEMBER = "family_member"
    const val T_BOOK = "book"
    const val T_ASSET = "asset"
    const val T_ANCHOR = "balance_anchor"
    const val T_CATEGORY = "category"
    const val T_TAG = "tag"
    const val T_TXN = "txn"
    const val T_MERCHANT_RULE = "merchant_rule"

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 参与同步的表。 */
    val SYNCED_TABLES = listOf(
        T_DEVICE, T_FAMILY, T_MEMBER, T_BOOK, T_ASSET, T_ANCHOR,
        T_CATEGORY, T_TAG, T_TXN, T_MERCHANT_RULE,
    )

    fun tableOf(entity: Any): String = when (entity) {
        is DeviceEntity -> T_DEVICE
        is FamilyEntity -> T_FAMILY
        is FamilyMemberEntity -> T_MEMBER
        is BookEntity -> T_BOOK
        is AssetEntity -> T_ASSET
        is BalanceAnchorEntity -> T_ANCHOR
        is CategoryEntity -> T_CATEGORY
        is TagEntity -> T_TAG
        is TxnEntity -> T_TXN
        is MerchantRuleEntity -> T_MERCHANT_RULE
        else -> error("not a synced entity: ${entity::class.java.name}")
    }

    fun idOf(entity: Any): String = when (entity) {
        is DeviceEntity -> entity.id
        is FamilyEntity -> entity.id
        is FamilyMemberEntity -> entity.id
        is BookEntity -> entity.id
        is AssetEntity -> entity.id
        is BalanceAnchorEntity -> entity.id
        is CategoryEntity -> entity.id
        is TagEntity -> entity.id
        is TxnEntity -> entity.id
        is MerchantRuleEntity -> entity.id
        else -> error("not a synced entity: ${entity::class.java.name}")
    }

    /** 用于 last-writer-wins 的版本时间。 */
    fun updatedAtOf(entity: Any): Long = when (entity) {
        is DeviceEntity -> entity.updatedAt
        is FamilyEntity -> entity.updatedAt
        is FamilyMemberEntity -> entity.updatedAt
        is BookEntity -> entity.updatedAt
        is AssetEntity -> entity.updatedAt
        is BalanceAnchorEntity -> entity.updatedAt
        is CategoryEntity -> entity.updatedAt
        is TagEntity -> entity.updatedAt
        is TxnEntity -> entity.updatedAt
        is MerchantRuleEntity -> entity.updatedAt
        else -> 0L
    }

    fun encode(entity: Any): String = when (entity) {
        is DeviceEntity -> json.encodeToString(DeviceEntity.serializer(), entity)
        is FamilyEntity -> json.encodeToString(FamilyEntity.serializer(), entity)
        is FamilyMemberEntity -> json.encodeToString(FamilyMemberEntity.serializer(), entity)
        is BookEntity -> json.encodeToString(BookEntity.serializer(), entity)
        is AssetEntity -> json.encodeToString(AssetEntity.serializer(), entity)
        is BalanceAnchorEntity -> json.encodeToString(BalanceAnchorEntity.serializer(), entity)
        is CategoryEntity -> json.encodeToString(CategoryEntity.serializer(), entity)
        is TagEntity -> json.encodeToString(TagEntity.serializer(), entity)
        is TxnEntity -> json.encodeToString(TxnEntity.serializer(), entity)
        is MerchantRuleEntity -> json.encodeToString(MerchantRuleEntity.serializer(), entity)
        else -> error("not a synced entity: ${entity::class.java.name}")
    }

    fun decode(table: String, payload: String): Any = when (table) {
        T_DEVICE -> json.decodeFromString(DeviceEntity.serializer(), payload)
        T_FAMILY -> json.decodeFromString(FamilyEntity.serializer(), payload)
        T_MEMBER -> json.decodeFromString(FamilyMemberEntity.serializer(), payload)
        T_BOOK -> json.decodeFromString(BookEntity.serializer(), payload)
        T_ASSET -> json.decodeFromString(AssetEntity.serializer(), payload)
        T_ANCHOR -> json.decodeFromString(BalanceAnchorEntity.serializer(), payload)
        T_CATEGORY -> json.decodeFromString(CategoryEntity.serializer(), payload)
        T_TAG -> json.decodeFromString(TagEntity.serializer(), payload)
        T_TXN -> json.decodeFromString(TxnEntity.serializer(), payload)
        T_MERCHANT_RULE -> json.decodeFromString(MerchantRuleEntity.serializer(), payload)
        else -> error("unknown sync table: $table")
    }
}
