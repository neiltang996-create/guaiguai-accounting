package com.family.ledger.auto.rules

/**
 * 页面识别器：**按包名取该 App 的页面规则集，有序遍历，第一个匹配上的就是 pageType**。
 *
 * 对应钱迹 `s9.b.b(packageName, rootNode, allNodes)`：
 * ```
 * val appRules = appRuleMap[packageName] ?: return null
 * for (rule in appRules) if (rule.matches(root, all)) return rule.pageType
 * return null
 * ```
 * 识别不出单笔页面时返回 null；总览和列表不能用整页金额猜测交易。
 */
object PageRecognizer {

    /** 识别页面；返回命中的页面规则（含它的字段规则），识别不出返回 null。 */
    fun recognize(
        packageName: String?,
        root: NodeSnapshot?,
        all: List<NodeSnapshot>? = null,
    ): PageRule? {
        if (root == null) return null
        val pages = AutoBillPages.forPackage(packageName)
        if (pages.isEmpty()) return null
        val nodes = all ?: root.flatten()
        for (page in pages) {
            if (page.matches(root, nodes)) return page
        }
        return null
    }

    /** 只要 pageType 时用这个。 */
    fun recognizePageType(
        packageName: String?,
        root: NodeSnapshot?,
        all: List<NodeSnapshot>? = null,
    ): String? = recognize(packageName, root, all)?.pageType
}
