package com.family.ledger.auto.rules

import com.family.ledger.auto.Direction
import com.family.ledger.auto.PayPackages
import org.junit.Assert.*
import org.junit.Test

class WeChatCurrentPageTest {
    private fun page(vararg texts: String) = NodeSnapshot(children = texts.map {
        NodeSnapshot(className = "TextView", text = it)
    })

    // 按合成 OCR 文字格式构造，非无障碍节点抓取；节点读取另做真机验证。
    @Test fun untitledDiscountedReceiptUsesActualAmountAndHistoricalTime() {
        val root = page("示例烧烤店", "−68.00", "原价", "¥70.00", "优惠",
            "邮储信用卡中心立减优惠¥2.00", "当前状态", "支付成功", "支付时间",
            "2024年4月12日 14:20:30", "商品", "示例烧烤店-消费", "商户全称", "示例烧烤店",
            "支付方式", "邮储银行信用卡(8802)", "交易单号", "900000000000000000000000012",
            "商户单号", "9000000000000000000014", "账单服务")
        val receiptTime = Values.parseTimeMs("2024-04-12 14:20:30")!!
        val signal = BillPageParser.parseSignal(PayPackages.WECHAT, root, receiptTime + 86_400_000)!!
        assertEquals("WeChatBillDetail", signal.pageType)
        assertEquals(6800L, signal.amountCents)
        assertEquals(200L, signal.couponCents)
        assertEquals("示例烧烤店", signal.merchant)
        assertEquals("邮储银行信用卡(8802)", signal.suggestedAccountHint)
        assertEquals(receiptTime, signal.receiptTimeMillis)
        assertEquals("900000000000000000000000012", signal.orderId)
        assertEquals(Direction.PAYMENT, signal.direction)
    }

    @Test fun wechatBillOverviewDoesNotCreateReceipt() {
        val root = page("账单", "全部账单", "查找交易", "收支统计", "2026年9月",
            "支出¥2048.00", "收入¥130.00", "示例烧烤店", "−68.00", "70.00", "4月12日14:20",
            "示例便利店", "−17.00", "19.00")
        assertNull(BillPageParser.parseSignal(PayPackages.WECHAT, root))
    }

    @Test fun syntheticOcrNodesKeepMerchantAccountAndNetAmount() {
        val root = NodeTree.decode(javaClass.getResource("/auto-nodes/wechat_ocr_sample.json")!!.readText())
        val signal = BillPageParser.parseSignal(PayPackages.WECHAT, root, 1790442000000L)!!
        assertEquals("示例烧烤店", signal.merchant)
        assertTrue(signal.suggestedAccountHint!!.contains("(8802)"))
        assertEquals(6800L, signal.amountCents)
        assertEquals(200L, signal.couponCents)
    }

    @Test fun emptyProtectedWindowIsNotAReceipt() {
        assertNull(BillPageParser.parseSignal(PayPackages.WECHAT, NodeSnapshot(packageName = PayPackages.WECHAT)))
    }

    @Test fun separateEqualDiscountsRemainSeparateAndChineseDateSupportsSingleDigits() {
        val root = page("账单详情", "当前状态", "支付成功", "-10.00",
            "银行立减金¥2.00", "商家立减¥2.00", "支付时间", "2026年9月6日 9：08：07")
        val result = BillPageParser.parse(PayPackages.WECHAT, root)!!
        assertEquals(400L, result.couponCents)
        assertEquals("2026-09-06 09:08:07", result.timeText)
        assertEquals(Values.parseTimeMs("2026-09-06 09:08:07"), result.occurredAt)
    }
}
