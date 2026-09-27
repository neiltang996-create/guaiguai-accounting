package com.family.ledger.auto.rules

/**
 * 规则 DSL · **提取器**（对齐钱迹 `i9.g` 的 `Current` / `Next` / `NextByType` / `Relative` / `FromChild`）。
 *
 * 提取器的输入是「命中的锚点节点 + 整棵树的**前序展开列表**」；输出是「值所在的节点」。
 * 之所以要 `all` 而不是只给树：`Relative(offset)` 是钱迹的主力手段
 * （锚点「支付成功」往后数第 N 个节点就是金额），而「往后数」需要线性序列。
 */
sealed interface Extractor {

    fun extract(anchor: NodeSnapshot, all: List<NodeSnapshot>): NodeSnapshot?

    /** 命中节点自身（金额常常自己就是一个纯金额节点）。 */
    object Current : Extractor {
        override fun extract(anchor: NodeSnapshot, all: List<NodeSnapshot>): NodeSnapshot? = anchor
    }

    /** 明确页面类型已确定的固定字段（如余额宝收益的入账账户）。 */
    data class Literal(val value: String) : Extractor {
        override fun extract(anchor: NodeSnapshot, all: List<NodeSnapshot>): NodeSnapshot = NodeSnapshot(text = value)
    }

    /** 下一个兄弟节点。 */
    object Next : Extractor {
        override fun extract(anchor: NodeSnapshot, all: List<NodeSnapshot>): NodeSnapshot? =
            NodeNavigator.nextSibling(all, anchor)
    }

    /** 下一个指定类型的兄弟节点（如 `NextByType("TextView")`）。 */
    data class NextByType(val className: String) : Extractor {
        override fun extract(anchor: NodeSnapshot, all: List<NodeSnapshot>): NodeSnapshot? =
            NodeNavigator.nextSibling(all, anchor) { it.classMatches(className) }
    }

    /** 下一个有文本的兄弟节点（跳过空白占位）。 */
    object NextText : Extractor {
        override fun extract(anchor: NodeSnapshot, all: List<NodeSnapshot>): NodeSnapshot? =
            NodeNavigator.nextSibling(all, anchor) { it.display != null }
    }

    /** 支付宝收钱码详情：金额前一个兄弟容器中的商户标题，避免取到下面的个人收款方。 */
    object PreviousSiblingHeading : Extractor {
        override fun extract(anchor: NodeSnapshot, all: List<NodeSnapshot>): NodeSnapshot? {
            val previous = NodeNavigator.previousSibling(all, anchor) ?: return null
            return (listOf(previous) + NodeNavigator.descendantsOf(all, previous)).lastOrNull {
                it.classMatches("TextView") && it.display?.let { text ->
                    !Labels.isLabel(text) && !Values.containsMoney(text)
                } == true
            }
        }
    }

    /**
     * 从前序序列里**相对锚点**偏移取节点 —— 钱迹的 `Relative(trigger, fromClass, offset)`。
     *
     * 计数从锚点自身开始（`offset = 0` 就是锚点自己，前提是它满足 [fromClass]）；
     * `offset` 允许为负（往前数，例如商户名在金额上面时的布局）。
     *
     * @param skipLabels 跳过「交易方式/付款方式/订单号」这类纯标签节点（取商户名时很有用）
     */
    data class Relative(
        val offset: Int,
        val fromClass: String? = null,
        val skipLabels: Boolean = false,
    ) : Extractor {
        override fun extract(anchor: NodeSnapshot, all: List<NodeSnapshot>): NodeSnapshot? {
            val start = NodeNavigator.positionOf(all, anchor) ?: return null
            var seen = 0
            val indices = if (offset >= 0) start until all.size else start downTo 0
            for (i in indices) {
                val node = all[i]
                if (fromClass != null && !node.classMatches(fromClass)) continue
                val text = node.display
                if (skipLabels && text != null && Labels.isLabel(text)) continue
                if (seen == offset) return node
                seen += if (offset >= 0) 1 else -1
            }
            return null
        }
    }

    /** 第一个指定类型的子孙节点（钱迹的 `FromChild`）：一行里「标签 + 值」同属一个容器时用它。 */
    data class FromChild(val className: String) : Extractor {
        override fun extract(anchor: NodeSnapshot, all: List<NodeSnapshot>): NodeSnapshot? =
            anchor.firstWhere { it !== anchor && it.classMatches(className) }
    }

    /** 第一个有文本的子孙节点。 */
    object FromChildText : Extractor {
        override fun extract(anchor: NodeSnapshot, all: List<NodeSnapshot>): NodeSnapshot? =
            anchor.firstWhere { it !== anchor && it.display != null }
    }

    /** Native rows can wrap a label several levels deeper than their value. Stay in that row. */
    data class InAncestorRow(val rowId: String, val valueId: String) : Extractor {
        override fun extract(anchor: NodeSnapshot, all: List<NodeSnapshot>): NodeSnapshot? {
            var parent = NodeNavigator.parentOf(all, anchor)
            while (parent != null) {
                if (parent.viewId == rowId) return NodeNavigator.descendantsOf(all, parent)
                    .firstOrNull { it.viewId == valueId && it.display != null }
                parent = NodeNavigator.parentOf(all, parent)
            }
            return null
        }
    }

    /**
     * **行取值**（我们自己的务实扩展）：账单页的「标签 → 值」布局有好几种，
     * 与其为每种写一条规则，不如按下面的顺序找第一个像「值」的东西：
     *
     *  1. 锚点自身文本里就带金额 → 就是它（`红包 -0.50元`）；
     *  2. 锚点自身文本是「标签：值」→ 去掉标签后的部分（合成一个虚拟节点返回）；
     *  3. 子孙里有文本的第一个（`[标签[值]]` 嵌套布局）；
     *  4. 后继兄弟里有文本的第一个（`[标签][值]` 并排布局；兄弟是容器时取容器里的第一个文本）；
     *  5. 父节点的后继兄弟（`[行[标签]][行[值]]` 布局）。
     *
     * @param stripLabel 是否执行第 2 步（同时处理标签冒号、全角空格等分隔符）
     */
    data class RowValue(val stripLabel: Boolean = true) : Extractor {
        override fun extract(anchor: NodeSnapshot, all: List<NodeSnapshot>): NodeSnapshot? {
            val own = anchor.display
            if (own != null) {
                // ① 自身就带金额（优惠券行最常见：`红包 -0.50元`）
                if (Values.containsMoney(own)) return anchor
                // ② `商户名称：美团` / `付款方式 余额宝`
                if (stripLabel) {
                    Values.stripLabel(own)?.let { value ->
                        return anchor.copy(
                            text = value,
                            contentDescription = null,
                            children = emptyList(),
                        )
                    }
                }
            }
            // ③ 子孙里第一个「像值」的节点
            for (node in NodeNavigator.descendantsOf(all, anchor)) {
                valueOf(node)?.let { return it }
            }
            // ④ 后继兄弟（兄弟是容器时，取容器里的第一个文本）
            NodeNavigator.nextSibling(all, anchor) { valueOf(it) != null }?.let { sibling ->
                valueOf(sibling)?.let { return it }
            }
            // ⑤ 父节点的后继兄弟
            val parent = NodeNavigator.parentOf(all, anchor) ?: return null
            val parentSibling = NodeNavigator.nextSibling(all, parent) { valueOf(it) != null } ?: return null
            return valueOf(parentSibling)
        }

        /** 节点自身（或它的子孙）里第一个「不是纯标签」的文本节点。 */
        private fun valueOf(node: NodeSnapshot): NodeSnapshot? {
            val own = node.display
            if (own != null) return if (Labels.isLabel(own)) null else node
            return node.firstWhere { child ->
                val text = child.display
                text != null && !Labels.isLabel(text)
            }
        }
    }
}

/**
 * 节点导航：只依赖**前序展开列表 + depth**，所以 fixture 不需要写 `index`。
 *
 * 前序序列里「同 depth 的连续一段」就是兄弟；遇到更浅的 depth 说明这一层结束了。
 */
object NodeNavigator {

    /** 节点在前序列表里的位置（先按对象身份，再退回相等判断）。 */
    fun positionOf(all: List<NodeSnapshot>, node: NodeSnapshot): Int? {
        val byIdentity = all.indexOfFirst { it === node }
        if (byIdentity >= 0) return byIdentity
        val byEquals = all.indexOf(node)
        return if (byEquals >= 0) byEquals else null
    }

    /** 下一个兄弟（可带条件）。 */
    fun nextSibling(
        all: List<NodeSnapshot>,
        node: NodeSnapshot,
        predicate: ((NodeSnapshot) -> Boolean)? = null,
    ): NodeSnapshot? {
        val start = positionOf(all, node) ?: return null
        val depth = node.depth
        var i = start + 1
        while (i < all.size) {
            val cur = all[i]
            if (cur.depth < depth) return null
            if (cur.depth == depth) {
                if (predicate == null || predicate(cur)) return cur
            }
            i++
        }
        return null
    }

    /** 前一个兄弟。 */
    fun previousSibling(all: List<NodeSnapshot>, node: NodeSnapshot): NodeSnapshot? {
        val start = positionOf(all, node) ?: return null
        val depth = node.depth
        var i = start - 1
        while (i >= 0) {
            val cur = all[i]
            if (cur.depth < depth) return null
            if (cur.depth == depth) return cur
            i--
        }
        return null
    }

    /** 所有兄弟（含自身），按文档顺序。 */
    fun siblings(all: List<NodeSnapshot>, node: NodeSnapshot): List<NodeSnapshot> {
        val start = positionOf(all, node) ?: return listOf(node)
        val depth = node.depth
        val out = ArrayList<NodeSnapshot>(4)
        // 向左找起点
        var left = start
        while (left - 1 >= 0 && all[left - 1].depth >= depth) {
            if (all[left - 1].depth == depth) out.add(0, all[left - 1])
            left--
        }
        out += node
        // 向右
        var i = start + 1
        while (i < all.size) {
            val cur = all[i]
            if (cur.depth < depth) break
            if (cur.depth == depth) out += cur
            i++
        }
        return out
    }

    /** 父节点（左边最近的 depth-1 节点）。 */
    fun parentOf(all: List<NodeSnapshot>, node: NodeSnapshot): NodeSnapshot? {
        val start = positionOf(all, node) ?: return null
        val depth = node.depth
        if (depth <= 0) return null
        var i = start - 1
        while (i >= 0) {
            if (all[i].depth == depth - 1) return all[i]
            i--
        }
        return null
    }

    /** 子孙节点（前序）。 */
    fun descendantsOf(all: List<NodeSnapshot>, node: NodeSnapshot): List<NodeSnapshot> {
        val start = positionOf(all, node) ?: return emptyList()
        val out = ArrayList<NodeSnapshot>(8)
        var i = start + 1
        while (i < all.size) {
            val cur = all[i]
            if (cur.depth <= node.depth) break
            out += cur
            i++
        }
        return out
    }
}
