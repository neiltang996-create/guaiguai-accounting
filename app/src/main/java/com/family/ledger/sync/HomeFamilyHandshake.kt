package com.family.ledger.sync

/**
 * 零输入配对需要的本机能力（由 [SyncRepository] 接到 `FamilyRepository` / `TransactionDao` 上）。
 * 抽成接口是为了让握手逻辑能在 JVM 单测里跑，不依赖 Android。
 */
interface HomeFamilyBridge {

    /** 本机已有多少条真实流水（0 = 只有首启种子数据，可以安全地自动加入对方家庭）。 */
    suspend fun localTxnCount(): Int

    /** 自动加入对方家庭（等价于用户在「家庭」页输入配对码）。 */
    suspend fun adoptFamily(familyId: String): Boolean

    object None : HomeFamilyBridge {
        override suspend fun localTxnCount(): Int = 0
        override suspend fun adoptFamily(familyId: String): Boolean = false
    }
}

/**
 * 家庭握手（零输入配对）。
 *
 * 痛点：服务器地址可以编译期内置，但两台手机如果各有各的 `familyId`，
 * 会在服务器上各写各的目录，永远同步不到一起。让用户输配对码又违背「什么都不要填」。
 *
 * 做法：服务器只认**一本账**（`/family`）。
 *   - 服务器上还没登记 → 我登记，继续同步（第一台手机）
 *   - 已登记且就是我的 → 什么都不做（之后每次同步都跑，幂等）
 *   - 已登记但不是我的 → 本机还没有真实流水（`txn` 表 0 条）就**自动加入**对方家庭；
 *     已经有真实流水就**绝不自动改**（会把用户已有数据搬错家庭），给中文提示让用户手动配对
 *
 * 握手可能改变本机家庭码，所以调用方拿到 [Result.familyId] 之后要用**新的家庭码重建 transport**
 * （`X-Family-Id` 变了，服务器上的目录也就对了），再跑 ops 同步。
 */
class HomeFamilyHandshake(private val bridge: HomeFamilyBridge = HomeFamilyBridge.None) {

    /**
     * @param familyId 握手后应该使用的家庭码（null 表示保持不变）
     * @param adopted  是否自动加入了对方家庭
     * @param blocked  true = 服务器可达但不该继续同步（家庭码冲突且本机已有数据），
     *                 [message] 是给用户看的中文原因
     */
    data class Result(
        val familyId: String?,
        val adopted: Boolean = false,
        val blocked: Boolean = false,
        val message: String = "",
    )

    /** 跑一次握手（幂等；[transport] 必须是用**当前**家庭码建的）。 */
    suspend fun run(transport: HomeServerTransport, myFamilyId: String): Result {
        val remote = transport.getFamily()
        if (remote == null) {
            // 服务器上还没人登记：我是第一台
            val claim = transport.claimFamily(myFamilyId)
            if (!claim.conflict) return Result(myFamilyId)
            // 极小概率：两台手机同时登记，我输了 → 直接加入对方那本
            return join(claim.familyId.ifBlank { myFamilyId }, myFamilyId)
        }
        if (remote.equals(myFamilyId.trim(), ignoreCase = true)) return Result(myFamilyId)
        return join(remote, myFamilyId)
    }

    private suspend fun join(remoteFamilyId: String, myFamilyId: String): Result {
        val mine = myFamilyId.trim()
        val remote = remoteFamilyId.trim()
        if (remote.isEmpty() || remote.equals(mine, ignoreCase = true)) return Result(mine)
        val localTxns = runCatching { bridge.localTxnCount() }.getOrDefault(0)
        if (localTxns > 0) {
            // 有真实数据：绝不自动改家庭（会把用户已有数据搬错），让用户自己决定
            return Result(null, blocked = true, message = HomeServerProtocol.familyConflictMessage(localTxns))
        }
        val adopted = runCatching { bridge.adoptFamily(remote) }.getOrDefault(false)
        return if (adopted) {
            Result(familyId = remote, adopted = true, message = "已自动加入家里的账本（家庭码 $remote）")
        } else {
            Result(null, blocked = true, message = HomeServerProtocol.familyJoinFailedMessage())
        }
    }
}
