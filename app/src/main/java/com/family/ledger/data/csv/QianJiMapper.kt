package com.family.ledger.data.csv

import com.family.ledger.core.Ids
import com.family.ledger.core.Money
import com.family.ledger.core.TimeFmt
import com.family.ledger.data.db.entity.AssetType
import com.family.ledger.data.db.entity.OwnerType
import com.family.ledger.data.db.entity.TxnEntity
import com.family.ledger.data.db.entity.TxnSource
import com.family.ledger.data.db.entity.TxnType
import com.family.ledger.data.repo.AssetRepository
import com.family.ledger.data.repo.CategoryRepository
import com.family.ledger.data.repo.FamilyRepository
import kotlin.math.abs

/**
 * 钱迹行 ↔ 本地实体的映射。
 *
 * 导入方向：全是「名字 → id」的解析，先把库里的名字索引一次性读进内存，
 * 避免 600 行 × 每个名字都查一次库；匹配不到的名字按约定自动创建
 * （账户→资产、分类→一级/二级、记账者→家庭成员、标签→标签）。
 *
 * 所有写入都走 repository，保证同时记 oplog。
 */
class QianJiMapper(
    private val assets: AssetRepository,
    private val categories: CategoryRepository,
    private val family: FamilyRepository,
) {

    /** 归一化名字 → 资产 id。 */
    private val assetIdByName = HashMap<String, String>()

    /** "kind\u0000一级名" → 分类 id。 */
    private val topCategoryIdByKey = HashMap<String, String>()

    /** "parentId\u0000二级名" → 分类 id。 */
    private val childCategoryIdByKey = HashMap<String, String>()

    /** 归一化名字 → 成员 id。 */
    private val memberIdByName = HashMap<String, String>()

    /** 归一化名字 → 标签 id。 */
    private val tagIdByName = HashMap<String, String>()

    /** 本次导入自动创建的资产数。 */
    var createdAssets: Int = 0
        private set

    /** 本次导入自动创建的分类数（一级 + 二级）。 */
    var createdCategories: Int = 0
        private set

    /** 本次导入自动创建的成员数。 */
    var createdMembers: Int = 0
        private set

    // ---------- 缓存 ----------

    /** 一次性建索引。导入前必须调用。 */
    suspend fun load() {
        assetIdByName.clear()
        assets.all().forEach { assetIdByName[key(it.name)] = it.id }

        topCategoryIdByKey.clear()
        childCategoryIdByKey.clear()
        for (kind in listOf(KIND_EXPENSE, KIND_INCOME)) {
            for (group in categories.groups(kind)) {
                topCategoryIdByKey[topKey(kind, group.top.name)] = group.top.id
                for (child in group.children) {
                    childCategoryIdByKey[childKey(group.top.id, child.name)] = child.id
                }
            }
        }

        memberIdByName.clear()
        family.members().forEach { memberIdByName[key(it.displayName)] = it.id }

        tagIdByName.clear()
        categories.tags().forEach { tagIdByName[key(it.name)] = it.id }
    }

    suspend fun setAssetMappings(mapping: Map<String,String>) {
        for ((name, id) in mapping) {
            require(assets.byId(id)?.deleted == false) { "映射的资产不存在" }
            assetIdByName[key(name)] = id
        }
    }

    // ---------- 名字解析（缺则自动创建） ----------

    /** 「账户1 / 账户2」→ 资产 id；库里没有就自动建一个。 */
    suspend fun assetId(name: String?): String? {
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) return null
        assetIdByName[key(n)]?.let { return it }
        val owner = guessOwnerType(n)
        val created = assets.create(
            name = n,
            type = guessAssetType(n),
            ownerType = owner,
            familyId = null,
            groupName = if (owner == OwnerType.FAMILY) {
                AssetRepository.GROUP_FAMILY
            } else {
                AssetRepository.GROUP_PERSONAL
            },
        )
        createdAssets++
        assetIdByName[key(n)] = created.id
        return created.id
    }

    /** 「分类 / 二级分类」→ (一级 id, 二级 id)；库里没有就自动建。 */
    suspend fun categoryIds(row: QianJiRow, kind: String = kindFor(row)): Pair<String?, String?> {
        val topName = row.category.trim()
        if (topName.isEmpty()) return null to null

        var topId = topCategoryIdByKey[topKey(kind, topName)]
            ?: topCategoryIdByKey[topKey(otherKind(kind), topName)]
        if (topId == null) {
            val created = categories.createCategory(topName, kind, null)
            createdCategories++
            topCategoryIdByKey[topKey(kind, topName)] = created.id
            topId = created.id
        }

        val subName = row.subCategory.trim()
        if (subName.isEmpty()) return topId to null

        var subId = childCategoryIdByKey[childKey(topId, subName)]
        if (subId == null) {
            val created = categories.createCategory(subName, kind, topId)
            createdCategories++
            childCategoryIdByKey[childKey(topId, subName)] = created.id
            subId = created.id
        }
        return topId to subId
    }

    /** 「记账者」→ 成员 id；库里没有就自动加一个成员。 */
    suspend fun memberId(name: String?): String? {
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) return null
        memberIdByName[key(n)]?.let { return it }
        // 兜底：名字大小写/空格差异
        family.members().firstOrNull { it.displayName.trim() == n }?.let {
            memberIdByName[key(n)] = it.id
            return it.id
        }
        val created = family.addMember(n)
        createdMembers++
        memberIdByName[key(n)] = created.id
        return created.id
    }

    /** 「标签」→ 标签 id 列表。 */
    suspend fun tagIds(raw: String?): List<String> {
        val names = QianJiRow.splitList(raw)
        if (names.isEmpty()) return emptyList()
        val out = ArrayList<String>(names.size)
        for (n in names) {
            var id = tagIdByName[key(n)]
            if (id == null) {
                val tag = categories.findOrCreateTag(n)
                id = tag.id
                tagIdByName[key(n)] = id
            }
            out += id
        }
        return out
    }

    // ---------- 导入：行 → 流水 ----------

    /**
     * 一行钱迹 → 一笔本地流水（名字全部解析完）。
     * 类型或时间无法识别时返回 null，由调用方计入错误。
     */
    suspend fun toTxn(
        row: QianJiRow,
        bookId: String,
        deviceId: String,
        relatedTxnId: String? = null,
        now: Long = System.currentTimeMillis(),
    ): TxnEntity? {
        val (categoryId, subCategoryId) = categoryIds(row)
        val recorderId = memberId(row.recorder)
        return buildTxn(
            row = row,
            bookId = bookId,
            deviceId = deviceId,
            assetId = assetId(row.account1),
            toAssetId = assetId(row.account2),
            categoryId = categoryId,
            subCategoryId = subCategoryId,
            recorderMemberId = recorderId,
            tagIds = tagIds(row.tags),
            relatedTxnId = relatedTxnId,
            now = now,
        )
    }

    companion object {

        const val KIND_EXPENSE = "EXPENSE"
        const val KIND_INCOME = "INCOME"

        /** 钱迹没有「收入」以外的一级分类挂在 INCOME 下，这里按交易类型判断。 */
        fun kindFor(row: QianJiRow): String =
            if (row.txnType == TxnType.INCOME) KIND_INCOME else KIND_EXPENSE

        fun otherKind(kind: String): String =
            if (kind == KIND_INCOME) KIND_EXPENSE else KIND_INCOME

        /** 名字含「小荷包」→ 家庭共享资产，其余按个人资产。 */
        fun guessOwnerType(name: String): OwnerType =
            if (name.contains("小荷包")) OwnerType.FAMILY else OwnerType.USER

        /** 按名字猜资产类型（仅用于自动创建的新账户，已有账户不受影响）。 */
        fun guessAssetType(name: String): AssetType = when {
            name.contains("信用卡") -> AssetType.CREDIT
            name.contains("储蓄卡") || name.contains("银行") -> AssetType.SAVINGS
            name.contains("余额宝") || name.contains("基金") || name.contains("理财") -> AssetType.INVEST
            name.contains("充值卡") || name.contains("储值") -> AssetType.PREPAID
            name.contains("现金") -> AssetType.CASH
            name.contains("借") -> AssetType.PAYABLE
            name.contains("零钱") || name.contains("支付宝") || name.contains("微信") ||
                name.contains("小荷包") || name.contains("普惠") -> AssetType.VIRTUAL
            else -> AssetType.OTHER
        }

        /** 名字索引的归一化 key（去空格 + 小写，中文不受影响）。 */
        fun key(name: String): String = name.trim().lowercase()

        private fun topKey(kind: String, name: String): String = "$kind\u0000${key(name)}"

        private fun childKey(parentId: String, name: String): String = "$parentId\u0000${key(name)}"

        /**
         * 用已解析好的 id 组装流水。
         *
         * 金额恒为正数（方向由 type + assetId/toAssetId 决定）；
         * `externalId` 保存钱迹 ID，作为导入去重键。
         */
        fun buildTxn(
            row: QianJiRow,
            bookId: String,
            deviceId: String,
            assetId: String?,
            toAssetId: String?,
            categoryId: String?,
            subCategoryId: String?,
            recorderMemberId: String?,
            tagIds: List<String>,
            relatedTxnId: String?,
            now: Long = System.currentTimeMillis(),
        ): TxnEntity? {
            val type = row.txnType ?: return null
            val occurredAt = row.occurredAt ?: return null
            return TxnEntity(
                id = if (row.id.isNotBlank()) "t-qj-" + java.util.UUID.nameUUIDFromBytes(("qianji|" + row.id.trim()).toByteArray(Charsets.UTF_8)) else Ids.newId("t-"),
                bookId = bookId,
                type = type,
                amount = abs(row.amountCents),
                currency = row.currency.trim().ifBlank { "CNY" },
                occurredAt = occurredAt,
                assetId = assetId,
                toAssetId = toAssetId,
                categoryId = categoryId,
                subCategoryId = subCategoryId,
                recorderMemberId = recorderMemberId,
                // 钱迹只导出「记账者」，付款人/消费人缺省与记账者一致（与手工记账的缺省语义相同）
                payerMemberId = recorderMemberId,
                consumerMemberId = recorderMemberId,
                tagIds = tagIds.takeIf { it.isNotEmpty() }?.joinToString(","),
                note = row.note.trim().ifBlank { null },
                reimbursable = row.isReimbursed,
                reimbursedAmount = row.reimbursedCents,
                fee = row.feeCents,
                coupon = row.couponCents,
                excludeFromStats = row.excludedFromStats,
                relatedTxnId = relatedTxnId,
                imagePaths = row.imageList.takeIf { it.isNotEmpty() }?.joinToString(","),
                source = TxnSource.IMPORT_QIANJI,
                externalId = row.id.trim().ifBlank { null },
                merchant = null,
                createdByDeviceId = deviceId,
                createdAt = now,
                updatedAt = now,
            )
        }

        /**
         * 本地流水 → 钱迹行（导出方向）。
         *
         * 外部引用已经由调用方反查成名字，这里只做纯拼装，
         * 便于单测覆盖类型映射与「不计收支」标记。
         *
         * **钱迹 CSV 没有「余额校准」类型，导出会失真**：
         * 校准行若被当成支出导入新库会静默算错余额（`excludeFromStats` 只影响统计，不影响余额推导）。
         * 校准锚点属于本 App 的私有状态，靠 WebDAV 同步传递，不进钱迹 CSV。
         * 因此 [TxnType.BALANCE_ADJUST] 返回 null，由 [exportRows] 过滤掉。
         */
        fun toRow(
            txn: TxnEntity,
            externalId: String,
            assetName: (String?) -> String,
            categoryName: (String?) -> String,
            memberName: (String?) -> String,
            tagNames: (List<String>) -> String,
            relatedExternalId: String?,
        ): QianJiRow? {
            if (txn.type == TxnType.BALANCE_ADJUST) return null
            return QianJiRow(
                id = externalId,
                time = TimeFmt.toCsv(txn.occurredAt),
                category = categoryName(txn.categoryId),
                subCategory = categoryName(txn.subCategoryId),
                type = QianJiRow.typeToCn(txn.type),
                amount = Money.toPlainString(txn.amount),
                currency = txn.currency.ifBlank { "CNY" },
                account1 = assetName(txn.assetId),
                account2 = assetName(txn.toAssetId),
                note = txn.note.orEmpty(),
                reimbursed = if (txn.reimbursable) "1" else "",
                fee = if (txn.fee != 0L) Money.toPlainString(txn.fee) else "",
                coupon = if (txn.coupon != 0L) Money.toPlainString(txn.coupon) else "",
                recorder = memberName(txn.recorderMemberId),
                flag = if (txn.excludeFromStats) QianJiRow.FLAG_EXCLUDE else "",
                tags = tagNames(QianJiRow.splitList(txn.tagIds)),
                images = txn.imagePaths.orEmpty(),
                relatedBill = relatedExternalId.orEmpty(),
            )
        }

        /**
         * 导出：批量把本地流水映射成钱迹行，**跳过 [TxnType.BALANCE_ADJUST]**。
         *
         * 钱迹 CSV 没有「余额校准」类型，导出会失真：
         * 校准行若被当成支出导入新库会静默算错余额（`excludeFromStats` 只影响统计，不影响余额推导）。
         * 校准锚点属于本 App 的私有状态，靠 WebDAV 同步传递，不进钱迹 CSV。
         */
        fun exportRows(
            txns: List<TxnEntity>,
            externalIdOf: (TxnEntity) -> String,
            assetName: (String?) -> String,
            categoryName: (String?) -> String,
            memberName: (String?) -> String,
            tagNames: (List<String>) -> String,
            relatedExternalIdOf: (TxnEntity) -> String?,
        ): List<QianJiRow> = txns.mapNotNull { txn ->
            toRow(
                txn = txn,
                externalId = externalIdOf(txn),
                assetName = assetName,
                categoryName = categoryName,
                memberName = memberName,
                tagNames = tagNames,
                relatedExternalId = relatedExternalIdOf(txn),
            )
        }

        /**
         * 本地新记的账没有钱迹 ID，导出时生成一个**稳定**的钱迹格式 ID
         * （FNV-1a 64 位哈希 → 19 位数字，同一条流水每次导出都相同）。
         */
        fun generatedExternalId(localId: String): String {
            var h = FNV_OFFSET_BASIS
            for (ch in localId) {
                h = h xor ch.code.toLong()
                h *= FNV_PRIME
            }
            return "qj" + (h and Long.MAX_VALUE).toString().padStart(19, '0')
        }

        private const val FNV_OFFSET_BASIS = -9000000000000000001L // 0xcbf29ce484222325
        private const val FNV_PRIME = 1099511628211L // 0x100000001b3
    }
}
