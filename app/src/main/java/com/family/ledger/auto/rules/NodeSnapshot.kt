package com.family.ledger.auto.rules

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 平台无关的**节点快照**（对齐钱迹 `AutoViewNode` 的思路，代码自己写）。
 *
 * 无障碍服务把 `AccessibilityNodeInfo` 树一次性转成这个结构，之后所有页面识别与字段抽取
 * 都在这棵纯数据树上进行 —— 于是规则可以脱离真机、用 JSON fixture 回归。
 *
 * `depth` / `index` 记录节点在树里的位置：`depth` 从 0 开始，`index` 是在父节点 children 里的下标。
 * 兄弟/父子导航（见 [NodeNavigator]）只依赖 `depth` 与**前序展开列表**，不依赖 `index`，
 * 这样 fixture 可以省掉 `index` 字段。
 *
 * **本包必须保持纯 Kotlin（零 Android 依赖）**。
 */
@Serializable
data class NodeSnapshot(
    val className: String? = null,
    val viewId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val depth: Int = 0,
    val index: Int = 0,
    val packageName: String? = null,
    val children: List<NodeSnapshot> = emptyList(),
) {

    /** 展示文本：`text` 优先，其次 `contentDescription`；空白视为没有。 */
    val display: String?
        get() = text?.trim()?.takeIf { it.isNotEmpty() }
            ?: contentDescription?.trim()?.takeIf { it.isNotEmpty() }

    /** 简单类名（`android.widget.TextView` → `TextView`）。 */
    val shortClass: String?
        get() = className?.substringAfterLast('.')?.takeIf { it.isNotEmpty() }

    /**
     * 类名匹配：既接受简单名（`TextView`），也接受全限定名；
     * 真机给的是 `android.widget.TextView` 或 `androidx.appcompat.widget.AppCompatTextView`，
     * fixture 里写简单名更省事，所以两种都认。
     */
    fun classMatches(expected: String): Boolean {
        val full = className ?: return false
        if (full == expected) return true
        val simple = shortClass ?: return false
        return simple == expected || simple.endsWith(expected)
    }

    /**
     * 前序展开（含自身），规则引擎的 `all` 列表就是这个。
     *
     * `depth` / `index` 在这里**按结构重算**：这样无论节点树是解析来的还是代码里拼出来的，
     * 兄弟/父子导航都成立（手写节点忘了填 depth 也不会静默失效）。
     */
    fun flatten(): List<NodeSnapshot> {
        val out = ArrayList<NodeSnapshot>(16)
        collect(this, out, MAX_FLATTEN_NODES, depth = 0, index = 0)
        return out
    }

    /** 节点总数（含自身）。 */
    fun nodeCount(): Int {
        var n = 1
        for (c in children) n += c.nodeCount()
        return n
    }

    /**
     * 把整棵树拼成一段文本 —— 与老的「整页文本 + 正则」兜底路径完全兼容，
     * 同时用于窗口内容去重（同一段文本短时间内重复上报就跳过）。
     */
    fun textDump(maxChars: Int = DEFAULT_TEXT_LIMIT): String {
        val sb = StringBuilder(256)
        appendText(this, sb, maxChars)
        return sb.toString().trim()
    }

    /** 第一个满足条件的节点（前序）。 */
    fun firstWhere(predicate: (NodeSnapshot) -> Boolean): NodeSnapshot? {
        if (predicate(this)) return this
        for (c in children) c.firstWhere(predicate)?.let { return it }
        return null
    }

    private fun collect(node: NodeSnapshot, out: MutableList<NodeSnapshot>, limit: Int, depth: Int, index: Int) {
        if (out.size >= limit) return
        out += if (node.depth == depth && node.index == index) {
            node
        } else {
            node.copy(depth = depth, index = index)
        }
        node.children.forEachIndexed { i, child ->
            if (out.size >= limit) return
            collect(child, out, limit, depth + 1, i)
        }
    }

    private fun appendText(node: NodeSnapshot, sb: StringBuilder, maxChars: Int) {
        if (sb.length >= maxChars) return
        node.display?.let {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(it.take(maxChars - sb.length))
        }
        for (c in node.children) {
            if (sb.length >= maxChars) return
            appendText(c, sb, maxChars)
        }
    }

    companion object {
        const val DEFAULT_TEXT_LIMIT = 3000
        private const val MAX_FLATTEN_NODES = 2000
    }
}

/**
 * 节点树的 JSON 编解码与截断。
 *
 * 两处用到：
 *  1. 单测的 fixture（`app/src/test/resources/auto-nodes/` 下的 JSON 文件）；
 *  2. 真机上「没识别出页面」时把节点树 dump 进 `auto_bill_log`（我们远程排障的唯一手段）。
 */
object NodeTree {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    fun decode(text: String): NodeSnapshot =
        relayout(json.decodeFromString(NodeSnapshot.serializer(), text))

    fun encode(node: NodeSnapshot): String = json.encodeToString(NodeSnapshot.serializer(), node)

    /**
     * 按嵌套结构重算 `depth` / `index`。
     *
     * 兄弟/父子导航只认 `depth`，手写 fixture 时逐个填 depth 太容易错 ——
     * 所以解析时统一重算一遍（幂等：真机 dump 里本来是对的，重算也不会变）。
     */
    fun relayout(node: NodeSnapshot, depth: Int = 0, index: Int = 0): NodeSnapshot = node.copy(
        depth = depth,
        index = index,
        children = node.children.mapIndexed { i, child -> relayout(child, depth + 1, i) },
    )

    /**
     * 截断到最多 [maxNodes] 个节点（钱迹的上限是 2000）。
     * 日志里塞整棵树的代价太大，dump 时再按需压得更小。
     */
    fun truncate(node: NodeSnapshot, maxNodes: Int): NodeSnapshot = copyLimited(node, maxNodes, intArrayOf(0))

    fun count(node: NodeSnapshot): Int = node.nodeCount()

    private fun copyLimited(node: NodeSnapshot, maxNodes: Int, used: IntArray): NodeSnapshot {
        if (used[0] >= maxNodes) return node.copy(children = emptyList())
        used[0]++
        val kept = ArrayList<NodeSnapshot>(node.children.size)
        for (c in node.children) {
            if (used[0] >= maxNodes) break
            kept += copyLimited(c, maxNodes, used)
        }
        return node.copy(children = kept)
    }
}
