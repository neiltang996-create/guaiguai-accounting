package com.family.ledger.auto

/**
 * 「页面上写的付款方式 → 账本里的资产」匹配打分（钱迹的 `firstAsset` / `outAssetMatcher` 思路）。
 *
 * 为什么值得单独抽出来：**页面直接给出的付款方式比任何关键词猜测都准**，
 * 但它和资产名往往对不齐 ——
 *  - 全角/半角括号：页面 `工商银行储蓄卡(8804)` vs 资产 `工商银行储蓄卡（8804）`；
 *  - 银行卡只给尾号：页面 `招商银行信用卡(8806)`，资产名里也带尾号，靠尾号 4 位对齐；
 *  - 带渠道前缀：页面 `零钱`，资产 `微信零钱（用户 A）`；
 *  - 带后缀：页面 `余额宝`，资产 `余额宝`。
 *
 * 打分刻意做成**纯函数**（不依赖 Context/DB），这样匹配规则能进单测，
 * 而 [AutoBillPipeline.guessAsset] 只需要比较分数：页面给出的方式（[SCORE_EXACT] / [SCORE_TAIL]）
 * 一定压过「家庭资产优先 / 渠道名 / 成员名」这些兜底加分（都 ≤ 30）。
 */
object AssetHintMatcher {

    /** 资产名与提示完全一致（归一化后）。 */
    const val SCORE_EXACT = 400

    /** 提示里有银行卡尾号 `(8804)`，资产名里含这 4 位数字。 */
    const val SCORE_TAIL = 300

    /** 资产名包含提示（`微信零钱（用户 A）` ⊃ `零钱`）。 */
    const val SCORE_CONTAINS = 200

    /** 只匹配上提示里括号前的主体词（`招商银行信用卡(8806)` → `招商银行信用卡`）。 */
    const val SCORE_PARTIAL = 80

    /** 括号（全角/半角）与空白归一化后的名字。 */
    fun normalize(name: String?): String = name.orEmpty()
        .replace('（', '(')
        .replace('）', ')')
        .replace(" ", "")
        .replace("\u3000", "")
        .trim()
        .lowercase()

    /**
     * @param assetName 账本里的资产名（如 `支付宝小荷包(示例日常)`、`微信零钱（用户 A）`）
     * @param hint 页面上给出的付款方式（如 `余额宝`、`零钱`、`招商银行信用卡(8806)`）
     * @return 0 表示对不上（此时按渠道/家庭等兜底加分决定）
     */
    fun score(assetName: String, hint: String?): Int {
        val h = hint?.trim().orEmpty()
        if (h.isEmpty() || assetName.isBlank()) return 0

        val asset = normalize(assetName)
        val wanted = normalize(h)
        if (asset == wanted) return SCORE_EXACT

        // 银行卡尾号：页面/资产任一侧给了 (dddd)，只要 4 位数字对得上就算
        val tail = CARD_TAIL.find(h)?.groupValues?.get(1)
        if (tail != null && asset.contains(tail)) return SCORE_TAIL

        if (wanted.isNotEmpty() && asset.contains(wanted)) return SCORE_CONTAINS

        val word = normalize(h.substringBefore('(').substringBefore('（'))
        if (word.length >= 2 && asset.contains(word)) return SCORE_PARTIAL
        return 0
    }

    private val CARD_TAIL = Regex("""[(（](\d{4})[)）]""")
}
