package com.family.ledger.auto.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 提取器 + 节点导航单测。
 *
 * 「锚点 + 相对位置」是这套规则的核心（钱迹的 `Relative(offset)`），
 * 所以这里把兄弟/父子/偏移/跳过标签/行取值逐一钉死。
 */
class ExtractorTest {

    /**
     * ```
     * FrameLayout
     *   Row1: [支付成功] [¥45.00] [美团]
     *   Row2: [交易方式] [余额宝]
     *   Row3: [订单号 2026…] [付款时间] [2024-04-11 13:03:38]
     * ```
     */
    private val tree: NodeSnapshot = NodeTree.decode(
        """
        {"className":"FrameLayout","children":[
          {"className":"LinearLayout","children":[
            {"className":"TextView","text":"支付成功"},
            {"className":"TextView","text":"¥45.00"},
            {"className":"TextView","text":"美团"}
          ]},
          {"className":"LinearLayout","children":[
            {"className":"TextView","text":"交易方式"},
            {"className":"TextView","text":"余额宝"}
          ]},
          {"className":"LinearLayout","children":[
            {"className":"TextView","text":"订单号 9000000000000000000004"},
            {"className":"TextView","text":"付款时间"},
            {"className":"TextView","text":"2024-04-11 13:03:38"}
          ]}
        ]}
        """.trimIndent()
    )

    private val all = tree.flatten()
    private fun anchor(text: String): NodeSnapshot = all.first { it.display == text }

    // ---------- 导航 ----------

    @Test
    fun `兄弟节点只认同层`() {
        val amount = anchor("¥45.00")
        assertEquals(
            listOf("支付成功", "¥45.00", "美团"),
            NodeNavigator.siblings(all, amount).map { it.display },
        )
        assertEquals("美团", NodeNavigator.nextSibling(all, amount)?.display)
        assertEquals("支付成功", NodeNavigator.previousSibling(all, amount)?.display)
        assertNull("最后一个兄弟之后没有了", NodeNavigator.nextSibling(all, anchor("美团")))
    }

    @Test
    fun `父节点是左边最近的上一层节点`() {
        val amount = anchor("¥45.00")
        val parent = NodeNavigator.parentOf(all, amount)
        assertEquals("LinearLayout", parent?.shortClass)
        assertEquals(listOf("支付成功", "¥45.00", "美团"), parent?.children?.map { it.display })
        assertNull("根节点没有父", NodeNavigator.parentOf(all, tree))
    }

    @Test
    fun `子孙节点按前序返回`() {
        val row1 = tree.children[0]
        assertEquals(
            listOf("支付成功", "¥45.00", "美团"),
            NodeNavigator.descendantsOf(all, row1).map { it.display },
        )
        assertEquals(emptyList<String>(), NodeNavigator.descendantsOf(all, anchor("美团")).map { it.display })
    }

    // ---------- 提取器 ----------

    @Test
    fun `Current 取锚点自身`() {
        val amount = anchor("¥45.00")
        assertEquals(amount, Extractor.Current.extract(amount, all))
    }

    @Test
    fun `NextByType 跳过非目标类型`() {
        val label = anchor("交易方式")
        assertEquals("余额宝", Extractor.NextByType("TextView").extract(label, all)?.display)
        assertNull(
            "下一个兄弟里没有 ImageView",
            Extractor.NextByType("ImageView").extract(label, all),
        )
    }

    @Test
    fun `Relative 从锚点起算偏移`() {
        val amount = anchor("¥45.00")
        // offset=0 是锚点自己，1 是下一个
        assertEquals("¥45.00", Extractor.Relative(0, fromClass = "TextView").extract(amount, all)?.display)
        assertEquals("美团", Extractor.Relative(1, fromClass = "TextView").extract(amount, all)?.display)
        assertEquals("交易方式", Extractor.Relative(2, fromClass = "TextView").extract(amount, all)?.display)
    }

    @Test
    fun `Relative skipLabels 跳过纯标签`() {
        val amount = anchor("¥45.00")
        // 不跳过标签：往后第二个 TextView 是「交易方式」
        assertEquals("交易方式", Extractor.Relative(2, fromClass = "TextView").extract(amount, all)?.display)
        // 跳过标签：往后第二个「非标签」TextView 是「余额宝」
        assertEquals(
            "余额宝",
            Extractor.Relative(2, fromClass = "TextView", skipLabels = true).extract(amount, all)?.display,
        )
    }

    @Test
    fun `Relative 可取任意类型`() {
        val amount = anchor("¥45.00")
        assertEquals("美团", Extractor.Relative(1).extract(amount, all)?.display)
        // 负数 = 往前数（商户名写在金额上面的布局）
        assertEquals("支付成功", Extractor.Relative(-1, fromClass = "TextView").extract(amount, all)?.display)
        assertEquals("支付成功", Extractor.Relative(-1).extract(amount, all)?.display)
    }

    @Test
    fun `FromChild 与 FromChildText`() {
        val row2 = tree.children[1]
        assertEquals("交易方式", Extractor.FromChild("TextView").extract(row2, all)?.display)
        assertEquals("交易方式", Extractor.FromChildText.extract(row2, all)?.display)
        assertNull("叶子节点没有子孙", Extractor.FromChildText.extract(anchor("余额宝"), all))
    }

    @Test
    fun `RowValue 取后继兄弟作为值`() {
        val label = anchor("交易方式")
        assertEquals("余额宝", Extractor.RowValue().extract(label, all)?.display)
    }

    @Test
    fun `RowValue 剥掉标签前缀`() {
        val node = NodeTree.decode("""{"className":"TextView","text":"付款方式 余额宝"}""")
        assertEquals("余额宝", Extractor.RowValue().extract(node, node.flatten())?.display)

        val colon = NodeTree.decode("""{"className":"TextView","text":"商户名称：美团"}""")
        assertEquals("美团", Extractor.RowValue().extract(colon, colon.flatten())?.display)
    }

    @Test
    fun `RowValue 遇到金额行直接返回自身`() {
        val coupon = NodeTree.decode("""{"className":"TextView","text":"红包 -0.50元"}""")
        assertEquals("红包 -0.50元", Extractor.RowValue().extract(coupon, coupon.flatten())?.display)
    }

    @Test
    fun `RowValue 支持标签与值分属两行的嵌套布局`() {
        // [行1[标签]] [行2[值]]
        val t = NodeTree.decode(
            """
            {"className":"LinearLayout","children":[
              {"className":"LinearLayout","children":[{"className":"TextView","text":"商户名称"}]},
              {"className":"LinearLayout","children":[{"className":"TextView","text":"美团"}]}
            ]}
            """.trimIndent()
        )
        val nodes = t.flatten()
        val label = nodes.first { it.display == "商户名称" }
        assertEquals("美团", Extractor.RowValue().extract(label, nodes)?.display)
    }

    @Test
    fun `RowValue 优先取子孙`() {
        val t = NodeTree.decode(
            """
            {"className":"LinearLayout","children":[
              {"className":"TextView","text":"付款方式"},
              {"className":"LinearLayout","children":[{"className":"TextView","text":"花呗"}]}
            ]}
            """.trimIndent()
        )
        val nodes = t.flatten()
        val label = nodes.first { it.display == "付款方式" }
        assertEquals("花呗", Extractor.RowValue().extract(label, nodes)?.display)
    }

    @Test
    fun `positionOf 对不在列表里的节点返回 null`() {
        assertNull(NodeNavigator.positionOf(all, NodeSnapshot(text = "不存在的节点")))
    }
}
