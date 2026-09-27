package com.family.ledger.data.repo

import android.content.Context
import android.os.Build
import androidx.room.withTransaction
import com.family.ledger.core.FixedPeople
import kotlinx.coroutines.flow.map
import com.family.ledger.core.Ids
import com.family.ledger.data.SettingsStore
import com.family.ledger.data.db.AppDatabase
import com.family.ledger.data.db.entity.*
import com.family.ledger.data.sync.SyncJournal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SeedCategory(val name: String, val kind: String, val children: List<String> = emptyList())

@Serializable
data class SeedAsset(
    val name: String,
    val type: String,
    val ownerType: String,
    val group: String? = null,
)

@Serializable
data class SeedFile(
    val note: String = "",
    val categories: List<SeedCategory> = emptyList(),
    val assets: List<SeedAsset> = emptyList(),
)

/**
 * 家庭与初始化。
 *
 * 首启做四件事：
 *   1. 建家庭（默认「我们家」）
 *   2. 把本机登记为「我」这个成员
 *   3. 建默认账本
 *   4. 用仓库内虚构示例播种默认分类与账户
 */
class FamilyRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val journal: SyncJournal,
    private val settings: SettingsStore,
) {
    /** 串行化 bootstrap：启动时的异步 bootstrap 与「选身份」时的改名会并发。 */
    private val bootstrapLock = Mutex()

    private val familyDao get() = db.familyDao()
    private val memberDao get() = db.familyMemberDao()
    private val bookDao get() = db.bookDao()
    private val categoryDao get() = db.categoryDao()
    private val assetDao get() = db.assetDao()

    fun observeFamily(): Flow<FamilyEntity?> = familyDao.observeCurrent()
    fun observeMembers(): Flow<List<FamilyMemberEntity>> = memberDao.observeAll()

    suspend fun family(): FamilyEntity? = familyDao.current()
    suspend fun members(): List<FamilyMemberEntity> = memberDao.all()
    /**
     * 本机使用者。优先按 deviceId（可靠），deviceId 为空时退回 isMe 标记。
     * 详见 `FamilyMemberDao.byDeviceId` 的注释：isMe 会随同步串台，不能当唯一依据。
     */
    suspend fun me(): FamilyMemberEntity? =
        settings.myMemberId?.let { memberDao.byId(it) }
    suspend fun books(): List<BookEntity> = bookDao.all()

    suspend fun defaultBook(): BookEntity = db.withTransaction {
        // ① 先按**确定性 ID** 找：两台设备各自 bootstrap 时必须命中同一本，
        //    否则同步合并后会出现两本「日常账本」（真机实测踩到过）。
        bookDao.byId(defaultBookId())?.let { return@withTransaction it }
        // ② 兼容已经在用的老数据：本机已有账本就用第一本，不要凭空再造第三本。
        bookDao.all().firstOrNull()?.let { return@withTransaction it }
        createBook("日常账本", OwnerType.FAMILY, id = defaultBookId())
    }

    /**
     * 默认账本的确定性 ID。种子分类/资产早就是确定性的（见 [seedId]），
     * 账本当初漏了 —— 于是手机和平板各建一本、同步后变成两本。
     */
    private fun defaultBookId(): String = seedId("sb-", "日常账本", OwnerType.FAMILY.name)

    suspend fun createBook(name: String, ownerType: OwnerType, id: String? = null): BookEntity = db.withTransaction {
        val now = System.currentTimeMillis()
        val book = BookEntity(
            id = id ?: Ids.newId("b-"),
            name = name,
            ownerType = ownerType,
            ownerUserId = if (ownerType == OwnerType.USER) settings.myMemberId else null,
            ownerFamilyId = if (ownerType == OwnerType.FAMILY) settings.familyId else null,
            createdAt = now,
            updatedAt = now,
        )
        bookDao.upsert(book)
        journal.record(book, hlc = if (book.id == defaultBookId()) 0L else null)
        book
    }

    suspend fun selectPerson(personId: String) {
        FixedPeople.requireId(personId)
        val previous = settings.myMemberId
        val previousName = settings.myDisplayName
        settings.myMemberId = personId
        settings.myDisplayName = FixedPeople.name(personId)
        try {
            ensureBootstrap()
            settings.identityChosen = true
        } catch (e: Exception) {
            settings.myMemberId = previous
            settings.myDisplayName = previousName
            throw e
        }
    }

    /** 兼容旧调用，仅接受两个固定名字。 */
    suspend fun setMyName(name: String) = selectPerson(
        FixedPeople.idForName(name) ?: error("只支持用户 A和用户 B")
    )

    /** 历史导入不能创建第三个人；未知记账者交由导入预览报告。 */
    suspend fun addMember(name: String, deviceId: String? = null, isMe: Boolean = false): FamilyMemberEntity {
        val id = FixedPeople.idForName(name) ?: error("未识别的记账者：$name，请映射到用户 A或用户 B")
        ensureBootstrap()
        return memberDao.byId(id) ?: error("人物尚未初始化")
    }

    fun observeDevices() = db.deviceDao().observeAll()

    private suspend fun ensurePeople(familyId: String, now: Long): FamilyMemberEntity {
        val selected = settings.myMemberId?.takeIf { it in FixedPeople.names }
            ?: FixedPeople.idForName(settings.myDisplayName)
            ?: error("请先选择这是谁的设备")
        for ((id, name) in FixedPeople.names) {
            val old = memberDao.byId(id)
            if (old == null) {
                val person = FamilyMemberEntity(id, familyId, name, null, false, 0L, 0L)
                memberDao.upsert(person)
                journal.record(person, hlc = 0L)
            }
        }
        // 兼容已有设备人物：合并身份引用、保留旧行墓碑及全部账单，不猜未知名字。
        val aliases = memberDao.allIncludingDeleted().mapNotNull { old ->
            val target = FixedPeople.idForName(old.displayName)
            if (target != null && target != old.id) old.id to target else null
        }.toMap()
        if (aliases.isNotEmpty()) {
            for (t in db.txnDao().allIncludingDeleted()) {
                val updated = t.copy(recorderMemberId = aliases[t.recorderMemberId] ?: t.recorderMemberId,
                    payerMemberId = aliases[t.payerMemberId] ?: t.payerMemberId,
                    consumerMemberId = aliases[t.consumerMemberId] ?: t.consumerMemberId)
                if (updated != t) { val migrated = updated.copy(updatedAt = now); db.txnDao().upsert(migrated); journal.record(migrated) }
            }
            for (a in assetDao.allIncludingDeleted()) {
                aliases[a.ownerUserId]?.let { id ->
                    val migrated = a.copy(ownerUserId = id, updatedAt = now)
                    assetDao.upsert(migrated); journal.record(migrated)
                }
            }
            for (b in bookDao.allIncludingDeleted()) {
                aliases[b.ownerUserId]?.let { id ->
                    val migrated = b.copy(ownerUserId = id, updatedAt = now)
                    bookDao.upsert(migrated); journal.record(migrated)
                }
            }
            for (old in memberDao.allIncludingDeleted().filter { it.id in aliases }) {
                old.deviceId?.let { deviceId ->
                    if (db.deviceDao().byId(deviceId) == null) {
                        val d = DeviceEntity(deviceId, aliases.getValue(old.id), old.displayName + "的设备", old.joinedAt, now)
                        db.deviceDao().upsert(d); journal.record(d)
                    }
                }
                if (!old.deleted) { val tombstone = old.copy(deleted = true, updatedAt = now); memberDao.upsert(tombstone); journal.recordDelete(tombstone) }
            }
        }
        settings.myMemberId = selected
        settings.myDisplayName = FixedPeople.name(selected)
        if (settings.myDeviceName.isBlank()) settings.myDeviceName = Build.MODEL ?: "Android 设备"
        val current = db.deviceDao().byId(settings.deviceId)
        if (current == null || current.personId != selected || current.name != settings.myDeviceName) {
            val device = DeviceEntity(settings.deviceId, selected, settings.myDeviceName, current?.createdAt ?: now, now)
            db.deviceDao().upsert(device); journal.record(device)
        }
        return memberDao.byId(selected)!!
    }

    data class BootstrapResult(val family: FamilyEntity, val me: FamilyMemberEntity, val book: BookEntity, val seededCategories: Int, val seededAssets: Int)

    /** 当前家庭配对码（两台手机输入同一个码 = 同一个家庭）。 */
    fun familyCode(): String? = settings.familyId

    data class AdoptResult(
        val familyId: String,
        val repointedAssets: Int,
        val repointedBooks: Int,
        val repointedMembers: Int,
    )

    /**
     * 加入配偶的家庭：输入对方手机上显示的配对码。
     *
     * 两台手机各自首启时会生成各自的家庭 id，必须靠这一步统一，
     * 否则 WebDAV 上会各写各的目录，永远同步不到一起。
     *
     * 本机已有的家庭范围数据（家庭资产 / 家庭账本 / 成员）会改挂到新家庭，
     * 旧家庭行打墓碑软删除，避免同步过去变成野数据。
     */
    suspend fun adoptFamilyCode(rawCode: String, familyName: String = "我们家"): AdoptResult {
        val code = Ids.normalizeFamilyCode(rawCode)
            ?: return AdoptResult("", 0, 0, 0)
        val oldId = settings.familyId
        if (code == oldId) return AdoptResult(code, 0, 0, 0)

        val now = System.currentTimeMillis()
        val fam = FamilyEntity(id = code, name = familyName, createdAt = now, updatedAt = now)
        familyDao.upsert(fam)
        journal.record(fam)

        val touched = mutableListOf<Any>()

        var assets = 0
        for (a in assetDao.all()) {
            if (a.ownerType == OwnerType.FAMILY && (a.ownerFamilyId == null || a.ownerFamilyId == oldId)) {
                val u = a.copy(ownerFamilyId = code, updatedAt = now)
                assetDao.upsert(u); touched += u; assets++
            }
        }

        var books = 0
        for (b in bookDao.all()) {
            if (b.ownerType == OwnerType.FAMILY && (b.ownerFamilyId == null || b.ownerFamilyId == oldId)) {
                val u = b.copy(ownerFamilyId = code, updatedAt = now)
                bookDao.upsert(u); touched += u; books++
            }
        }

        var members = 0
        for (m in memberDao.all()) {
            if (m.familyId != code) {
                val u = m.copy(familyId = code, updatedAt = now)
                memberDao.upsert(u); touched += u; members++
            }
        }

        if (touched.isNotEmpty()) journal.recordAll(touched)

        // 旧家庭打墓碑
        if (oldId != null && oldId != code) {
            for (old in familyDao.all().filter { it.id == oldId }) {
                val d = old.copy(deleted = true, updatedAt = now)
                familyDao.upsert(d)
                journal.recordDelete(d)
            }
        }

        settings.familyId = code
        return AdoptResult(code, assets, books, members)
    }

    suspend fun ensureBootstrap(defaultMemberName: String = "我"): BootstrapResult =
        bootstrapLock.withLock {
            if (settings.myDisplayName.isBlank() && FixedPeople.idForName(defaultMemberName) != null)
                settings.myDisplayName = defaultMemberName
            db.withTransaction { ensureBootstrapLocked(defaultMemberName) }
        }

    private suspend fun ensureBootstrapLocked(defaultMemberName: String): BootstrapResult {
        val now = System.currentTimeMillis()
        var fam = familyDao.current()
        // 已配对过（settings 里有 familyId）但本地还没这行：用同一个 id 补出来，绝不新建
        if (fam == null) {
            val known = settings.familyId
            fam = if (known != null) {
                FamilyEntity(id = known, name = "我们家", createdAt = now, updatedAt = now)
            } else {
                FamilyEntity(id = FixedPeople.HOUSEHOLD, name = "我们家", createdAt = now, updatedAt = now)
            }
        }
        if (familyDao.current() == null) { familyDao.upsert(fam); journal.record(fam, hlc = 0L) }
        settings.familyId = fam.id

        val me = ensurePeople(fam.id, now)

        val book = defaultBook()

        var seededCategories = 0
        var seededAssets = 0
        // ★ 守卫不能只看「表空不空」：首次写 146 条要好几秒，用户中途划掉 App 会留下
        //   「只写了一部分」的状态，而 count != 0 会让下次启动**永不补种**（用户反馈过）。
        //   改成「没完整跑完就重跑」—— 种子 id 由内容确定性推导，写入是幂等 upsert。
        if (!settings.bootstrapSeeded || categoryDao.count() == 0 || assetDao.all().isEmpty()) {
            val seed = loadSeed()
            if (seed != null) {
                seededCategories = seedCategories(seed, now)
                seededAssets = seedAssets(seed, fam.id, now)
                settings.bootstrapSeeded = true
            }
        }
        settings.bootstrapDone = true
        return BootstrapResult(fam, me, book, seededCategories, seededAssets)
    }

    private fun loadSeed(): SeedFile? = runCatching {
        context.assets.open("sample_seed.json").use { input ->
            Json { ignoreUnknownKeys = true }.decodeFromString(SeedFile.serializer(), input.readBytes().decodeToString())
        }
    }.getOrNull()

    private suspend fun seedCategories(seed: SeedFile, now: Long): Int {
        var count = 0
        var order = 0
        for (c in seed.categories) {
            val parent = CategoryEntity(
                id = seedId("sc-", c.kind, c.name),
                name = c.name,
                parentId = null,
                kind = c.kind,
                sortOrder = order++,
                updatedAt = now,
            )
            if (categoryDao.allIncludingDeleted().none { it.id == parent.id }) { categoryDao.upsert(parent); journal.record(parent, hlc = 0L) }
            count++
            var childOrder = 0
            for (child in c.children) {
                val sub = CategoryEntity(
                    id = seedId("sc-", c.kind, c.name, child),
                    name = child,
                    parentId = parent.id,
                    kind = c.kind,
                    sortOrder = childOrder++,
                    updatedAt = now,
                )
                if (categoryDao.allIncludingDeleted().none { it.id == sub.id }) { categoryDao.upsert(sub); journal.record(sub, hlc = 0L) }
                count++
            }
        }
        return count
    }

    private suspend fun seedAssets(seed: SeedFile, familyId: String, now: Long): Int {
        var count = 0
        var order = 0
        for (a in seed.assets) {
            val owner = runCatching { OwnerType.valueOf(a.ownerType) }.getOrDefault(OwnerType.USER)
            val type = runCatching { AssetType.valueOf(a.type) }.getOrDefault(AssetType.OTHER)
            val asset = AssetEntity(
                id = seedId("sa-", a.name),
                name = a.name,
                ownerType = owner,
                ownerUserId = if (owner == OwnerType.USER) { if (a.name.contains(FixedPeople.name(FixedPeople.B))) FixedPeople.B else FixedPeople.A } else null,
                ownerFamilyId = if (owner == OwnerType.FAMILY) familyId else null,
                type = type,
                groupName = a.group ?: if (owner == OwnerType.FAMILY) AssetRepository.GROUP_FAMILY else AssetRepository.GROUP_PERSONAL,
                sharedForFamily = owner == OwnerType.FAMILY,
                sortOrder = order++,
                createdAt = now,
                updatedAt = now,
            )
            if (assetDao.byId(asset.id) == null) { assetDao.upsert(asset); journal.record(asset, hlc = 0L) }
            count++
        }
        return count
    }

    companion object {
        /**
         * 种子数据的**确定性 ID**。
         *
         * 为什么不能用随机 ID：两台手机各自首启都会播种同一批分类/账户。
         * 如果用随机 ID，配对同步后 LWW 无法把它们合并，用户会看到
         * **双份分类、双份账户**，而且两台手机各记的账会挂在各自的账户 id 上，
         * 「同一份家庭共享资产」就散了。
         *
         * 改成由内容推导的 ID 后，两台手机对「吃 / 外卖」「支付宝小荷包(示例日常)」
         * 生成完全相同的 id，同步时按 id 合并成一条，流水也自然汇到同一个资产上。
         *
         * `String.hashCode()` 的行为由 Java 语言规范固定，Android/ART 与 JVM 一致，
         * 所以两端结果必然相同。前面拼一段可读 slug 是为了排障时能一眼看出是什么。
         */
        private fun seedId(prefix: String, vararg parts: String): String {
            val slug = parts.joinToString("-") { p ->
                p.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
            }.take(48)
            val digest = parts.joinToString("|").hashCode().toUInt().toString(36)
            return "$prefix$slug-$digest"
        }
    }
}
