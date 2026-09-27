package com.family.ledger.auto.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `NodeSnapshot` / `NodeTree` 单测：前序展开、类名匹配、整页文本、JSON 往返与截断。
 *
 * 这些能力是规则引擎的地基 —— 后序导航全靠「前序列表 + depth」，所以这里先把地基钉死。
 */
class NodeSnapshotTest {

    /** 三层结构：A(B, C(D, E), F)。 */
    private fun tree(): NodeSnapshot = NodeSnapshot(
        className = "android.widget.FrameLayout",
        children = listOf(
            NodeSnapshot(className = "android.widget.TextView", text = "A"),
            NodeSnapshot(
                className = "android.widget.LinearLayout",
                children = listOf(
                    NodeSnapshot(className = "android.widget.TextView", text = "D"),
                    NodeSnapshot(className = "android.widget.TextView", text = "E"),
                ),
            ),
            NodeSnapshot(className = "android.widget.TextView", text = "F"),
        ),
    )

    @Test
    fun `前序展开顺序正确`() {
        val texts = tree().flatten().map { it.display ?: "-" }
        assertEquals(listOf("-", "A", "-", "D", "E", "F"), texts)
    }

    @Test
    fun `depth 按层级计算`() {
        val depths = tree().flatten().map { it.depth }
        assertEquals(listOf(0, 1, 1, 2, 2, 1), depths)
    }

    @Test
    fun `类名匹配同时支持简单名与全限定名`() {
        val tv = NodeSnapshot(className = "android.widget.TextView")
        assertTrue(tv.classMatches("TextView"))
        assertTrue(tv.classMatches("android.widget.TextView"))
        assertFalse(tv.classMatches("LinearLayout"))

        val compat = NodeSnapshot(className = "androidx.appcompat.widget.AppCompatTextView")
        assertTrue("AppCompatTextView 也应认作 TextView", compat.classMatches("TextView"))

        assertFalse(NodeSnapshot(className = null).classMatches("TextView"))
    }

    @Test
    fun `展示文本 text 优先于 contentDescription`() {
        assertEquals("你好", NodeSnapshot(text = "你好", contentDescription = "描述").display)
        assertEquals("描述", NodeSnapshot(text = "  ", contentDescription = "描述").display)
        assertNull(NodeSnapshot(text = "", contentDescription = " ").display)
    }

    @Test
    fun `整页文本按前序拼接`() {
        val text = tree().textDump()
        assertEquals("A D E F", text)
    }

    @Test
    fun `整页文本有长度上限`() {
        val wide = NodeSnapshot(
            children = (1..50).map { NodeSnapshot(text = "节点$it") },
        )
        val text = wide.textDump(maxChars = 20)
        assertTrue("应被截断：${text.length}", text.length <= 24)
    }

    @Test
    fun `JSON 往返保持一致`() {
        val node = tree()
        val json = NodeTree.encode(node)
        val back = NodeTree.decode(json)
        assertEquals(node.textDump(), back.textDump())
        assertEquals(node.flatten().size, back.flatten().size)
    }

    @Test
    fun `解析 fixture 时自动重算 depth`() {
        // fixture 里故意不写 depth；解析后必须按嵌套结构算出来
        val json = """
            {"className":"FrameLayout","children":[
              {"className":"TextView","text":"A"},
              {"className":"LinearLayout","children":[{"className":"TextView","text":"B"}]}
            ]}
        """.trimIndent()
        val node = NodeTree.decode(json)
        assertEquals(listOf(0, 1, 1, 2), node.flatten().map { it.depth })
    }

    @Test
    fun `截断保留前 N 个节点且结构完整`() {
        val truncated = NodeTree.truncate(tree(), maxNodes = 3)
        assertEquals(3, truncated.nodeCount())
        assertTrue(truncated.flatten().size == 3)
    }

    @Test
    fun `节点总数与 firstWhere`() {
        assertEquals(6, tree().nodeCount())
        assertEquals("E", tree().firstWhere { it.display == "E" }?.display)
        assertNull(tree().firstWhere { it.display == "Z" })
    }
}
