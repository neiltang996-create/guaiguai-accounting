package com.family.ledger.auto.rules

import com.family.ledger.auto.Direction
import com.family.ledger.auto.PayPackages

/**
 * **页面规则表** —— 钱迹那套「先判页面、再用该页面的专属规则取值」的落地。
 *
 * 规则内容是我们自己按中文支付页的通用结构写的（锚点文案需要真机 dump 校准），
 * 与钱迹的规则表**不是同一份数据**：这里没有复制它的代码，只借鉴了「pageType + 规则 DSL」的架构。
 *
 * 顺序即优先级（越具体越靠前）：
 *  - 支付宝：账单详情 → 退款成功 → 支付成功
 *    （账单详情页的「当前状态」可能就是「支付成功」，所以它必须排最前）
 *  - 微信：账单详情 → 支付成功
 *  - 云闪付：支付成功
 */
object AutoBillPages {

    private const val TEXT_VIEW = "TextView"

    // ---------- 金额的几种写法（一条都取不到就算页面没识别成功） ----------

    /** `¥45.00` / `￥1,234` / `¥45`。 */
    val AMOUNT_SYMBOL: Matcher = Matcher.RegexText("""^[¥￥]\s*(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d{1,2})?$""")

    /** `已支付¥45.00` / `实付 45.00元` / `付款金额：45.00`。 */
    val AMOUNT_LABELED: Matcher = Matcher.RegexText(
        """^(?:已支付|实付|付款金额|支付金额|付款|金额)\s*[:：]?\s*[¥￥]?\s*(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d{1,2})\s*元?$"""
    )

    /** `-45.00` / `+45.00` / `-0.50元`（账单详情页常用带符号写法）。 */
    val AMOUNT_SIGNED: Matcher = Matcher.RegexText("""^[-+−]\s*[¥￥]?\s*(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d{1,2})\s*元?$""")

    /** `45.00` / `1,234.50` / `45元`（必须有小数或「元」，避免把支付页上的无关数字当金额）。 */
    val AMOUNT_BARE: Matcher = Matcher.RegexText(
        """^(?:(?:\d{1,3}(?:,\d{3})+|\d+)\.\d{1,2}\s*元?|(?:\d{1,3}(?:,\d{3})+|\d+)\s*元)$"""
    )

    /** 页面识别用：有金额才算支付相关页面（账单详情页常写成 `-45.00`，所以带符号的也算）。 */
    /**
     * **中文前后缀形式**：`支出680元` / `收入100元` / `支出680.00元`。
     *
     * 真机实测（2026-09-26，支付宝收钱码付款成功页）：金额就是**一个节点**
     * ```
     * '支出680元'      ← 前缀「支出」+ 数字（无小数点）+ 后缀「元」
     * ```
     * 上面四种写法**全都匹配不上** → 抽不到金额 → 不产生待确认账单
     * → **卡片永远不弹**。这是用户反馈「还是不会弹出呢，钱迹就可以」的直接原因。
     *
     * 要求「方向前缀 + 元」同时出现，避免把页面上的随机数字当金额。
     */
    val AMOUNT_CN: Matcher = Matcher.RegexText(
        """^(?:支出|收入|已支付|已收款|付款|收款|转账|退款|消费)\s*[¥￥]?\s*""" +
            """(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d{1,2})?\s*元$"""
    )

    val AMOUNT_ANY: Matcher =
        Matcher.anyOf(AMOUNT_SYMBOL, AMOUNT_LABELED, AMOUNT_SIGNED, AMOUNT_BARE, AMOUNT_CN)

    private val ASSET_LABELS = arrayOf(
        "交易方式", "付款方式", "支付方式", "扣款方式", "付款账户", "支付账户",
        "退款去向", "退款方式", "退回方式",
    )

    // 「对方账户」是真机实测补充：支付宝账单详情页用的就是这个标签（2026-09-26 抓取）
    private val MERCHANT_LABELS = arrayOf(
        "商户名称", "商户全称", "收款方", "收款商户", "对方账户", "对方名称", "交易对方",
    )

    private val ORDER_LABELS = arrayOf("订单号", "交易单号", "商户单号", "交易流水号", "流水号")

    private val TIME_LABELS = arrayOf("付款时间", "创建时间", "交易时间", "支付时间", "下单时间")

    private val STATUS_LABELS = arrayOf("当前状态", "交易状态", "订单状态")

    /**
     * **转账页专属标签**（真机实测 2026-09-26，支付宝「余额宝 → 中国工商银行(8804)」）。
     *
     * 这页同时有「账单详情」标题和「创建时间/账单分类/计入收支」这些详情页标签，
     * 唯一能把它和普通账单详情区分开的就是下面这些转账才有的行。
     * 判据用「任一 + 金额」，所以两个方向各自的标签分开列。
     */
    /**
     * 转出页的**结构标签**（只放页面独有、绝不会出现在账单列表项里的词）。
     *
     * ★ 用户实测误报：支付宝「账单」列表页里有一条列表项叫
     *   `余额宝-转出到银行卡`，而锚点里原先有「转出到」「转出到银行卡」
     *   —— 于是**整个账单列表页被误判成转账页**，还把顶部汇总的
     *   「9月支出 ¥12,345.67」当成金额弹了卡片。
     *
     *   教训：**页面锚点必须是「只在详情页出现的结构标签」**，
     *   不能是「详情页里某个值的近似文案」—— 列表项就是值的集合。
     *   「转出进度 / 转出说明 / 转入账户 / 到账时间」这五个在真实转出页上都在，
     *   而账单列表页一个都没有。
     */
    private val TRANSFER_OUT_LABELS = arrayOf(
        "转出进度", "转出说明", "转入账户", "到账时间",
    )

    /** 转入方向（**没有真机样本，按对称性写，待真机 dump 校准**）。 */
    /** 同上：只放结构标签，不放「转入到」这类会出现在列表项里的近似文案。 */
    private val TRANSFER_IN_LABELS = arrayOf(
        "转入进度", "转入说明", "转出账户", "转入时间", "到账账户",
    )

    /** 转账说明 / 转入账户 ↓ 用来推「转出资产 / 转入资产」。 */
    private val TRANSFER_NOTE_LABELS = arrayOf("转出说明", "转入说明", "转账说明")

    private val TO_ASSET_LABELS = arrayOf(
        "转入账户", "转出账户", "转入卡", "到账账户", "收款账户",
    )

    /**
     * 状态**值**。
     *
     * 真机实测（2026-09-26，支付宝账单详情页）：页面上只有「交易成功」这个**值**，
     * **没有**「当前状态 / 交易状态」这个标签 —— 早先只用 [STATUS_LABELS] 做页面匹配，
     * 导致这类页面永远识别不出来、记账卡片不弹。
     */
    private val STATUS_VALUES = arrayOf(
        "交易成功", "支付成功", "付款成功", "交易关闭", "已关闭", "等待付款", "等待确认",
        "已退款", "退款成功", "交易失败", "已撤销", "还款成功", "已到账", "部分退款",
    )

    /**
     * **账单详情页专属标签**。
     *
     * 真机对比得出（2026-09-26）：付款成功页和账单详情页**都有「账单详情」四个字**
     * —— 在成功页上它是一个「账单详情」链接。所以「账单详情」不能单独作为判据。
     * 真正的区分特征是详情页才有的这些标签。
     */
    private val BILL_DETAIL_LABELS = arrayOf(
        "创建时间", "商品说明", "账单分类", "计入收支", "账单管理", "交易单号", "对方账户",
    )

    /**
     * 优惠券/立减关键词（我们自己整理的常见文案）。
     *
     * 每条规则抽一个金额并**累加**；关键词互相包含（`到店支付红包` ⊃ `红包`）时，
     * 同一个锚点只会被计入一次（见 [Rule] 的累加语义）。
     */
    private val COUPON_KEYWORDS = arrayOf(
        "碰一下立减", "碰一下餐饮立减", "到店扫码支付红包", "百次立减", "支付宝立减",
        "视频红包", "花呗立减", "碰友日立减", "现金抵价券", "到店支付红包",
        "到店支付立减券", "每日必减", "福利金抵扣", "品牌商家立减", "商家立减",
        "银行立减金", "立减金", "红包抵扣", "红包", "优惠券", "优惠", "抵扣",
    )

    // ---------- 字段规则（按渠道复用） ----------

    private fun amountRules(): List<Rule> = listOf(
        // 先带币种符号的，再带标签的，再带符号的，最后才是裸数字
        Rule(AMOUNT_SYMBOL, Extractor.Current, FieldNames.AMOUNT, FieldKind.AMOUNT),
        Rule(AMOUNT_LABELED, Extractor.Current, FieldNames.AMOUNT, FieldKind.AMOUNT),
        Rule(AMOUNT_SIGNED, Extractor.Current, FieldNames.AMOUNT, FieldKind.AMOUNT),
        Rule(AMOUNT_CN, Extractor.Current, FieldNames.AMOUNT, FieldKind.AMOUNT),
        Rule(AMOUNT_BARE, Extractor.Current, FieldNames.AMOUNT, FieldKind.AMOUNT),
        // 符号单独记一份：抽不到「当前状态」时用它判方向
        Rule(AMOUNT_SIGNED, Extractor.Current, FieldNames.SIGN, FieldKind.SIGN),
    )

    private fun merchantRules(): List<Rule> = listOf(
        Rule(Matcher.containsAny(*MERCHANT_LABELS), Extractor.RowValue(), FieldNames.MERCHANT),
        // 支付成功页：金额下面紧挨着的那个非标签文本就是商户（`¥45.00` → `美团`）
        Rule(AMOUNT_SYMBOL, Extractor.Relative(1, fromClass = TEXT_VIEW, skipLabels = true), FieldNames.MERCHANT),
        Rule(AMOUNT_BARE, Extractor.Relative(1, fromClass = TEXT_VIEW, skipLabels = true), FieldNames.MERCHANT),
        Rule(AMOUNT_LABELED, Extractor.Relative(1, fromClass = TEXT_VIEW, skipLabels = true), FieldNames.MERCHANT),
    )

    private fun assetRules(): List<Rule> = listOf(
        Rule(Matcher.containsAny(*ASSET_LABELS), Extractor.RowValue(), FieldNames.FIRST_ASSET, FieldKind.ASSET),
    )

    private fun couponRules(): List<Rule> = COUPON_KEYWORDS.map { keyword ->
        Rule(Matcher.TextContains(keyword), Extractor.RowValue(), FieldNames.COUPON, FieldKind.COUPON)
    }

    private fun orderIdRules(): List<Rule> = listOf(
        Rule(Matcher.containsAny(*ORDER_LABELS), Extractor.RowValue(), FieldNames.ORDER_ID, FieldKind.ORDER_ID),
    )

    private fun timeRules(): List<Rule> = listOf(
        Rule(Matcher.containsAny(*TIME_LABELS), Extractor.RowValue(), FieldNames.TIME, FieldKind.TIME),
    )

    /**
     * 中文方向前缀 → [FieldNames.BILL_TYPE]。
     *
     * `支出680元` 这个节点本身就说明了方向，比「当前状态」标签更直接
     * （[Values.directionOf] 已经认识「支出/收入/退款」这些词）。
     */
    private fun directionCnRules(): List<Rule> = listOf(
        Rule(DIRECTION_CN, Extractor.Current, FieldNames.BILL_TYPE, FieldKind.BILL_TYPE),
    )

    /** 与 [AMOUNT_CN] 同形；匹配到就说明这个节点带方向前缀。 */
    private val DIRECTION_CN: Matcher = Matcher.RegexText(
        """^(?:支出|收入|已支付|已收款|付款|收款|转账|退款|消费)\s*[¥￥]?\s*""" +
            """(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d{1,2})?\s*元$"""
    )

    private fun statusRules(): List<Rule> = listOf(
        // 有标签的页面：从「当前状态 → 交易成功」取右边
        Rule(Matcher.containsAny(*STATUS_LABELS), Extractor.RowValue(), FieldNames.BILL_TYPE, FieldKind.BILL_TYPE),
        // 只有值的页面（合成支付宝账单详情）：节点本身就是状态
        Rule(Matcher.containsAny(*STATUS_VALUES), Extractor.Current, FieldNames.BILL_TYPE, FieldKind.BILL_TYPE),
    )

    /** 账单详情页：字段最全（含当前状态 → 资金方向）。 */
    private fun billDetailRules(): List<Rule> = amountRules() + merchantRules() + assetRules() +
        couponRules() + orderIdRules() + timeRules() + directionCnRules() + statusRules()

    // ---------- 转账专属规则 ----------

    /**
     * 转账页的「转入账户」→ `toAsset`。
     *
     * 真机：`转入账户 → 中国工商银行(8804)`，直接就是转入资产名（匹配资产时靠尾号 8804 对齐）。
     */
    private fun toAssetRules(): List<Rule> = listOf(
        Rule(Matcher.containsAny(*TO_ASSET_LABELS), Extractor.RowValue(), FieldNames.TO_ASSET, FieldKind.ASSET),
    )

    /** 转账说明整句（`余额宝-转出到银行卡`）留下来，供 [TransferText] 反推转出资产。 */
    private fun transferNoteRules(): List<Rule> = listOf(
        Rule(Matcher.containsAny(*TRANSFER_NOTE_LABELS), Extractor.RowValue(), FieldNames.TRANSFER_NOTE, FieldKind.TEXT),
    )

    /** 转账页没有「商户」，对方账户就是「转入账户」那一行。 */
    private fun transferMerchantRules(): List<Rule> = listOf(
        Rule(Matcher.containsAny(*TO_ASSET_LABELS), Extractor.RowValue(), FieldNames.MERCHANT),
    )

    /**
     * 转账页字段规则。
     *
     * 顺序要紧：**标签行排在「金额下面第一个文本」之前** ——
     * 真机转账页金额 `1.00` 下面紧跟的是状态值 `交易成功`，只有靠标签行才能拿到正确值。
     */
    private fun transferRules(): List<Rule> = amountRules() +
        transferMerchantRules() + merchantRules() +
        toAssetRules() + transferNoteRules() + assetRules() +
        couponRules() + timeRules() + directionCnRules()

    /** 转账页的页面匹配：转账专属标签 + 金额。 */
    private fun transferMatchers(labels: Array<String>): List<Matcher> =
        listOf(Matcher.containsAny(*labels), AMOUNT_ANY)

    /** 支付成功页：没有「当前状态」行，方向由页面默认值决定。 */
    private fun paySuccessRules(): List<Rule> = amountRules() + merchantRules() + assetRules() +
        couponRules() + orderIdRules() + timeRules() + directionCnRules()

    /**
     * 支付成功页的锚点。
     *
     * 用户反馈「转账也触发不了」后补充：**转账成功页写的是「转账成功」**，
     * 不在原来的三种写法里。微信转账则常见「已存入零钱」「已转账」。
     */
    private val SUCCESS_ANCHOR: Matcher = Matcher.containsAny(
        "支付成功", "交易成功", "付款成功",
        "转账成功", "已转账", "已存入零钱", "收款成功", "到账成功",
    )

    // ---------- 页面 ----------

    /**
     * **支付宝转出**（余额宝/银行卡 → 银行卡）：真机 fixture 驱动
     * （`auto-nodes/alipay_transfer_out_sample.json`，2026-09-26 抓取）。
     *
     * 必须排在 [ALIPAY_BILL_DETAIL] **之前**：这页同样有「账单详情」标题和
     * 「创建时间/账单分类/计入收支」标签，排后面会被详情页抢走，
     * 然后按默认方向记成一笔**支出** —— 正是用户报的那个 bug。
     */
    val ALIPAY_TRANSFER_OUT = PageRule(
        pageType = "AlipayTransferOut",
        matchers = transferMatchers(TRANSFER_OUT_LABELS),
        rules = transferRules(),
        defaultDirection = Direction.TRANSFER,
    )

    /**
     * **支付宝转入**（银行卡 → 余额宝）。
     *
     * ⚠️ 没有真机样本，按 [ALIPAY_TRANSFER_OUT] 的对称性写，
     * 锚点（转入进度/转入说明/转出账户）**待真机 dump 校准**。
     */
    val ALIPAY_TRANSFER_IN = PageRule(
        pageType = "AlipayTransferIn",
        matchers = transferMatchers(TRANSFER_IN_LABELS),
        rules = transferRules(),
        defaultDirection = Direction.TRANSFER,
    )

    // 余额宝收益页的金额没有正号；必须由单笔详情和收益说明共同确定收入方向。
    val ALIPAY_INTEREST_DETAIL = PageRule(
        pageType = "AlipayBillDetail",
        matchers = listOf(Matcher.TextContains("账单详情"), Matcher.TextContains("创建时间"),
            Matcher.TextContains("商品说明"), Matcher.RegexText("^余额宝-.*收益发放$")),
        rules = listOf(Rule(Matcher.RegexText("^余额宝-.*收益发放$"),
            Extractor.Literal("余额宝"), FieldNames.FIRST_ASSET, FieldKind.ASSET),
            Rule(Matcher.RegexText("^余额宝-.*收益发放$"), Extractor.Literal("收入"),
                FieldNames.BILL_TYPE, FieldKind.BILL_TYPE)) + billDetailRules(),
        defaultDirection = Direction.INCOME,
    )

    val ALIPAY_BILL_DETAIL = PageRule(
        pageType = "AlipayBillDetail",
        // 必须同时有「账单详情」和**详情页专属**的东西：成功页上也有「账单详情」链接，
        // 只看它会把付款成功页误判成详情页（单测已经钉住这条）。
        //
        // 两类判据都收：老版页面给「当前状态 → 交易关闭」这样的标签对；
        // 新版页面（真机抓取）只给「创建时间 / 商品说明 / 账单分类」。付款成功页两者都没有。
        matchers = listOf(
            Matcher.TextContains("账单详情"),
            Matcher.anyOf(
                Matcher.containsAny(*BILL_DETAIL_LABELS),
                Matcher.containsAny(*STATUS_LABELS),
            ),
        ),
        rules = listOf(Rule(AMOUNT_CN, Extractor.PreviousSiblingHeading, FieldNames.MERCHANT)) + billDetailRules(),
    )

    val ALIPAY_REFUND_SUCCESS = PageRule(
        pageType = "AlipayRefundSuccess",
        matchers = listOf(Matcher.TextContains("退款成功"), AMOUNT_ANY),
        rules = paySuccessRules(),
        defaultDirection = Direction.REFUND,
    )

    val ALIPAY_PAY_SUCCESS = PageRule(
        pageType = "AlipayPaySuccess",
        matchers = listOf(SUCCESS_ANCHOR, AMOUNT_ANY),
        rules = paySuccessRules(),
    )

    val WECHAT_BILL_DETAIL = PageRule(
        pageType = "WeChatBillDetail",
        matchers = listOf(Matcher.containsAny("账单详情", "交易详情"), Matcher.containsAny(*STATUS_LABELS)),
        rules = wechatDetailRules(),
    )

    // 微信新版详情没有标题。状态、支付时间、交易单号三行共同确认单笔详情，
    // 不能仅凭金额或商户名匹配，否则账单列表也会被识别。
    val WECHAT_UNTITLED_DETAIL = PageRule(
        pageType = "WeChatBillDetail",
        matchers = listOf(Matcher.containsAny(*STATUS_LABELS),
            Matcher.containsAny(*TIME_LABELS), Matcher.containsAny(*ORDER_LABELS), AMOUNT_ANY),
        rules = wechatDetailRules(),
    )

    private fun wechatDetailRules(): List<Rule> = listOf(
        // 顶部 -68.00 是实付，下方 ¥70.00 是原价；带币种符号不能优先于实付。
        Rule(AMOUNT_SIGNED, Extractor.Current, FieldNames.AMOUNT, FieldKind.AMOUNT),
    ) + billDetailRules()

    val WECHAT_PAY_SUCCESS = PageRule(
        pageType = "WeChatPaySuccess",
        matchers = listOf(SUCCESS_ANCHOR, AMOUNT_ANY),
        rules = paySuccessRules(),
    )

    val ICBC_BILL_DETAIL = PageRule(
        pageType = "IcbcBillDetail",
        matchers = listOf(Matcher.TextContains("明细详情"), Matcher.TextContains("交易卡号"),
            Matcher.TextContains("记账时间"), Matcher.TextContains("业务摘要"), Matcher.TextContains("人民币"), AMOUNT_SIGNED),
        rules = listOf(
            Rule(AMOUNT_SIGNED, Extractor.Current, FieldNames.AMOUNT, FieldKind.AMOUNT),
            Rule(AMOUNT_SIGNED, Extractor.Current, FieldNames.SIGN, FieldKind.SIGN),
            Rule(Matcher.TextContains("业务摘要"), Extractor.InAncestorRow("com.icbc:id/detail_item", "com.icbc:id/tv_right"), FieldNames.MERCHANT),
            Rule(Matcher.TextContains("交易卡号"), Extractor.InAncestorRow("com.icbc:id/detail_item", "com.icbc:id/tv_right"), FieldNames.FIRST_ASSET),
            Rule(Matcher.TextContains("记账时间"), Extractor.InAncestorRow("com.icbc:id/detail_item", "com.icbc:id/tv_right"), FieldNames.TIME, FieldKind.TIME),
            Rule(Matcher.TextContains("业务摘要"), Extractor.RowValue(), FieldNames.MERCHANT),
            Rule(Matcher.TextContains("交易卡号"), Extractor.RowValue(), FieldNames.FIRST_ASSET),
            Rule(Matcher.TextContains("记账时间"), Extractor.RowValue(), FieldNames.TIME, FieldKind.TIME),
        ),
    )

    val UNIONPAY_PAY_SUCCESS = PageRule(
        pageType = "UnionpayPaySuccess",
        matchers = listOf(SUCCESS_ANCHOR, AMOUNT_ANY),
        rules = paySuccessRules(),
    )

    /**
     * 支付宝的规则集，**顺序即优先级**。
     *
     * 转账排最前：转账详情页和账单详情页共享「账单详情 / 创建时间 / 账单分类」，
     * 只有转账专属标签能把它们分开；排后面 → 转账会被记成支出（用户实测报的 bug）。
     */
    val ALIPAY: List<PageRule> = listOf(
        ALIPAY_TRANSFER_OUT,
        ALIPAY_TRANSFER_IN,
        ALIPAY_INTEREST_DETAIL,
        ALIPAY_BILL_DETAIL,
        ALIPAY_REFUND_SUCCESS,
        ALIPAY_PAY_SUCCESS,
    )

    val WECHAT: List<PageRule> = listOf(WECHAT_BILL_DETAIL, WECHAT_UNTITLED_DETAIL, WECHAT_PAY_SUCCESS)

    val UNIONPAY: List<PageRule> = listOf(UNIONPAY_PAY_SUCCESS)

    /** 按包名取规则集；不在关注范围内返回空表（调用方走文本兜底）。 */
    fun forPackage(packageName: String?): List<PageRule> = when (packageName) {
        PayPackages.ALIPAY, PayPackages.ALIPAY_RC -> ALIPAY
        PayPackages.ICBC -> listOf(ICBC_BILL_DETAIL)
        PayPackages.WECHAT -> WECHAT
        PayPackages.UNIONPAY, PayPackages.UNIONPAY_TSM -> UNIONPAY
        else -> emptyList()
    }

    /** 全部页面（单测与统计用）。 */
    fun all(): List<PageRule> = ALIPAY + WECHAT + UNIONPAY + ICBC_BILL_DETAIL

    /** 全部优惠券关键词（单测用）。 */
    fun couponKeywords(): List<String> = COUPON_KEYWORDS.toList()
}
