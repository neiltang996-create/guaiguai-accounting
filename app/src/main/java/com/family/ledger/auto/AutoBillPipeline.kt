package com.family.ledger.auto

import android.content.Context
import androidx.room.withTransaction
import com.family.ledger.AppContainer
import com.family.ledger.FamilyLedgerApp
import com.family.ledger.core.Ids
import com.family.ledger.data.db.entity.AssetEntity
import com.family.ledger.data.db.entity.OwnerType
import com.family.ledger.data.db.entity.PendingBillEntity
import com.family.ledger.data.db.entity.TxnEntity
import com.family.ledger.data.repo.TxnDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 自动记账 · 编排。
 *
 * 无障碍服务与通知监听都只调用 [handle]：
 *
 *   去重（内容 hash → 订单号 → 待确认指纹 → 流水指纹）→ 猜资产（家庭共享优先）
 *   → 猜分类（商户学习规则）→ 落待确认账单 → 写 auto_bill_log → 发通知；
 *   完整面板由用户确认后入账（仍走 `ledger.save(TxnDraft)`）。
 *
 * 所有写入都走 repository（`ledger.save` / `ledger.upsert`），保证 oplog 成对写入、双端可同步。
 */
class AutoBillPipeline internal constructor(
    private val context: Context,
    private val container: AppContainer,
) {

    private val textCache = DedupEngine.TextHashCache()
    private val notifier = AutoBillNotifier(context)

    /** 串行化处理，避免无障碍与通知同时命中同一次支付时竞态建单。 */
    private val lock = Mutex()

    /**
     * 指纹 → (最近一次建单的通道, 时间)。
     *
     * 只在**真正建单**时更新（丢弃/放行都不动），用于识别「同一次支付被无障碍与通知各看到一次」：
     * 指纹相同 + 通道不同 + [DedupEngine.DOUBLE_SOURCE_WINDOW_MS] 内 → 静默合并；
     * 其余情况一律建单 + 提示。进程重启后为空 —— 宁可多提示一次，也不静默丢账。
     * 只在 [lock] 内访问，无需额外同步。
     */
    private val recentEmissions = HashMap<String, Pair<Origin, Long>>()

    /** 自动记账总开关（服务在遍历窗口/解析前先短路，省电）。 */
    val enabled: Boolean get() = container.settings.autoBillEnabled && container.settings.identityChosen

    data class ConfirmOverride(
        val assetId: String? = null,
        /** 转账的转入资产（只有 [Direction.TRANSFER] 用）。 */
        val toAssetId: String? = null,
        val categoryId: String? = null,
        val subCategoryId: String? = null,
        val occurredAt: Long? = null,
        val payerId: String? = null,
        val consumerId: String? = null,
        val bookId: String? = null,
        val relatedTxnId: String? = null,
        val recoveryKind: String? = null,
        val amountCents: Long? = null,
        val note: String? = null,
    )

    /** 快速确认页需要的模型。 */
    data class ConfirmUiModel(
        val pending: PendingBillEntity,
        val direction: Direction,
        val channel: Channel,
        val channelLabel: String?,
        val orderId: String?,
        /** 转账的转入资产线索（页面的「转入账户」），供确认页默认选中。 */
        val toAssetHint: String? = null,
    ) {
        val duplicate: Boolean get() = pending.status == PendingBillEntity.STATUS_DUPLICATE
        val categoryKind: String get() = if (direction == Direction.INCOME) "INCOME" else "EXPENSE"

        /** 自己账户之间的转账：确认页要多给一个「转入账户」选择器。 */
        val isTransfer: Boolean get() = direction == Direction.TRANSFER

        /** 已确认 / 已忽略：从「已记入」通知点进来时只做展示，不再给确认按钮。 */
        val resolved: Boolean
            get() = pending.status == PendingBillEntity.STATUS_CONFIRMED ||
                pending.status == PendingBillEntity.STATUS_IGNORED
    }

    suspend fun recordDebug(pkg: String, activity: String?, event: String, raw: String, nodes: String?, signal: PaySignal?) {
        if (!container.settings.debugCaptureEnabled) return
        container.ledger.logAutoBill("CAPTURE", entryJson = buildJsonObject {
            put("package", pkg); put("activity", activity); put("event", event)
            put("time", System.currentTimeMillis()); put("rawText", raw.take(12000))
            put("nodeTree", nodes); put("parser", signal?.pageType ?: if (raw.isBlank()) "EMPTY_WINDOW" else "UNRECOGNIZED")
            put("amountCents", signal?.amountCents); put("merchant", signal?.merchant)
            put("assetHint", signal?.suggestedAccountHint); put("personId", container.settings.myMemberId)
        }.toString())
    }

    // ---------- 入口 ----------

    suspend fun handle(signal: PaySignal, presentationAllowed: () -> Boolean = { true }) {
        try {
            lock.withLock { process(signal, presentationAllowed) }
        } catch (t: Throwable) {
            runCatching { container.ledger.logAutoBill("ERROR", entryJson = errorJson(t)) }
        } finally {
            // A repeated visit may create a new capture but reuse an existing attachment.
            // Remove only this call's unreferenced private file; retained bill photos are untouched.
            signal.receiptImage?.let { reference -> runCatching {
                lock.withLock {
                    val referenced = container.db.txnDao().all().any {
                        reference in com.family.ledger.attachments.ReceiptReference.fromPaths(it.imagePaths)
                    } || container.db.pendingBillDao().allPending().any {
                        PendingBillCodec.extrasOf(it.rawText)["image"] == reference
                    }
                    if (!referenced) com.family.ledger.attachments.ReceiptFiles(context).file(reference)?.delete()
                }
            } }
        }
    }

    private suspend fun process(signal: PaySignal, presentationAllowed: () -> Boolean) {
        val settings = container.settings
        val ledger = container.ledger
        if (!enabled) return
        if (signal.amountCents <= 0L) return

        val requestId = Ids.newId("ab-")
        ledger.logAutoBill(
            action = "RECEIVED",
            matchingMode = signal.channel.name,
            requestId = requestId,
            entryJson = signalJson(signal),
        )

        val at = signal.occurredAt
        val fingerprintScope = signal.sourcePackage + "|" + container.settings.deviceId + "|" + signal.direction.name
        val pendingDao = container.db.pendingBillDao()
        val txnDao = container.db.txnDao()

        // ---- 去重 ----
        val contentHashSeen = signal.rawEventId?.let {
            textCache.markAndCheck(DedupEngine.textHash(signal.sourcePackage + "|" + it))
        } ?: false
        val orderFp = signal.orderId?.takeIf { it.isNotBlank() }?.let { DedupEngine.orderFingerprint(signal.channel.name + "|" + signal.direction.name + "|" + it) }
        val fingerprint = orderFp
            ?: DedupEngine.fingerprint(fingerprintScope, signal.amountCents, signal.merchant, at)
        val candidateFps = if (orderFp != null) {
            listOf(orderFp)
        } else {
            DedupEngine.fingerprintCandidates(fingerprintScope, signal.amountCents, signal.merchant, at)
        }

        val existingTxn = txnDao.byFingerprint(fingerprint)
        // 使用交易字段判断同一回执；详情页广告、积分提示会变，不能要求整页原文一致。
        // 没有页面时间的通知仍按原有策略提示疑似重复，避免吞掉连续两次同金额支付。
        // 独立查明确时间，覆盖同金额连续付款导致第二笔指纹加盐的情况。
        val exactReceipt = if (signal.origin == Origin.ACCESSIBILITY &&
            signal.pageType?.endsWith("BillDetail") == true && signal.receiptTimeMillis == at) {
            pendingDao.receiptCandidates(signal.sourcePackage, signal.amountCents, at)
                .sortedBy { if (it.status == PendingBillEntity.STATUS_CONFIRMED) 0 else 1 }.firstOrNull {
                val oldText = PendingBillCodec.cleanRawText(it.rawText)
                val oldOrder = PendingBillCodec.extrasOf(it.rawText)["order"]
                val oldAsset = PendingBillCodec.assetHintOf(it.rawText)
                val sameMerchant = !signal.merchant.isNullOrBlank() &&
                    (it.merchant == signal.merchant || oldText.lineSequence().any { line -> line.trim() == signal.merchant })
                PendingBillCodec.pageTypeOf(it.rawText) == signal.pageType &&
                    PendingBillCodec.directionOf(it) == signal.direction &&
                    (oldOrder == null || signal.orderId == null || oldOrder == signal.orderId) &&
                    (oldAsset == null || signal.suggestedAccountHint == null || oldAsset == signal.suggestedAccountHint) &&
                    (oldText == signal.rawText || sameMerchant)
            }
        } else null
        val sameReceiptSnapshot = exactReceipt != null
        val existingPending = exactReceipt ?: candidateFps.firstNotNullOfOrNull { pendingDao.byFingerprint(it) }

        // 「同一次支付被两条通道各看到一次」：指纹相同 + 来源通道不同 + 时间很近。
        // 与上方明确时间的相同详情页一样可合并；无法确定时建单并提示。
        val lastEmission = candidateFps.firstNotNullOfOrNull { recentEmissions[it] }
            ?: existingPending?.let { p ->
                PendingBillCodec.extrasOf(p.rawText)["origin"]?.let { raw ->
                    runCatching { Origin.valueOf(raw) to p.occurredAt }.getOrNull()
                }
            }
        val crossChannelSamePayment = existingPending != null &&
            lastEmission != null &&
            signal.origin != Origin.UNKNOWN &&
            lastEmission.first != Origin.UNKNOWN &&
            lastEmission.first != signal.origin &&
            kotlin.math.abs(at - lastEmission.second) <= DedupEngine.DOUBLE_SOURCE_WINDOW_MS

        val orderHit = orderFp != null && (existingTxn != null || existingPending != null)

        // ---- 猜资产 / 猜分类 ----
        val rule = runCatching { ledger.guessCategory(signal.merchant, useExpenseOverride = signal.direction == Direction.PAYMENT) }.getOrNull()
        val asset = guessAsset(signal, rule?.assetId)
        val categoryId = rule?.categoryId ?: fallbackCategoryId(signal.direction)

        // 导入账单的旧指纹不包含微信订单号；账户必须来自页面，不能用默认账户猜中后丢账。
        val observedAssetId = resolveAssetId(signal.suggestedAccountHint)
        val importedReceipt = if (observedAssetId != null && signal.receiptTimeMillis == at &&
            signal.pageType?.endsWith("BillDetail") == true) {
            ImportedReceiptMatcher.match(signal, observedAssetId, settings.myMemberId,
                txnDao.observeBetween(at - ImportedReceiptMatcher.TIME_TOLERANCE_MS,
                    at + ImportedReceiptMatcher.TIME_TOLERANCE_MS + 1).first())
        } else null
        if (importedReceipt != null) {
            attachReceipt(importedReceipt.id, signal.receiptImage)
            if (existingPending != null && existingPending.status != PendingBillEntity.STATUS_CONFIRMED) {
                pendingDao.upsert(existingPending.copy(status = PendingBillEntity.STATUS_CONFIRMED,
                    txnId = importedReceipt.id, merchant = signal.merchant,
                    rawText = PendingBillCodec.encodeRawText(signal.rawText, pendingExtras(signal)),
                    updatedAt = System.currentTimeMillis()))
                notifier.cancel(existingPending.id)
            }
            ledger.logAutoBill("ALREADY_RECORDED", txnId = importedReceipt.id,
                matchingMode = "IMPORTED_RECEIPT", requestId = requestId, entryJson = "{}")
            if (settings.autoBillPopupEnabled && AutoBillPermission.canDrawOverlay(context))
                AutoBillPresentation.show(context, null, recordedKey = importedReceipt.id, allowed = presentationAllowed)
            return
        }

        val candidate = DedupEngine.Candidate(
            sourcePackage = signal.sourcePackage,
            amountCents = signal.amountCents,
            merchant = signal.merchant,
            channelLabel = signal.channelLabel,
            orderId = signal.orderId,
            occurredAt = at,
            assetId = asset?.id,
            fingerprint = fingerprint,
            orderFingerprint = orderFp,
        )
        val decision = DedupEngine.decide(
            DedupEngine.Facts(
                candidate = candidate,
                contentHashSeen = contentHashSeen,
                orderFingerprintHit = orderHit,
                pendingFingerprintHit = crossChannelSamePayment || sameReceiptSnapshot,
                txnFingerprintHit = existingTxn != null,
                pendingFingerprintTaken = existingPending != null && !crossChannelSamePayment,
                recent = recentComparables(at),
            )
        )

        if (decision.action == DedupEngine.Action.DROP) {
            ledger.logAutoBill(
                action = "DEDUPED",
                matchingMode = decision.mode.wire,
                requestId = requestId,
                entryJson = signalJson(signal),
            )
            // 待确认时重开完整面板；已经入账时仅提示，不再生成待确认或新流水。
            val confirmedPendingTxn = existingPending?.takeIf {
                it.status == PendingBillEntity.STATUS_CONFIRMED
            }?.txnId?.let { txnDao.byId(it) }
            val recordedTxn = when {
                orderHit -> existingTxn ?: confirmedPendingTxn
                sameReceiptSnapshot || crossChannelSamePayment -> confirmedPendingTxn
                else -> null
            }
            if (recordedTxn != null && !recordedTxn.deleted) {
                attachReceipt(recordedTxn.id, signal.receiptImage)
                ledger.logAutoBill("ALREADY_RECORDED", txnId = recordedTxn.id,
                    matchingMode = decision.mode.wire, entryJson = "{}")
                if (settings.autoBillPopupEnabled && AutoBillPermission.canDrawOverlay(context))
                    AutoBillPresentation.show(context, null, recordedKey = recordedTxn.id, allowed = presentationAllowed)
                return
            }
            val stillPending = existingPending?.takeIf {
                it.status == PendingBillEntity.STATUS_PENDING ||
                    it.status == PendingBillEntity.STATUS_DUPLICATE
            }
            if (stillPending != null) {
                if (signal.receiptImage != null && PendingBillCodec.extrasOf(stillPending.rawText)["image"] == null) {
                    pendingDao.upsert(stillPending.copy(rawText = PendingBillCodec.encodeRawText(
                        PendingBillCodec.cleanRawText(stillPending.rawText),
                        PendingBillCodec.extrasOf(stillPending.rawText) + ("image" to signal.receiptImage)),
                        updatedAt = System.currentTimeMillis()))
                }
                ledger.logAutoBill(
                    action = "RESHOW_PENDING",
                    pendingBillId = stillPending.id,
                    matchingMode = decision.mode.wire,
                    requestId = requestId,
                    entryJson = signalJson(signal),
                )
                notifier.notifyPending(stillPending, null)
                showConfirmation(stillPending, presentationAllowed)
            }
            return
        }

        // ---- 落待确认账单 ----
        // fingerprint 列有唯一索引：已被更早的账单占用时加盐，保证「第二笔真实消费」也能建单并提示用户
        val storedFingerprint = if (existingPending != null) saltedFingerprint(fingerprint, at) else fingerprint
        val now = System.currentTimeMillis()
        val pending = PendingBillEntity(
            id = Ids.newId("pb-"),
            sourcePackage = signal.sourcePackage,
            source = PendingBillCodec.txnSourceOf(signal.channel),
            amount = signal.amountCents,
            merchant = signal.merchant,
            occurredAt = at,
            rawText = PendingBillCodec.encodeRawText(signal.rawText, pendingExtras(signal)),
            fingerprint = storedFingerprint,
            guessedAssetId = asset?.id,
            guessedCategoryId = categoryId,
            guessedSubCategoryId = rule?.subCategoryId,
            status = if (decision.action == DedupEngine.Action.CREATE_FLAGGED) {
                PendingBillEntity.STATUS_DUPLICATE
            } else {
                PendingBillEntity.STATUS_PENDING
            },
            createdAt = now,
            updatedAt = now,
        )
        try {
            pendingDao.upsert(pending)
        } catch (t: Throwable) {
            // fingerprint 唯一索引冲突：另一条路径（无障碍 / 通知）已经建过单
            ledger.logAutoBill(
                action = "DEDUPED",
                matchingMode = DedupEngine.Mode.PENDING_FP.wire,
                requestId = requestId,
                entryJson = signalJson(signal),
            )
            return
        }
        // 记下「这个指纹最近一次是哪条通道建的、什么时候」
        // —— 下一次同指纹信号靠它判断是不是同一次支付的双通道上报
        rememberEmission(fingerprint, signal.origin, at)

        ledger.logAutoBill(
            action = "GUESSED",
            pendingBillId = pending.id,
            matchingMode = decision.mode.wire,
            requestId = requestId,
            entryJson = pendingJson(pending, asset?.name, decision),
        )

        // ---- 交互：直接打开完整记账面板，通知保留作为后续入口 ----
        notifier.notifyPending(pending, asset?.name)
        showConfirmation(pending, presentationAllowed)
    }

    /** 识别后直接打开完整记账面板；缺少权限时保留通知并提示授权。 */
    private fun showConfirmation(pending: PendingBillEntity, presentationAllowed: () -> Boolean) {
        val settings = container.settings
        val canDraw = AutoBillPermission.canDrawOverlay(context)
        val resolved = pending.status == PendingBillEntity.STATUS_CONFIRMED ||
            pending.status == PendingBillEntity.STATUS_IGNORED
        if (OverlayPolicy.shouldShow(settings.autoBillPopupEnabled, canDraw, resolved)) {
            AutoBillPresentation.show(context, pending.id, allowed = presentationAllowed)
            return
        }
        // 开关开着却弹不出来（没权限）→ 提示一次去开启；发不出去就留着下次再试
        if (OverlayPolicy.shouldHintMissingPermission(
                popupEnabled = settings.autoBillPopupEnabled,
                canDrawOverlay = canDraw,
                hintShown = settings.autoBillPopupHintShown,
            )
        ) {
            if (notifier.notifyOverlayPermissionHint()) settings.autoBillPopupHintShown = true
        }
    }

    // ---------- 确认 / 忽略 ----------

    /** 确认入账：建流水 → 学商户 → 回写待确认状态。幂等。 */
    suspend fun confirm(pendingId: String, override: ConfirmOverride = ConfirmOverride()): String? = try {
        lock.withLock { confirmInternal(pendingId, override, autoCommitted = false) }
    } catch (t: Throwable) {
        runCatching {
            container.ledger.logAutoBill("ERROR", pendingBillId = pendingId, entryJson = errorJson(t))
        }
        null
    }

    /** 关联退款的确认页保留具体校验错误；事务失败不改待确认状态。 */
    suspend fun confirmRecovery(pendingId: String, override: ConfirmOverride): String = lock.withLock {
        val pending = container.db.pendingBillDao().byId(pendingId) ?: error("待确认账单不存在")
        require(PendingBillCodec.directionOf(pending) == Direction.REFUND) { "识别结果已变化，请重新打开" }
        require(override.relatedTxnId != null) { "请先选择原支出" }
        confirmInternal(pendingId, override, autoCommitted = false) ?: error("未能保存，请重新打开确认")
    }

    private suspend fun confirmInternal(
        pendingId: String,
        override: ConfirmOverride,
        autoCommitted: Boolean,
    ): String? = container.db.withTransaction {
        val pendingDao = container.db.pendingBillDao()
        val ledger = container.ledger
        val pending = pendingDao.byId(pendingId) ?: return@withTransaction null
        // 幂等：重复点「确认」不会重复入账
        if (pending.status == PendingBillEntity.STATUS_CONFIRMED && pending.txnId != null) return@withTransaction pending.txnId
        if (pending.status == PendingBillEntity.STATUS_IGNORED) return@withTransaction null
        if (pending.amount <= 0L) return@withTransaction null

        val signal = PendingBillCodec.signalOf(pending)
        // 方向/渠道：页面规则引擎抽到的值写在 rawText 的标记里（纯文本可能二次解析不出来）
        val direction = PendingBillCodec.directionOf(pending)
        val channel = signal?.channel ?: Channel.of(pending.sourcePackage)
        val channelLabel = PendingBillCodec.channelLabelOf(pending)
        val couponCents = if (direction == Direction.REFUND) 0L else PendingBillCodec.couponOf(pending.rawText)
        val assetId = override.assetId ?: pending.guessedAssetId
        val preferredCategory = if (direction == Direction.PAYMENT && com.family.ledger.data.repo.LedgerRepository.hasCategoryOverride(pending.merchant))
            ledger.guessCategory(pending.merchant) else null
        val categoryId = override.categoryId ?: preferredCategory?.categoryId ?: pending.guessedCategoryId
        val subCategoryId = if (override.categoryId != null) override.subCategoryId
            else preferredCategory?.subCategoryId ?: pending.guessedSubCategoryId
        val occurredAt = override.occurredAt ?: pending.occurredAt
        val learned = ledger.guessCategory(pending.merchant)
        val preferredBook = override.bookId ?: learned?.bookId
        val book = preferredBook?.let { container.db.bookDao().byId(it) }?.takeIf { !it.deleted && !it.archived }
            ?: container.family.defaultBook()
        val flagged = pending.status == PendingBillEntity.STATUS_DUPLICATE

        // ---- 转账：必须同时有转出与转入资产，否则余额会算错 ----
        var toAssetId: String? = null
        if (direction == Direction.TRANSFER) {
            toAssetId = override.toAssetId
                ?: resolveAssetId(PendingBillCodec.toAssetHintOf(pending.rawText))
                    ?.takeIf { it != assetId }
            if (toAssetId == null) {
                // 认不出「转入账户」时**不入账**：宁可留在待确认里让用户点「改一下」选一次，
                // 也不能把一笔转账记成支出、或者只扣不加（余额直接错）。
                ledger.logAutoBill(
                    action = "TRANSFER_NO_TARGET",
                    pendingBillId = pending.id,
                    matchingMode = DedupEngine.Mode.NEW.wire,
                    entryJson = transferJson(pending, assetId, null),
                )
                return@withTransaction null
            }
        }

        val draft = TxnDraft(
            type = PendingBillCodec.txnTypeOf(direction),
            // 支付结果页展示实付金额；金额字段存券前金额，BalanceCalculator 再减优惠券一次。
            amountCents = Math.addExact(if (direction == Direction.REFUND) override.amountCents ?: pending.amount else pending.amount, couponCents),
            occurredAt = occurredAt,
            bookId = book.id,
            payerMemberId = override.payerId ?: container.settings.myMemberId,
            consumerMemberId = override.consumerId ?: learned?.consumerId ?: container.settings.myMemberId,
            assetId = assetId,
            toAssetId = toAssetId,
            categoryId = categoryId,
            subCategoryId = subCategoryId,
            merchant = pending.merchant,
            note = override.note ?: PendingBillCodec.noteFor(channelLabel, flagged),
            relatedTxnId = if (direction == Direction.REFUND) override.relatedTxnId else null,
            recoveryKind = if (direction == Direction.REFUND) override.recoveryKind ?: "REFUND" else null,
            // 页面上逐条累加出来的优惠券（钱迹的 feeAmount），进账本后统计口径才对得上
            couponCents = couponCents,
            source = PendingBillCodec.txnSourceOf(channel),
            sourceFingerprint = pending.fingerprint,
            imagePaths = listOfNotNull(PendingBillCodec.extrasOf(pending.rawText)["image"]),
            transactionId = "t-auto-" + java.util.UUID.nameUUIDFromBytes(
                (container.settings.deviceId + ":" + pending.id).toByteArray(Charsets.UTF_8)).toString(),
        )
        val txn = ledger.save(draft)

        // 学习「这个商户记到哪个分类/资产」，下次同商户直接带出。
        // 转账跳过：对方账户不是「商户」，学出来只会污染以后真正的支出猜测。
        if (direction != Direction.TRANSFER && direction != Direction.REFUND) {
            ledger.learnMerchant(pending.merchant, categoryId, subCategoryId, assetId, book.id, draft.consumerMemberId)
        }

        pendingDao.upsert(
            pending.copy(
                status = PendingBillEntity.STATUS_CONFIRMED,
                txnId = txn.id,
                guessedAssetId = assetId,
                guessedCategoryId = categoryId,
                guessedSubCategoryId = subCategoryId,
                updatedAt = System.currentTimeMillis(),
            )
        )
        ledger.logAutoBill(
            action = "CONFIRMED",
            pendingBillId = pending.id,
            txnId = txn.id,
            matchingMode = if (flagged) DedupEngine.Mode.SAME_DAY_AMOUNT.wire else DedupEngine.Mode.NEW.wire,
            entryJson = txnJson(txn, autoCommitted),
        )

        if (autoCommitted) {
            notifier.notifySaved(pending.id, pending.amount, pending.merchant, assetNameOf(assetId))
        } else {
            notifier.cancel(pending.id)
        }
        // 这笔已经处理完 → 撤掉可能还浮着的卡片（用户从通知动作/确认页进来时会走到这里）
        AutoBillOverlay.dismiss(pending.id)
        txn.id
    }

    /** 忽略：标记 IGNORED，不建流水。 */
    suspend fun ignore(pendingId: String): Boolean = try {
        lock.withLock {
            val dao = container.db.pendingBillDao()
            val p = dao.byId(pendingId) ?: return@withLock false
            if (p.status == PendingBillEntity.STATUS_CONFIRMED) return@withLock false
            dao.upsert(p.copy(status = PendingBillEntity.STATUS_IGNORED, updatedAt = System.currentTimeMillis()))
            container.ledger.logAutoBill(
                action = "IGNORED",
                pendingBillId = p.id,
                matchingMode = DedupEngine.Mode.NEW.wire,
                entryJson = "{}",
            )
            notifier.cancel(p.id)
            // 用户已忽略 → 卡片也要撤掉（可能是从通知动作进来的）
            AutoBillOverlay.dismiss(p.id)
            true
        }
    } catch (t: Throwable) {
        false
    }

    /** 快速确认页的数据。 */
    suspend fun loadConfirmModel(pendingId: String): ConfirmUiModel? = withContext(Dispatchers.Default) {
        val stored = runCatching { container.db.pendingBillDao().byId(pendingId) }.getOrNull()
            ?: return@withContext null
        val preferred = if (stored.status in listOf(PendingBillEntity.STATUS_PENDING, PendingBillEntity.STATUS_DUPLICATE) &&
            PendingBillCodec.directionOf(stored) == Direction.PAYMENT && com.family.ledger.data.repo.LedgerRepository.hasCategoryOverride(stored.merchant))
            container.ledger.guessCategory(stored.merchant) else null
        val p = if (preferred == null) stored else stored.copy(guessedCategoryId = preferred.categoryId, guessedSubCategoryId = preferred.subCategoryId)
        val signal = PendingBillCodec.signalOf(p)
        ConfirmUiModel(
            pending = p,
            direction = PendingBillCodec.directionOf(p),
            channel = signal?.channel ?: Channel.of(p.sourcePackage),
            channelLabel = PendingBillCodec.channelLabelOf(p),
            orderId = signal?.orderId,
            toAssetHint = PendingBillCodec.toAssetHintOf(p.rawText),
        )
    }

    /** A recognized existing receipt can acquire its first photo without creating another bill. */
    private suspend fun attachReceipt(txnId: String, reference: String?) {
        if (reference == null) return
        container.db.withTransaction {
            val current = container.ledger.byId(txnId) ?: return@withTransaction
            if (current.deleted || com.family.ledger.attachments.ReceiptReference.fromPaths(current.imagePaths).isNotEmpty()) return@withTransaction
            container.ledger.update(current.copy(imagePaths = listOfNotNull(current.imagePaths?.takeIf { it.isNotBlank() }, reference).joinToString(",")))
        }
    }

    // ---------- 猜测 ----------

    /**
     * 猜资产：学过的规则 > **页面直接给出的付款方式（firstAsset）** > 家庭共享资产优先 >
     * 渠道名匹配 > 本机成员名匹配。
     *
     * 页面方式来自规则引擎的 `firstAsset`（写在 `signal.suggestedAccountHint`），
     * 由 [AssetHintMatcher] 打分（≥200），一定压过下面那些 ≤30 的兜底加分 ——
     * 这就是「付款资产直接从页面取，不用猜」。
     */
    private suspend fun guessAsset(signal: PaySignal, ruleAssetId: String?): AssetEntity? {
        val assets = runCatching { container.assets.all() }.getOrNull().orEmpty()
            .filter { !it.archived && !it.deleted }
        if (assets.isEmpty()) return null
        val explicit = signal.suggestedAccountHint?.takeIf { it.isNotBlank() }
        if (explicit != null) {
            val matched = assets.maxByOrNull { AssetHintMatcher.score(it.name, explicit) }
            if (matched != null && AssetHintMatcher.score(matched.name, explicit) >= 200) return matched
        }
        if (signal.sourcePackage == PayPackages.ICBC) return null
        if (ruleAssetId != null) assets.firstOrNull { it.id == ruleAssetId }?.let { return it }

        val hint = signal.suggestedAccountHint
        val channelKeyword = when (signal.channel) {
            Channel.ALIPAY -> "支付宝"
            Channel.WECHAT -> "微信"
            Channel.UNIONPAY -> "银联"
            Channel.UNKNOWN -> null
        }
        val myName = container.settings.myDisplayName

        var best: AssetEntity? = null
        var bestScore = 0
        for (a in assets) {
            var score = AssetHintMatcher.score(a.name, hint)
            if (a.ownerType == OwnerType.FAMILY) score += SCORE_FAMILY
            if (channelKeyword != null && a.name.contains(channelKeyword)) score += SCORE_CHANNEL
            if (myName.isNotBlank() && a.name.contains(myName)) score += SCORE_SELF
            if (score > bestScore) {
                bestScore = score
                best = a
            }
        }
        return best ?: assets.firstOrNull { it.ownerType == OwnerType.FAMILY }
    }

    /** 猜不到分类时的兜底：支出→「其它」，收入→「收入」（种子数据里都有）。 */
    private suspend fun fallbackCategoryId(direction: Direction): String? {
        val kind = if (direction == Direction.INCOME) "INCOME" else "EXPENSE"
        val name = if (direction == Direction.INCOME) "收入" else "其它"
        return runCatching { container.db.categoryDao().topLevelByName(kind, name)?.id }.getOrNull()
    }

    /** 第二层判重的对照集：附近 5 分钟的流水 + 最近 20 条待确认账单。 */
    private suspend fun recentComparables(at: Long): List<DedupEngine.Existing> {
        val out = ArrayList<DedupEngine.Existing>()
        val from = at - COMPARE_LOOKBACK_MS
        val to = at + 60_000L

        runCatching { container.db.txnDao().observeBetween(from, to).first() }
            .getOrNull().orEmpty().forEach { t ->
                if (t.deleted) return@forEach
                out += DedupEngine.Existing(
                    id = t.id,
                    sourcePackage = null,
                    amountCents = t.amount,
                    merchant = t.merchant,
                    channelLabel = PendingBillCodec.channelLabelOf(t),
                    occurredAt = t.occurredAt,
                    assetId = t.assetId,
                )
            }

        container.db.pendingBillDao().recent(RECENT_PENDING_LIMIT).forEach { p ->
            if (p.status == PendingBillEntity.STATUS_IGNORED) return@forEach
            if (p.occurredAt < from || p.occurredAt > to) return@forEach
            out += DedupEngine.Existing(
                id = p.id,
                sourcePackage = p.sourcePackage,
                amountCents = p.amount,
                merchant = p.merchant,
                channelLabel = PendingBillCodec.channelLabelOf(p),
                occurredAt = p.occurredAt,
                assetId = p.guessedAssetId,
            )
        }
        return out
    }

    /**
     * 把页面上给的账户线索（转账页的「转入账户」，如 `中国工商银行(8804)`）匹配成账本里的资产 id。
     *
     * 复用 [AssetHintMatcher]：全角/半角括号、银行卡尾号 4 位都能对上（用户资产名里两种括号都有）。
     */
    private suspend fun resolveAssetId(hint: String?): String? {
        if (hint.isNullOrBlank()) return null
        val assets = runCatching { container.assets.all() }.getOrNull().orEmpty()
            .filter { !it.archived && !it.deleted }
        var best: AssetEntity? = null
        var bestScore = 0
        for (a in assets) {
            val score = AssetHintMatcher.score(a.name, hint)
            if (score > bestScore) {
                bestScore = score
                best = a
            }
        }
        return best?.id
    }

    private suspend fun assetNameOf(assetId: String?): String? {
        if (assetId.isNullOrBlank()) return null
        return runCatching { container.assets.byId(assetId)?.name }.getOrNull()
    }

    /** 记录一次建单（只保留最近 64 条 / 10 分钟，避免无界增长）。 */
    private fun rememberEmission(fingerprint: String, origin: Origin, at: Long) {
        recentEmissions[fingerprint] = origin to at
        if (recentEmissions.size <= EMISSION_CACHE_MAX) return
        val cutoff = at - EMISSION_CACHE_TTL_MS
        val it = recentEmissions.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value.second < cutoff) it.remove()
        }
        if (recentEmissions.size > EMISSION_CACHE_MAX) {
            recentEmissions.entries.sortedByDescending { it.value.second }
                .drop(EMISSION_CACHE_MAX)
                .forEach { recentEmissions.remove(it.key) }
        }
    }

    /**
     * 指纹已被占用时加盐。
     * `fingerprint` 列有唯一索引，加盐后才能为「第二笔真实消费」建单（状态为 DUPLICATE，提示用户）。
     */
    private fun saltedFingerprint(fingerprint: String, at: Long): String =
        "$fingerprint#${at / 1000}-${Ids.newId().takeLast(4)}"

    // ---------- 页面规则引擎遗留字段 ----------

    /**
     * 页面规则引擎抽到、但 `PendingBillEntity` 没有对应列的字段。
     *
     * 这些值跟着 `rawText` 的标记一起落库，确认入账时再取回来（方向/渠道/优惠券/页面类型）。
     * **文本兜底路线（通知）不写标记**：它的纯文本本来就能二次解析，保持老行为不变。
     */
    private fun pendingExtras(signal: PaySignal): Map<String, String> {
        val extras = LinkedHashMap<String, String>()
        extras["origin"] = signal.origin.name
        signal.receiptImage?.let { extras["image"] = it }
        extras[PendingBillCodec.KEY_DIRECTION] = signal.direction.name
        signal.orderId?.let { extras["order"] = it }
        signal.pageType?.takeIf { it.isNotBlank() }?.let { page ->
            extras[PendingBillCodec.KEY_PAGE] = page
            extras[PendingBillCodec.KEY_DIRECTION] = signal.direction.name
            signal.channelLabel?.takeIf { it.isNotBlank() }
                ?.let { extras[PendingBillCodec.KEY_CHANNEL_LABEL] = it }
            signal.suggestedAccountHint?.takeIf { it.isNotBlank() }
                ?.let { extras[PendingBillCodec.KEY_ASSET_HINT] = it }
        }
        // 转账的转入账户：实体表没有对应的列，靠标记带到确认入账那一刻
        if (signal.direction == Direction.TRANSFER) {
            signal.toAccountHint?.takeIf { it.isNotBlank() }
                ?.let { extras[PendingBillCodec.KEY_TO_ACCOUNT] = it }
            extras[PendingBillCodec.KEY_DIRECTION] = Direction.TRANSFER.name
        }
        if (signal.couponCents > 0L) {
            extras[PendingBillCodec.KEY_COUPON] = signal.couponCents.toString()
        }
        return extras
    }

    /**
     * 未识别页面的节点树 dump —— **远程排障的唯一手段**（用户手机不方便连 adb）。
     *
     * 由 [AutoBillAccessibilityService] 在「包名是我们关注的支付 App + 页面像支付页 + 规则与文本都没识别出来」
     * 时调用一次；节点树已由调用方截断（见 [AutoBillRules.dumpJson]）。
     */
    suspend fun logUnknownPage(
        packageName: String,
        pageText: String,
        nodesJson: String,
        reason: String = "UNRECOGNIZED",
        requestId: String? = null,
    ) {
        runCatching {
            container.ledger.logAutoBill(
                action = "PAGE_NODES",
                matchingMode = reason,
                requestId = requestId,
                entryJson = buildJsonObject {
                    put("pkg", packageName)
                    put("text", pageText.take(PAGE_DUMP_TEXT_LIMIT))
                    put("nodes", nodesJson)
                }.toString(),
            )
        }
    }

    // ---------- 日志 JSON ----------

    private fun signalJson(signal: PaySignal): String = buildJsonObject {
        put("pkg", signal.sourcePackage)
        put("amount", signal.amountCents)
        put("merchant", signal.merchant ?: "")
        put("channel", signal.channel.name)
        put("channelLabel", signal.channelLabel ?: "")
        put("direction", signal.direction.name)
        put("hint", signal.suggestedAccountHint ?: "")
        put("orderId", signal.orderId ?: "")
        put("at", signal.occurredAt)
        // 页面规则引擎的产出（没有就是 null —— 说明走的是通知/文本兜底）
        put("pageType", signal.pageType ?: "")
        put("coupon", signal.couponCents)
        put("text", signal.rawText.take(200))
    }.toString()

    private fun pendingJson(
        p: PendingBillEntity,
        assetName: String?,
        decision: DedupEngine.Result,
    ): String = buildJsonObject {
        put("pendingId", p.id)
        put("status", p.status)
        put("amount", p.amount)
        put("merchant", p.merchant ?: "")
        put("asset", assetName ?: "")
        put("assetHint", PendingBillCodec.assetHintOf(p.rawText) ?: "")
        put("pageType", PendingBillCodec.pageTypeOf(p.rawText) ?: "")
        put("coupon", PendingBillCodec.couponOf(p.rawText))
        put("direction", PendingBillCodec.directionOf(p).name)
        put("toHint", PendingBillCodec.toAssetHintOf(p.rawText) ?: "")
        put("categoryId", p.guessedCategoryId ?: "")
        put("subCategoryId", p.guessedSubCategoryId ?: "")
        put("fingerprint", p.fingerprint)
        put("mode", decision.mode.wire)
        put("duplicate", decision.duplicatePrompt)
        put("relatedId", decision.relatedId ?: "")
    }.toString()

    private fun txnJson(t: TxnEntity, autoCommitted: Boolean): String = buildJsonObject {
        put("txnId", t.id)
        put("type", t.type.name)
        put("amount", t.amount)
        put("assetId", t.assetId ?: "")
        put("categoryId", t.categoryId ?: "")
        put("merchant", t.merchant ?: "")
        put("coupon", t.coupon)
        put("fingerprint", t.sourceFingerprint ?: "")
        put("autoCommitted", autoCommitted)
    }.toString()

    /** 转账入账/失败时的日志 JSON（排障用：转出转入资产都能看到）。 */
    private fun transferJson(p: PendingBillEntity, fromAssetId: String?, toAssetId: String?): String =
        buildJsonObject {
            put("pendingId", p.id)
            put("amount", p.amount)
            put("merchant", p.merchant ?: "")
            put("fromAssetId", fromAssetId ?: "")
            put("toAssetId", toAssetId ?: "")
            put("toHint", PendingBillCodec.toAssetHintOf(p.rawText) ?: "")
            put("pageType", PendingBillCodec.pageTypeOf(p.rawText) ?: "")
        }.toString()

    private fun errorJson(t: Throwable): String = buildJsonObject {
        put("error", t::class.java.simpleName)
        put("message", t.message?.take(200) ?: "")
    }.toString()

    companion object {
        /** 自动提交要求的最少商户规则命中次数。 */
        const val AUTO_COMMIT_MIN_HITS = 3

        private const val EMISSION_CACHE_MAX = 64
        private const val EMISSION_CACHE_TTL_MS = 10 * 60 * 1000L

        private const val COMPARE_LOOKBACK_MS = 5 * 60 * 1000L
        private const val RECENT_PENDING_LIMIT = 20

        /** 未识别页面 dump 时保留的页面文本长度。 */
        private const val PAGE_DUMP_TEXT_LIMIT = 400

        private const val SCORE_FAMILY = 30
        private const val SCORE_CHANNEL = 20
        private const val SCORE_SELF = 15

        @Volatile private var instance: AutoBillPipeline? = null
        private val LOCK = Any()

        /** 进程内单例；容器未就绪时返回 null（调用方按「静默失败」处理）。 */
        fun getOrNull(context: Context): AutoBillPipeline? {
            instance?.let { return it }
            return synchronized(LOCK) {
                instance?.let { return it }
                val app = context.applicationContext as? FamilyLedgerApp ?: return null
                val container = runCatching { app.container }.getOrNull() ?: return null
                AutoBillPipeline(app, container).also { instance = it }
            }
        }
    }
}
