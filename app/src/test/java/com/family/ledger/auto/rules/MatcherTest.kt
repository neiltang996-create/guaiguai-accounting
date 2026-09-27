package com.family.ledger.auto.rules

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 匹配器单测：精确/包含/正则（整段与部分）/viewId/类名/组合器。 */
class MatcherTest {

    private fun node(
        text: String? = null,
        desc: String? = null,
        viewId: String? = null,
        className: String? = "android.widget.TextView",
    ) = NodeSnapshot(className = className, viewId = viewId, text = text, contentDescription = desc)

    @Test
    fun `Text 精确匹配且忽略大小写与空白`() {
        assertTrue(Matcher.Text("支付成功").matches(node(text = "支付成功")))
        assertTrue(Matcher.Text("支付成功").matches(node(text = " 支付成功 ")))
        assertTrue(Matcher.Text("PaySuccess").matches(node(text = "paysuccess")))
        assertFalse(Matcher.Text("支付成功").matches(node(text = "支付成功啦")))
        assertFalse(Matcher.Text("支付成功").matches(node(text = null)))
    }

    @Test
    fun `Text 也能匹配 contentDescription`() {
        assertTrue(Matcher.Text("二维码").matches(node(desc = "二维码")))
    }

    @Test
    fun `TextContains 命中整行里的锚点`() {
        assertTrue(Matcher.TextContains("支付成功").matches(node(text = "当前状态 支付成功")))
        assertFalse(Matcher.TextContains("退款成功").matches(node(text = "支付成功")))
    }

    @Test
    fun `RegexText 默认整段匹配`() {
        val amount = Matcher.RegexText("""^[¥￥]\d+(?:\.\d{1,2})?$""")
        assertTrue(amount.matches(node(text = "¥45.00")))
        assertTrue(amount.matches(node(text = "￥12")))
        assertFalse("带前后缀不匹配整段规则", amount.matches(node(text = "已支付¥45.00")))
        assertFalse(amount.matches(node(text = "45.00")))
    }

    @Test
    fun `RegexText partial 只要出现即可`() {
        val coupon = Matcher.RegexText("红包|立减", partial = true)
        assertTrue(coupon.matches(node(text = "到店支付红包")))
        assertTrue(coupon.matches(node(text = "碰一下立减")))
        assertFalse(coupon.matches(node(text = "优惠券")))
        assertNotNull(coupon.matchIn("到店支付红包"))
    }

    @Test
    fun `IdIs 支持全限定与后缀写法`() {
        assertTrue(Matcher.IdIs("bill_detail").matches(node(viewId = "com.eg.android.AlipayGphone:id/bill_detail")))
        assertTrue(Matcher.IdIs("bill_detail").matches(node(viewId = "bill_detail")))
        assertFalse(Matcher.IdIs("bill_detail").matches(node(viewId = "other_id")))
        assertFalse(Matcher.IdIs("bill_detail").matches(node(viewId = null)))
    }

    @Test
    fun `ClassIs 认简单名与 AppCompat`() {
        assertTrue(Matcher.ClassIs("TextView").matches(node()))
        assertTrue(
            Matcher.ClassIs("TextView")
                .matches(node(className = "androidx.appcompat.widget.AppCompatTextView"))
        )
        assertFalse(Matcher.ClassIs("TextView").matches(node(className = "android.widget.LinearLayout")))
    }

    @Test
    fun `AnyOf 与 AllOf`() {
        val any = Matcher.anyOf(Matcher.Text("交易方式"), Matcher.Text("付款方式"))
        assertTrue(any.matches(node(text = "交易方式")))
        assertTrue(any.matches(node(text = "付款方式")))
        assertFalse(any.matches(node(text = "订单号")))

        val all = Matcher.allOf(Matcher.TextContains("支付"), Matcher.ClassIs("TextView"))
        assertTrue(all.matches(node(text = "支付成功")))
        assertFalse("类型不符就不成立", all.matches(node(text = "支付成功", className = "android.widget.ImageView")))
    }

    @Test
    fun `containsAny 与 regexAny 便捷构造`() {
        val labels = Matcher.containsAny("订单号", "交易单号")
        assertTrue(labels.matches(node(text = "订单号 20260925")))
        assertTrue(labels.matches(node(text = "交易单号")))
        assertFalse(labels.matches(node(text = "商户名称")))

        val amounts = Matcher.regexAny("""^[¥￥]\d+$""", """^\d+\.\d{2}$""")
        assertTrue(amounts.matches(node(text = "¥45")))
        assertTrue(amounts.matches(node(text = "45.00")))
        assertFalse(amounts.matches(node(text = "45")))
    }

    @Test
    fun `没有文本的节点不会被锚点匹配`() {
        assertFalse(Matcher.TextContains("支付").matches(node(text = "")))
        assertNull(Matcher.RegexText("支付").matchIn(""))
    }
}
