package com.family.ledger.auto.rules

/**
 * 规则 DSL · **匹配器**（对齐钱迹 `r9` 的 `Text` / `RegexText` / `Any` 思路，实现自己写）。
 *
 * 匹配器只看**单个节点**；「至少有一个节点满足」的语义由 [PageRule] / [Rule] 负责。
 * 组合器 [AnyOf] / [AllOf] 让一条规则能表达「交易方式 或 付款方式」这类锚点。
 */
sealed interface Matcher {

    fun matches(node: NodeSnapshot): Boolean

    /** 文本/描述精确相等（默认忽略大小写与首尾空白）。 */
    data class Text(val value: String, val ignoreCase: Boolean = true) : Matcher {
        override fun matches(node: NodeSnapshot): Boolean {
            val actual = node.display ?: return false
            return actual.equals(value, ignoreCase = ignoreCase)
        }
    }

    /** 文本包含子串 —— 真机上锚点常被拼进一整行（如「当前状态 支付成功」）时用它。 */
    data class TextContains(val value: String, val ignoreCase: Boolean = true) : Matcher {
        override fun matches(node: NodeSnapshot): Boolean {
            val actual = node.display ?: return false
            return actual.contains(value, ignoreCase = ignoreCase)
        }
    }

    /**
     * 文本正则匹配。
     *
     * @param partial true = 只要文本里**出现**匹配片段即可（优惠券关键词）；false = 整段必须匹配（金额）。
     */
    data class RegexText(
        val pattern: String,
        val ignoreCase: Boolean = false,
        val partial: Boolean = false,
    ) : Matcher {
        private val regex: Regex by lazy {
            Regex(pattern, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
        }

        override fun matches(node: NodeSnapshot): Boolean {
            val actual = node.display ?: return false
            return if (partial) regex.containsMatchIn(actual) else regex.matches(actual)
        }

        /** 便于日志与断言：命中的文本。 */
        fun matchIn(text: String): String? =
            if (partial) regex.find(text)?.value else text.takeIf { regex.matches(it) }
    }

    /** viewId 精确匹配（真机上是 `com.eg.android.AlipayGphone:id/xxx`，也接受后缀写法）。 */
    data class IdIs(val viewId: String, val ignoreCase: Boolean = true) : Matcher {
        override fun matches(node: NodeSnapshot): Boolean {
            val actual = node.viewId ?: return false
            if (actual.equals(viewId, ignoreCase = ignoreCase)) return true
            return actual.endsWith("/$viewId", ignoreCase = ignoreCase) ||
                actual.endsWith(":id/$viewId", ignoreCase = ignoreCase)
        }
    }

    /** 控件类型匹配（`TextView` 或 `android.widget.TextView` 都认）。 */
    data class ClassIs(val className: String) : Matcher {
        override fun matches(node: NodeSnapshot): Boolean = node.classMatches(className)
    }

    /** 任一满足。 */
    data class AnyOf(val matchers: List<Matcher>) : Matcher {
        override fun matches(node: NodeSnapshot): Boolean = matchers.any { it.matches(node) }
    }

    /** 全部满足（同一个节点上同时成立）。 */
    data class AllOf(val matchers: List<Matcher>) : Matcher {
        override fun matches(node: NodeSnapshot): Boolean = matchers.all { it.matches(node) }
    }

    companion object {

        /** 「交易方式」或「付款方式」这类同义锚点。 */
        fun anyOf(vararg matchers: Matcher): Matcher = AnyOf(matchers.toList())

        fun allOf(vararg matchers: Matcher): Matcher = AllOf(matchers.toList())

        /** 文本包含任一关键词。 */
        fun containsAny(vararg keywords: String): Matcher =
            AnyOf(keywords.map { TextContains(it) })

        /** 整段文本匹配任一正则（金额的几种写法）。 */
        fun regexAny(vararg patterns: String): Matcher =
            AnyOf(patterns.map { RegexText(it) })
    }
}
