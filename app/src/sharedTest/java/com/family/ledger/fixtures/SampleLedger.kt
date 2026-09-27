package com.family.ledger.fixtures

/** Hand-authored synthetic CSV. No exported financial records are used in public tests. */
object SampleLedger {
    val text = "\uFEFF" + listOf(
        "ID,时间,分类,二级分类,类型,金额,币种,账户1,账户2,备注,已报销,手续费,优惠券,记账者,账单标记,标签,账单图片,关联账单",
        "demo-1,2024-04-01 12:00:00,吃,早餐,支出,30.00,CNY,支付宝小荷包(示例日常),,示例早餐,,,,用户 A,,示例,,",
        "demo-2,2024-04-02 12:00:00,收入,工资,收入,100.00,CNY,支付宝小荷包(示例日常),,示例工资,,,,用户 B,,,,",
        "demo-3,2024-04-03 12:00:00,吃,早餐,退款,10.00,CNY,支付宝小荷包(示例日常),,示例退款,,,,用户 A,,,,demo-1",
        "demo-4,2024-04-04 12:00:00,,,转账,20.00,CNY,支付宝小荷包(示例日常),示例家庭储蓄卡,示例转账,,,,用户 B,,,,",
        "demo-5,2024-04-05 12:00:00,,,还款,5.00,CNY,支付宝小荷包(示例日常),示例信用卡,示例还款,,,,用户 A,,,,",
        "demo-6,2024-04-06 12:00:00,,,债务-还款,5.00,CNY,支付宝小荷包(示例日常),示例信用卡,示例债务还款,,,,用户 B,,,,",
    ).joinToString("\r\n", postfix="\r\n")
    // Shared asset: -30 +100 +10 -20 -5 -5 = +50 yuan.
    const val COUNT = 6
    const val SHARED_BALANCE_CENTS = 5000L
}
