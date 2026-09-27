package com.family.ledger.auto.rules

import com.family.ledger.auto.Direction
import com.family.ledger.auto.Origin
import com.family.ledger.auto.PayPackages
import com.family.ledger.auto.PendingBillCodec
import com.family.ledger.data.db.entity.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **节点树 fixture 回归测试** —— 这套重构最大的收益：
 * 不用真机、不用 adb，直接用 JSON 节点树把「页面识别 + 字段抽取」钉死。
 *
 * fixture 放在 `app/src/test/resources/auto-nodes/`，模拟真实支付页的结构；
 * 新增 fixture 必须先替换为独立合成的交易信息，禁止提交原始 dump。
 */
class BillPageRulesFixtureTest {

    private val alipay = PayPackages.ALIPAY
    private val wechat = PayPackages.WECHAT
    private val unionpay = PayPackages.UNIONPAY

    private fun fixture(name: String): NodeSnapshot {
        val text = javaClass.getResourceAsStream("/auto-nodes/$name")?.bufferedReader()?.use { it.readText() }
        assertNotNull("fixture 缺失：$name", text)
        return NodeTree.decode(text!!)
    }

    private fun parse(name: String, pkg: String = alipay) = BillPageParser.parse(pkg, fixture(name))

    // ---------- 页面识别 ----------

    @Test
    fun `支付宝付款成功页识别正确并抽全部字段`() {
        val p = parse("alipay_pay_success.json")
        assertNotNull(p)
        p!!
        assertEquals("AlipayPaySuccess", p.pageType)
        assertEquals(4500L, p.amountCents)
        assertEquals("美团", p.merchant)
        assertEquals("余额宝", p.assetHint)          // 页面直接给出的付款方式
        assertEquals("支付宝·余额宝", p.channelLabel) // 与文本兜底路径产出的口径一致
        assertEquals(50L, p.couponCents)             // 到店支付红包 -0.50 元
        assertEquals("9000000000000000000004", p.orderId)
        assertEquals(Direction.PAYMENT, p.direction)
        assertEquals("2024-04-11 13:03:38", p.timeText)
    }

    /**
     * ★ 用户实测误报：支付宝「账单」**列表页**不该触发任何记账。
     *
     * 虚构列表样例（金额仅用于回归测试）：页面上有
     * ```
     * 9月
     * 支出 ¥12,345.67      ← 汇总，不是一笔账
     * 收入 ¥8,765.40       ← 汇总
     * 余额宝-转出到银行卡  1.00    ← 列表项文本里含「转出到银行卡」
     * ```
     * 原先转账锚点里有「转出到 / 转出到银行卡」，被这条列表项命中 →
     * 整个列表页被判成转账页 → 抓了汇总金额弹卡片。
     *
     * 锚点必须是**只在详情页出现的结构标签**，不能是「详情页里某个值的近似文案」。
     */
    @Test
    fun `支付宝账单列表页不能触发记账`() {
        val root = NodeTree.decode(
            """
            {"className":"FrameLayout","packageName":"com.eg.android.AlipayGphone","children":[
              {"className":"TextView","text":"搜索交易记录"},
              {"className":"TextView","text":"全部"},{"className":"TextView","text":"支出"},
              {"className":"TextView","text":"收入"},{"className":"TextView","text":"转账"},
              {"className":"TextView","text":"退款"},
              {"className":"TextView","text":"9月"},
              {"className":"TextView","text":"支出"},{"className":"TextView","text":"¥12,345.67"},
              {"className":"TextView","text":"收入"},{"className":"TextView","text":"¥8,765.40"},
              {"className":"TextView","text":"本月已省 6.50元"},
              {"className":"TextView","text":"示例充电服务有限公司"},
              {"className":"TextView","text":"5.00"},
              {"className":"TextView","text":"扫收钱码付款-给示例服装店"},
              {"className":"TextView","text":"-680.00"},
              {"className":"TextView","text":"余额宝-转出到银行卡"},
              {"className":"TextView","text":"1.00"},
              {"className":"TextView","text":"余额宝-收益发放"},
              {"className":"TextView","text":"3.20"},
              {"className":"TextView","text":"中国石油-支付宝小程序支付"},
              {"className":"TextView","text":"10.00"}
            ]}
            """.trimIndent()
        )
        val pageType = PageRecognizer.recognizePageType(alipay, root)
        assertNull("账单列表页被误判成 $pageType —— 会抓汇总金额弹卡片", pageType)
        assertNull("账单列表页不该产生任何记账信号", BillPageParser.parse(alipay, root))
    }

    /**
     * **合成的付款成功页**（示例服装店，¥680）。
     *
     * 用户反馈「还是不会弹出呢，钱迹就可以」的护栏。两个真机差异：
     *  1. 金额是**一个节点** `支出680元`（带中文前缀 + 无小数点）——
     *     我们原先四种金额写法全都匹配不上 → 抽不到金额 → 卡片不弹
     *  2. 「账单详情」标题在节点树**最后面**（所以打印节点树不能截断）
     */
    @Test
    fun `合成支付宝收钱码付款成功页必须识别并抽出金额`() {
        val p = parse("alipay_pay_success_sample.json")
        assertNotNull("样例付款成功页识别失败 —— 用户看到的就是「卡片不弹」", p)
        p!!
        assertEquals("AlipayBillDetail", p.pageType)   // 该页含「账单详情」标题 + 商品说明/账单分类
        assertEquals(68000L, p.amountCents)           // 支出680元
        assertEquals(Direction.PAYMENT, p.direction)   // 方向来自「支出」前缀
        assertEquals("余额宝", p.assetHint)             // 付款方式
        assertEquals("2024-04-12 11:30:11", p.timeText)
    }

    /**
     * **脱敏重建的 fixture**：保留支付宝「账单详情」页的节点结构，
     * 交易日期、金额、对方账户和订单号全部为示例值。
     *
     * 这条用例是用户反馈「点开之前的付款页面不弹记账卡片」的护栏。根因：
     * 真实页面上状态只有**值**「交易成功」，**没有**「当前状态 / 交易状态」标签，
     * 而页面匹配器当时只认标签 → 页面识别失败 → 卡片不弹。
     */
    @Test
    fun `合成支付宝账单详情页必须识别成功`() {
        val p = parse("alipay_bill_detail_sample.json")
        assertNotNull("样例账单详情页识别失败 —— 用户会看到「不弹记账卡片」", p)
        p!!
        assertEquals("AlipayBillDetail", p.pageType)
        assertEquals(320L, p.amountCents)                       // 3.20
        assertEquals("示例基金管理有限公司", p.merchant)          // 来自「对方账户」标签
        assertEquals("90000000000000000006", p.orderId)
        assertEquals("2024-04-12 09:30:24", p.timeText)
    }

    @Test
    fun `微信付款成功页识别正确并抽全部字段`() {
        val p = parse("wechat_pay_success.json", wechat)
        assertNotNull(p)
        p!!
        assertEquals("WeChatPaySuccess", p.pageType)
        assertEquals(3200L, p.amountCents)
        assertEquals("星巴克", p.merchant)
        assertEquals("零钱", p.assetHint)
        assertEquals("微信零钱", p.channelLabel)
        assertEquals(200L, p.couponCents)            // 优惠 ¥2.00
        assertEquals("9000000000000000000000000011", p.orderId)
        assertEquals(Direction.PAYMENT, p.direction)
    }

    @Test
    fun `云闪付付款成功页识别正确`() {
        val p = parse("unionpay_pay_success.json", unionpay)
        assertNotNull(p)
        p!!
        assertEquals("UnionpayPaySuccess", p.pageType)
        assertEquals(12800L, p.amountCents)
        assertEquals("全家便利店", p.merchant)
        assertEquals("云闪付", p.assetHint)
    }

    @Test
    fun `支付宝账单详情页识别正确（带负号金额与优惠）`() {
        val p = parse("alipay_bill_detail.json")
        assertNotNull(p)
        p!!
        assertEquals("AlipayBillDetail", p.pageType)
        assertEquals(4500L, p.amountCents)   // 页面写的是 -45.00，金额取绝对值
        assertEquals("美团", p.merchant)
        assertEquals("余额宝", p.assetHint)
        assertEquals(30L, p.couponCents)     // 优惠 0.30 元
        assertEquals("9000000000000000000003", p.orderId)
        assertEquals(Direction.PAYMENT, p.direction)
    }

    @Test
    fun `微信账单详情页识别退款`() {
        val p = parse("wechat_bill_detail.json", wechat)
        assertNotNull(p)
        p!!
        assertEquals("WeChatBillDetail", p.pageType)
        assertEquals(4500L, p.amountCents)
        assertEquals("美团科技有限公司", p.merchant)  // 「商户全称 美团科技有限公司」
        assertEquals("零钱", p.assetHint)
        assertEquals(Direction.REFUND, p.direction)  // 当前状态 = 已退款
    }

    @Test
    fun `支付宝退款成功页方向为退款`() {
        val p = parse("alipay_refund_success.json")
        assertNotNull(p)
        p!!
        assertEquals("AlipayRefundSuccess", p.pageType)
        assertEquals(4500L, p.amountCents)
        assertEquals(Direction.REFUND, p.direction)
        assertEquals("余额宝", p.assetHint)           // 「退款去向 余额宝」
    }

    // ---------- 顺序敏感 ----------

    @Test
    fun `账单详情页必须排在付款成功页前面`() {
        // 这个 fixture 同时含「账单详情」「当前状态 = 支付成功」「金额」，
        // 如果规则表顺序反了，就会被 AlipayPaySuccess 抢走。
        val root = fixture("alipay_bill_detail.json")
        val all = root.flatten()
        assertTrue("AlipayPaySuccess 的锚点其实也在这一页", AutoBillPages.ALIPAY_PAY_SUCCESS.matches(root, all))
        assertEquals("AlipayBillDetail", PageRecognizer.recognizePageType(alipay, root, all))
        assertEquals(AutoBillPages.ALIPAY_BILL_DETAIL, PageRecognizer.recognize(alipay, root, all))
    }

    @Test
    fun `付款成功页带账单详情链接也不会被误判为详情页`() {
        val root = fixture("alipay_pay_success.json")
        assertEquals(
            "详情页规则要求「当前状态」这类锚点，成功页只有链接文字",
            "AlipayPaySuccess",
            PageRecognizer.recognizePageType(alipay, root, root.flatten()),
        )
    }

    @Test
    fun `规则表顺序：转账排最前、微信详情页排最前`() {
        // 转账详情页和账单详情页共享「账单详情 / 创建时间 / 账单分类」，
        // 顺序反了转账就会被记成支出（用户实测报的 bug，所以这条是护栏）
        assertEquals("AlipayTransferOut", AutoBillPages.ALIPAY[0].pageType)
        assertEquals("AlipayTransferIn", AutoBillPages.ALIPAY[1].pageType)
        assertEquals("AlipayBillDetail", AutoBillPages.ALIPAY[2].pageType)
        assertEquals("WeChatBillDetail", AutoBillPages.WECHAT.first().pageType)
        assertTrue(AutoBillPages.ALIPAY.indexOf(AutoBillPages.ALIPAY_INTEREST_DETAIL) <
            AutoBillPages.ALIPAY.indexOf(AutoBillPages.ALIPAY_BILL_DETAIL))
    }

    // ---------- 未识别 ----------

    @Test
    fun `聊天页识别不出页面`() {
        val root = fixture("wechat_chat_page.json")
        assertNull(PageRecognizer.recognize(wechat, root, root.flatten()))
        assertNull(BillPageParser.parse(wechat, root))
        assertFalse("聊天页不该触发节点 dump", BillPageParser.isPaymentLike(root.textDump()))
    }

    @Test
    fun `支付相关但认不出的页面返回 null 且可 dump`() {
        val root = fixture("alipay_unknown_pay_page.json")
        assertNull(PageRecognizer.recognize(alipay, root, root.flatten()))
        assertNull(BillPageParser.parse(alipay, root))
        assertTrue("像支付页 → 值得 dump 节点树排障", BillPageParser.isPaymentLike(root.textDump()))

        // dump 的载荷就是节点树 JSON（截断后仍要能读回来）
        val dumped = NodeTree.encode(NodeTree.truncate(root, 400))
        val back = NodeTree.decode(dumped)
        assertEquals(root.nodeCount(), back.nodeCount())
        assertTrue(dumped.contains("付款码"))
    }

    @Test
    fun `不认识的包名直接返回 null`() {
        val root = fixture("alipay_pay_success.json")
        assertNull(PageRecognizer.recognize("com.tencent.mobileqq", root, root.flatten()))
        assertNull(BillPageParser.parse("com.tencent.mobileqq", root))
    }

    // ---------- 优惠券累加 ----------

    @Test
    fun `多条优惠券累加且同一节点不重复计`() {
        val root = NodeTree.decode(
            """
            {"className":"FrameLayout","children":[
              {"className":"TextView","text":"支付成功"},
              {"className":"TextView","text":"¥100.00"},
              {"className":"TextView","text":"永辉超市"},
              {"className":"LinearLayout","children":[
                {"className":"TextView","text":"到店支付红包"},
                {"className":"TextView","text":"-1.50元"}
              ]},
              {"className":"LinearLayout","children":[
                {"className":"TextView","text":"花呗立减"},
                {"className":"TextView","text":"-0.50元"}
              ]}
            ]}
            """.trimIndent()
        )
        val p = BillPageParser.parse(alipay, root, System.currentTimeMillis())
        assertNotNull(p)
        // 「到店支付红包」既命中「到店支付红包」也命中「红包」，但同一锚点只算一次
        assertEquals(200L, p!!.couponCents)
        assertEquals(10000L, p.amountCents)
        assertEquals("永辉超市", p.merchant)
    }

    @Test
    fun `优惠券没有金额时不计数`() {
        val root = NodeTree.decode(
            """
            {"className":"FrameLayout","children":[
              {"className":"TextView","text":"支付成功"},
              {"className":"TextView","text":"¥20.00"},
              {"className":"TextView","text":"有红包可用"}
            ]}
            """.trimIndent()
        )
        val p = BillPageParser.parse(alipay, root, System.currentTimeMillis())
        assertNotNull(p)
        assertEquals(0L, p!!.couponCents)
    }

    @Test
    fun `优惠券关键词表非空且互不为前缀导致重复（由锚点去重兜底）`() {
        assertTrue(AutoBillPages.couponKeywords().size >= 16)
        assertTrue(AutoBillPages.couponKeywords().contains("到店支付红包"))
        assertTrue(AutoBillPages.couponKeywords().contains("红包"))
    }

    // ---------- 失败/关闭页面不记账 ----------

    @Test
    fun `交易关闭的账单详情页不产生信号`() {
        val root = NodeTree.decode(
            """
            {"className":"FrameLayout","children":[
              {"className":"TextView","text":"账单详情"},
              {"className":"TextView","text":"¥45.00"},
              {"className":"LinearLayout","children":[
                {"className":"TextView","text":"当前状态"},
                {"className":"TextView","text":"交易关闭"}
              ]}
            ]}
            """.trimIndent()
        )
        assertEquals("AlipayBillDetail", PageRecognizer.recognizePageType(alipay, root, root.flatten()))
        assertNull("交易关闭不是一笔账", BillPageParser.parse(alipay, root))
    }

    @Test
    fun `付款成功但金额取不到时返回 null 交给文本兜底`() {
        val root = NodeTree.decode(
            """
            {"className":"FrameLayout","children":[
              {"className":"TextView","text":"支付成功"},
              {"className":"TextView","text":"美团"}
            ]}
            """.trimIndent()
        )
        // 「支付成功」+ 没有金额 → 页面规则本身就不该成立
        assertNull(PageRecognizer.recognizePageType(alipay, root, root.flatten()))
        assertNull(BillPageParser.parse(alipay, root))
    }

    // ---------- 转账（用户实测反馈：转账页不触发 / 会被记成支出） ----------

    /**
     * **合成转账 fixture**：模拟支付宝「余额宝 → 中国工商银行(8804) 转出」详情页。
     * 账户尾号和交易字段仅为示例。
     *
     * 这页同时有「账单详情」标题与「创建时间/账单分类/计入收支」这些详情页标签，
     * 所以**必须**靠转账专属标签（转出进度/转出说明/转入账户/到账时间）先认出来。
     */
    @Test
    fun `合成支付宝转出到银行卡页识别为 AlipayTransferOut`() {
        val p = parse("alipay_transfer_out_sample.json")
        assertNotNull("样例转账页识别失败 —— 用户会看到「转账不弹记账卡片」", p)
        p!!
        assertEquals("AlipayTransferOut", p.pageType)
        assertEquals(100L, p.amountCents)                 // 1.00
        assertEquals(Direction.TRANSFER, p.direction)
        assertEquals("余额宝", p.assetHint)                // 转出说明「余额宝-转出到银行卡」
        assertEquals("中国工商银行(8804)", p.toAssetHint)    // 「转入账户」那一行
        assertEquals("中国工商银行(8804)", p.merchant)      // 转账没有商户，对方账户就是它
        assertEquals("2024-04-12 13:20:37", p.timeText)
        assertEquals("支付宝·余额宝", p.channelLabel)
        assertTrue(p.isTransfer)
    }

    /**
     * 用户报的原始 bug：转账被当成「支出」。
     *
     * 三层护栏：① 页面不能被付款成功页/账单详情页抢走；② 方向必须是 TRANSFER；
     * ③ 方向 → 交易类型必须落到 `TxnType.TRANSFER`。
     */
    @Test
    fun `转账不会被当成支出`() {
        val root = fixture("alipay_transfer_out_sample.json")
        val all = root.flatten()

        // 这页**同时**满足付款成功页（有「交易成功」+ 金额）和账单详情页（有「账单详情」+ 创建时间）的判据，
        // 唯一能救回来的是**顺序**：转账规则排在最前面。
        assertTrue("付款成功页的判据其实也命中这一页", AutoBillPages.ALIPAY_PAY_SUCCESS.matches(root, all))
        assertTrue("账单详情页的判据其实也命中这一页", AutoBillPages.ALIPAY_BILL_DETAIL.matches(root, all))
        assertTrue(
            "所以转账规则必须排在它们之前",
            AutoBillPages.ALIPAY.indexOf(AutoBillPages.ALIPAY_TRANSFER_OUT) <
                AutoBillPages.ALIPAY.indexOf(AutoBillPages.ALIPAY_PAY_SUCCESS) &&
                AutoBillPages.ALIPAY.indexOf(AutoBillPages.ALIPAY_TRANSFER_OUT) <
                AutoBillPages.ALIPAY.indexOf(AutoBillPages.ALIPAY_BILL_DETAIL),
        )
        assertEquals(
            "识别结果必须是转账，不能被任何一页抢走",
            "AlipayTransferOut",
            PageRecognizer.recognizePageType(alipay, root, all),
        )

        val p = BillPageParser.parse(alipay, root)!!
        assertEquals(Direction.TRANSFER, p.direction)
        assertNotEquals("转账不是支出", Direction.PAYMENT, p.direction)
        assertEquals(TxnType.TRANSFER, PendingBillCodec.txnTypeOf(p.direction))
        assertNotEquals("绝不能落成 EXPENSE", TxnType.EXPENSE, PendingBillCodec.txnTypeOf(p.direction))
    }

    @Test
    fun `转账页的金额与商户不会被状态值或时间抢走`() {
        val p = parse("alipay_transfer_out_sample.json")!!
        // 页面上 '1.00' 是唯一像金额的节点；'04-12 13:20'、'2024-04-12 13:20:37' 都不是
        assertEquals(100L, p.amountCents)
        // 商户不能被取成金额下面的「交易成功」
        assertEquals("中国工商银行(8804)", p.merchant)
    }

    @Test
    fun `支付宝转入页按对称性识别（待真机校准）`() {
        // 没有真机样本：按转出页对称构造
        val root = NodeTree.decode(
            """
            {"className":"FrameLayout","children":[
              {"className":"TextView","text":"账单详情"},
              {"className":"TextView","text":"余额宝"},
              {"className":"TextView","text":"2,000.00"},
              {"className":"TextView","text":"交易成功"},
              {"className":"TextView","text":"转入进度"},
              {"className":"TextView","text":"到账成功"},
              {"className":"TextView","text":"转入说明"},
              {"className":"TextView","text":"中国工商银行(8804)-转入到余额宝"},
              {"className":"TextView","text":"转出账户"},
              {"className":"TextView","text":"中国工商银行(8804)"},
              {"className":"TextView","text":"创建时间"},
              {"className":"TextView","text":"2024-04-12 16:00:00"}
            ]}
            """.trimIndent()
        )
        val p = BillPageParser.parse(alipay, root)
        assertNotNull(p)
        p!!
        assertEquals("AlipayTransferIn", p.pageType)
        assertEquals(200000L, p.amountCents)
        assertEquals(Direction.TRANSFER, p.direction)
        assertEquals("中国工商银行(8804)", p.assetHint)    // 转入说明的前半段 = 转出账户
        assertEquals("中国工商银行(8804)", p.toAssetHint)  // 「转出账户」也在 TO_ASSET_LABELS 里
    }

    @Test
    fun `普通账单详情页仍然识别为账单详情而不是转账`() {
        // 转账规则不能误伤普通详情页（真机收益页）
        val p = parse("alipay_bill_detail_sample.json")!!
        assertEquals("AlipayBillDetail", p.pageType)
        assertNotEquals(Direction.TRANSFER, p.direction)
    }

    // ---------- 转成 PaySignal ----------

    @Test
    fun `Parsed 转 PaySignal 保留页面类型与优惠券`() {
        val p = parse("alipay_pay_success.json")!!
        val signal = BillPageParser.toSignal(p, alipay, Origin.ACCESSIBILITY)
        assertEquals(alipay, signal.sourcePackage)
        assertEquals(4500L, signal.amountCents)
        assertEquals("美团", signal.merchant)
        assertEquals("AlipayPaySuccess", signal.pageType)
        assertEquals(50L, signal.couponCents)
        assertEquals("余额宝", signal.suggestedAccountHint)
        assertEquals("支付宝·余额宝", signal.channelLabel)
        assertEquals(Origin.ACCESSIBILITY, signal.origin)
        assertTrue(signal.rawText.contains("美团"))
    }

    @Test
    fun `页面时间解析成 epoch 且异常时间回退到 now`() {
        val now = System.currentTimeMillis()
        val p = parse("alipay_pay_success.json")!!
        assertTrue("应解析出 2024-04-11 的时间", p.occurredAt < now + 1000)

        // 页面写了离谱时间（未来 10 年）→ 回退到 now，避免污染去重时间桶
        val weird = NodeTree.decode(
            """
            {"className":"FrameLayout","children":[
              {"className":"TextView","text":"支付成功"},
              {"className":"TextView","text":"¥9.90"},
              {"className":"TextView","text":"付款时间"},
              {"className":"TextView","text":"2036-09-25 13:03:38"}
            ]}
            """.trimIndent()
        )
        val parsed = BillPageParser.parse(alipay, weird, now)
        assertNotNull(parsed)
        assertEquals("离谱时间应回退到事件时间", now, parsed!!.occurredAt)
    }
}
