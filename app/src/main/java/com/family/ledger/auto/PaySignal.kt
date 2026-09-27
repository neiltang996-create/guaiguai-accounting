package com.family.ledger.auto

/**
 * 自动记账 · 统一信号模型。
 *
 * 无障碍服务与通知监听都只做一件事：把各自看到的界面/通知文本翻译成 [PaySignal]，
 * 之后的去重、猜资产、猜分类、落库、通知全部由 [AutoBillPipeline] 处理。
 *
 * **本文件必须保持纯 Kotlin（零 Android 依赖）**，这样解析与去重才能进 JVM 单测。
 */

/** 付款渠道（包名维度的粗分类）。 */
enum class Channel {
    ALIPAY,
    WECHAT,
    UNIONPAY,
    UNKNOWN;

    companion object {
        fun of(pkg: String?): Channel = when (pkg) {
            PayPackages.ALIPAY, PayPackages.ALIPAY_RC -> ALIPAY
            PayPackages.WECHAT -> WECHAT
            PayPackages.UNIONPAY, PayPackages.UNIONPAY_TSM -> UNIONPAY
            else -> UNKNOWN
        }
    }
}

/** 资金方向。 */
enum class Direction {
    /** 支出：付款/消费。 */
    PAYMENT,

    /** 退款：钱退回来了（对应 TxnType.REFUND）。 */
    REFUND,

    /** 收入：收款/到账。 */
    INCOME,

    /**
     * 转账：**自己名下的两个账户之间挪钱**（余额宝 → 工商银行储蓄卡）。
     *
     * 落库为 `TxnType.TRANSFER`，必须同时有转出资产（`assetId`）与转入资产（`toAssetId`），
     * 否则余额会算错 —— 见 [AutoBillPipeline] 里「转不出目标资产就不入账」的保护。
     *
     * 注意：两个人之间的转账（微信转给老婆）语义上钱离开了家庭账户，**不算**这里；
     * 这里专指用户自己账户之间的搬钱。
     */
    TRANSFER,
}

/** 关注的支付类应用包名，与 AndroidManifest 的 `<queries>` 和 accessibility 配置保持一致。 */
object PayPackages {
    const val ALIPAY = "com.eg.android.AlipayGphone"
    const val ALIPAY_RC = "com.eg.android.AlipayGphoneRC"
    const val ICBC = "com.icbc"
    const val WECHAT = "com.tencent.mm"
    const val UNIONPAY = "com.unionpay"
    const val UNIONPAY_TSM = "com.unionpay.tsmservice"

    val WATCHED: Set<String> = setOf(ALIPAY, ALIPAY_RC, WECHAT, UNIONPAY, UNIONPAY_TSM, ICBC)

    fun isWatched(pkg: String?): Boolean = pkg != null && pkg in WATCHED
}

/**
 * 信号来源通道。
 *
 * 同一个付款会被**两条通道各看到一次**（无障碍读支付结果页、通知监听读付款通知），
 * 它们文本不同但描述的是同一笔支付。去重时只有「来源通道不同 + 时间相近」才能
 * 断定是同一次支付的双通道上报，从而静默合并；否则一律提示用户，绝不悄悄丢账。
 */
enum class Origin {
    ACCESSIBILITY,
    NOTIFICATION,
    UNKNOWN,
}

/**
 * 一条待处理的付款信号。
 *
 * @param sourcePackage 来源包名（支付宝 / 微信 / 云闪付）。
 * @param amountCents 金额（分，恒为正）。
 * @param merchant 商户名，拿不到为 null。
 * @param rawText 原始文本（已截断到安全长度），用于回溯与二次解析。
 * @param occurredAt 发生时间（epoch millis）。
 * @param channel 渠道枚举。
 * @param direction 资金方向。
 * @param suggestedAccountHint 账户线索，如「零钱」「余额宝」「银行卡(0208)」。
 *        页面规则引擎给的 `firstAsset`（转出/付款资产）就写在这里，**优先于关键词猜测**。
 * @param toAccountHint 转入资产的线索（转账页的「转入账户」，如 `中国工商银行(8804)`）；
 *        只有 [Direction.TRANSFER] 才有意义。
 * @param channelLabel 付款渠道细节（微信零钱 / 支付宝·花呗 / 招商银行(8806)…）。
 *        这是 [DedupEngine] 判断「同一商户的两笔合理消费」与「一笔消费的两条通知」的关键依据，
 *        会随账单一起持久化（写入待确认账单的 rawText 与流水的备注前缀）。
 * @param orderId 交易订单号 / 商家订单号。能拿到时优先用它作为去重指纹，比「金额±时间」稳得多。
 * @param origin 这条信号是哪个通道报上来的（用于区分「同一次支付的双通道上报」与「两笔真实消费」）。
 * @param pageType 页面规则引擎识别出的页面类型（如 `AlipayPaySuccess`）；通知/文本兜底路线为 null。
 * @param couponCents 优惠券抵扣合计（分，**恒为正** = 抵扣了多少）。
 *        钱迹把每条券抽成负数再累加成 `feeAmount`；我们按「正数 = 优惠额」存，与账本/CSV 的「优惠券」列一致。
 */
data class PaySignal(
    val sourcePackage: String,
    val amountCents: Long,
    val merchant: String?,
    val rawText: String,
    val occurredAt: Long,
    val channel: Channel,
    val direction: Direction,
    val suggestedAccountHint: String?,
    /** 转账的转入资产线索（页面的「转入账户」）；非转账为 null。 */
    val toAccountHint: String? = null,
    val channelLabel: String? = null,
    val orderId: String? = null,
    val origin: Origin = Origin.UNKNOWN,
    val pageType: String? = null,
    val couponCents: Long = 0L,
    val rawEventId: String? = null,
    /** 页面明确给出的交易时间，和事件到达时间分开；缺失时不可用于强去重。 */
    val receiptTimeMillis: Long? = null,
    val receiptImage: String? = null,
)
