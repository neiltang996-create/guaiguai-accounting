package com.family.ledger.auto.rules

import com.family.ledger.auto.Direction

/**
 * 规则 DSL · **一个页面的规则**。
 *
 * 对齐钱迹：`pageType`（如 `AlipayPaySuccess`）+ 一组 matcher 用于**页面识别**，
 * 识别上之后再跑这个页面专属的规则集取字段。
 *
 * 识别语义（钱迹 `s9.a.b()`）：
 *  - [matchers] 是「与」关系：**每个** matcher 都要能在节点里找到至少一个命中节点；
 *  - 同一个 matcher 命中多个节点没关系，只要有一个就成立。
 *
 * 注册表里的**顺序即优先级**：越具体的页面必须排越前。
 * 典型例子：支付宝账单详情页里「当前状态 = 支付成功」，如果不先判 `AlipayBillDetail`，
 * 就会被 `AlipayPaySuccess` 抢走。
 */
data class PageRule(
    val pageType: String,
    val matchers: List<Matcher>,
    val rules: List<Rule> = emptyList(),
    /** 页面默认资金方向（规则里抽不到状态时用它）。 */
    val defaultDirection: Direction = Direction.PAYMENT,
) {

    fun matches(root: NodeSnapshot, all: List<NodeSnapshot>): Boolean =
        matchers.isNotEmpty() && matchers.all { m -> all.any { m.matches(it) } }

    fun matches(root: NodeSnapshot): Boolean = matches(root, root.flatten())

    /** 跑完这个页面的所有规则，得到字段字典。 */
    fun applyRules(root: NodeSnapshot, all: List<NodeSnapshot> = root.flatten()): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        val seenCouponAnchors = Rule.newAnchorSet()
        for (rule in rules) rule.apply(root, all, out, seenCouponAnchors)
        return out
    }
}
