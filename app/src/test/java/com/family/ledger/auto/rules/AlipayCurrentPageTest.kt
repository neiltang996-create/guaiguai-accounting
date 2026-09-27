package com.family.ledger.auto.rules

import com.family.ledger.auto.Direction
import com.family.ledger.auto.PayPackages
import org.junit.Assert.*
import org.junit.Test

class AlipayCurrentPageTest {
    @Test fun yuanWithDecimalAndUngroupedLargeAmountsAreAccepted() {
        for (amount in listOf("3.2元", "1234.56", "12345.67元", "1,234.56元")) {
            val root = NodeSnapshot(children = listOf("账单详情", "商品说明", amount,
                "商户名称", "示例商户").map { NodeSnapshot(className="TextView", text=it) })
            assertNotNull(amount, BillPageParser.parseSignal(PayPackages.ALIPAY, root))
        }
    }
    @Test fun sampleInterestDetailIsIncomeToYuEBao() {
        val raw=javaClass.getResourceAsStream("/auto-nodes/alipay_interest_sample.json")!!.bufferedReader().readText()
        val at=Values.parseTimeMs("2024-04-12 09:30:24")!!
        val signal=BillPageParser.parseSignal(PayPackages.ALIPAY, NodeTree.decode(raw), at+1000)!!
        assertEquals(320L, signal.amountCents)
        assertEquals(Direction.INCOME, signal.direction)
        assertEquals("余额宝", signal.suggestedAccountHint)
        assertEquals("示例基金管理有限公司", signal.merchant)
        // 历史账单不能被改成打开页面的当天。
        val historical=BillPageParser.parseSignal(PayPackages.ALIPAY, NodeTree.decode(raw), at+180L*86_400_000)!!
        assertEquals(at, historical.receiptTimeMillis)
    }
    @Test fun sampleReceiptUsesVisibleMerchantAndPaymentAccount() {
        val raw=javaClass.getResourceAsStream("/auto-nodes/alipay_scan_receipt_sample.json")!!.bufferedReader().readText()
        val receiptTime=Values.parseTimeMs("2024-04-12 16:10:15")!!
        val result=BillPageParser.parse(PayPackages.ALIPAY,NodeTree.decode(raw), now=receiptTime+1000L)
        assertNotNull(result)
        result!!
        assertEquals("AlipayBillDetail",result.pageType)
        assertEquals(42000L,result.amountCents)
        assertEquals(Direction.PAYMENT,result.direction)
        assertEquals("余额宝",result.assetHint)
        assertEquals("2024-04-12 16:10:15",result.timeText)
        assertEquals("示例礼服店",result.merchant)
        assertEquals(receiptTime,BillPageParser.parseSignal(PayPackages.ALIPAY,NodeTree.decode(raw),receiptTime+1000L)!!.receiptTimeMillis)
    }
    @Test fun aggregateBillListNeverCreatesAccessibilitySignal() {
        val labels=listOf("搜索交易记录", "全部", "支出", "收入", "转账", "退款", "筛选", "9月",
            "支出¥12,765.67", "收入¥2,000.00", "示例商户", "-420.00", "余额宝-收益发放", "+3.20")
        val root=NodeSnapshot(className="FrameLayout",children=labels.map { NodeSnapshot(className="TextView",text=it) })
        assertNull(BillPageParser.parseSignal(PayPackages.ALIPAY,root))
    }

    @Test fun unrecognizedSummaryWithPaymentWordsNeverUsesNotificationFallback() {
        val root=NodeSnapshot(className="FrameLayout",children=listOf(
            NodeSnapshot(className="TextView",text="账单总览 支出¥100 退款¥20 收入¥300")))
        assertNull(BillPageParser.parseSignal(PayPackages.ALIPAY,root))
        assertNull(BillPageParser.parseSignal(PayPackages.WECHAT,root))
    }
}
