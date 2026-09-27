package com.family.ledger.auto

import android.view.accessibility.AccessibilityNodeInfo
import com.family.ledger.auto.rules.NodeSnapshot
import com.family.ledger.auto.rules.NodeTree

/**
 * 自动记账 · **节点树适配层**（唯一需要 `AccessibilityNodeInfo` 的地方）。
 *
 * 无障碍服务把当前窗口的节点树交给这里转成纯数据 [NodeSnapshot]，
 * 之后页面识别/规则取值/单测回归全在 `auto/rules/` 里跑，完全不需要真机。
 *
 * 上限对齐钱迹：**2000 个节点**、深度 30。超出的部分直接丢弃 —— 支付结果页永远够用，
 * 而超大列表页（长账单流）不值得为它付遍历成本。
 */
object AutoBillRules {

    /** 一次遍历最多取多少个节点（钱迹同为 2000）。 */
    const val MAX_NODES = 2000

    /** 最大深度，防止畸形树递归爆栈。 */
    const val MAX_DEPTH = 30

    /** dump 进 `auto_bill_log` 时的节点上限（日志要能同步，不能塞上万行）。 */
    const val MAX_DUMP_NODES = 400

    /** dump JSON 的字符上限（约 40KB）。 */
    const val MAX_DUMP_CHARS = 40_000

    /** 节点树 → 纯数据快照；拿不到返回 null。 */
    fun snapshotOf(root: AccessibilityNodeInfo?, maxNodes: Int = MAX_NODES): NodeSnapshot? {
        if (root == null) return null
        val counter = intArrayOf(0)
        return snapshot(root, depth = 0, index = 0, counter = counter, maxNodes = maxNodes)
    }

    /**
     * 未识别页面时把节点树序列化成 JSON 存进 `auto_bill_log`。
     * 用户手机不方便连 adb，这是我们**唯一的远程排障手段**（钱迹也是这么干的）。
     */
    fun dumpJson(root: NodeSnapshot, maxNodes: Int = MAX_DUMP_NODES): String {
        val truncated = if (root.nodeCount() > maxNodes) NodeTree.truncate(root, maxNodes) else root
        val json = runCatching { NodeTree.encode(truncated) }.getOrDefault("")
        return if (json.length > MAX_DUMP_CHARS) json.substring(0, MAX_DUMP_CHARS) else json
    }

    private fun snapshot(
        node: AccessibilityNodeInfo,
        depth: Int,
        index: Int,
        counter: IntArray,
        maxNodes: Int,
    ): NodeSnapshot? {
        if (counter[0] >= maxNodes || depth > MAX_DEPTH) return null
        counter[0]++

        val childCount = runCatching { node.childCount }.getOrDefault(0)
        val children = ArrayList<NodeSnapshot>(minOf(childCount, 8))
        for (i in 0 until childCount) {
            if (counter[0] >= maxNodes) break
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            snapshot(child, depth + 1, i, counter, maxNodes)?.let { children += it }
        }

        return NodeSnapshot(
            className = runCatching { node.className?.toString() }.getOrNull(),
            viewId = runCatching { node.viewIdResourceName }.getOrNull(),
            text = runCatching { node.text?.toString() }.getOrNull(),
            contentDescription = runCatching { node.contentDescription?.toString() }.getOrNull(),
            depth = depth,
            index = index,
            packageName = runCatching { node.packageName?.toString() }.getOrNull(),
            children = children,
        )
    }
}
