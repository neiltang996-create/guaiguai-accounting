package com.family.ledger.data.csv

import com.family.ledger.core.Money
import com.family.ledger.core.TimeFmt
import com.family.ledger.data.db.entity.TxnType

/**
 * 钱迹 CSV 的一行。
 *
 * 18 列**全部以原始字符串保留** —— 这是「parse → write → parse 往返一致」的前提：
 * 一旦在解析阶段做归一化（金额补两位小数、时间重排），导出结果就会和源文件产生差异。
 *
 * 需要结构化取值时用下面的辅助属性，它们只走 [Money] / [TimeFmt]：
 * 金额一律「分」的 Long，时间一律 epoch millis。
 */
data class QianJiRow(
    val id: String = "",
    val time: String = "",
    val category: String = "",
    val subCategory: String = "",
    val type: String = "",
    val amount: String = "",
    val currency: String = "",
    val account1: String = "",
    val account2: String = "",
    val note: String = "",
    val reimbursed: String = "",
    val fee: String = "",
    val coupon: String = "",
    val recorder: String = "",
    val flag: String = "",
    val tags: String = "",
    val images: String = "",
    val relatedBill: String = "",
) {

    // ---------- 解析后的辅助属性 ----------

    /** 金额（分）。 */
    val amountCents: Long get() = Money.parseToCents(amount)

    /** 手续费（分）。 */
    val feeCents: Long get() = Money.parseToCents(fee)

    /** 优惠券抵扣（分）。 */
    val couponCents: Long get() = Money.parseToCents(coupon)

    /** 发生时间（epoch millis）；为空或格式非法时返回 null。 */
    val occurredAt: Long? get() = TimeFmt.parseCsv(time)

    /** 钱迹的「不计收支」。 */
    val excludedFromStats: Boolean get() = flag.trim() == FLAG_EXCLUDE

    /** 「已报销」列有值即为已报销（空 / 0 / 否 视为未报销）。 */
    val isReimbursed: Boolean
        get() {
            val v = reimbursed.trim()
            return v.isNotEmpty() && v != "0" && v != "否"
        }

    /** 已报销金额（分）：该列放的是金额时才有意义，否则为 0。 */
    val reimbursedCents: Long get() = if (isReimbursed) Money.parseToCents(reimbursed) else 0L

    /** 映射后的本地交易类型；未知类型返回 null（导入时应记为错误行）。 */
    val txnType: TxnType? get() = typeOf(type)

    /** 标签（逗号分隔）。 */
    val tagList: List<String> get() = splitList(tags)

    /** 账单图片（逗号分隔）。 */
    val imageList: List<String> get() = splitList(images)

    /** 关联账单的钱迹 ID（逗号分隔，通常只有一个）。 */
    val relatedIds: List<String> get() = splitList(relatedBill)

    /** 整行是否为空（用于丢掉尾随的空行）。 */
    val isBlank: Boolean
        get() = id.isBlank() && time.isBlank() && category.isBlank() && subCategory.isBlank() &&
            type.isBlank() && amount.isBlank() && currency.isBlank() && account1.isBlank() &&
            account2.isBlank() && note.isBlank() && reimbursed.isBlank() && fee.isBlank() &&
            coupon.isBlank() && recorder.isBlank() && flag.isBlank() && tags.isBlank() &&
            images.isBlank() && relatedBill.isBlank()

    /** 按钱迹列顺序输出 18 个字段。 */
    fun toFields(): List<String> = listOf(
        id, time, category, subCategory, type, amount, currency,
        account1, account2, note, reimbursed, fee, coupon,
        recorder, flag, tags, images, relatedBill,
    )

    companion object {

        /** 「账单标记」列的不计收支取值。 */
        const val FLAG_EXCLUDE = "不计收支"

        /** 钱迹的「债务-还款」，语义等价于 [TxnType.REPAYMENT]。 */
        const val TYPE_DEBT_REPAYMENT = "债务-还款"

        /** 列数。 */
        const val COLUMN_COUNT = 18

        /** 表头，与钱迹导出一字不差、顺序一致。 */
        val HEADER: List<String> = listOf(
            "ID", "时间", "分类", "二级分类", "类型", "金额", "币种",
            "账户1", "账户2", "备注", "已报销", "手续费", "优惠券",
            "记账者", "账单标记", "标签", "账单图片", "关联账单",
        )

        /** 表头整行（逗号分隔）。 */
        val HEADER_LINE: String = HEADER.joinToString(",")

        /** 按列顺序构造；缺列补空串，多列截断。 */
        fun fromList(fields: List<String>): QianJiRow = QianJiRow(
            id = fields.getOrElse(0) { "" },
            time = fields.getOrElse(1) { "" },
            category = fields.getOrElse(2) { "" },
            subCategory = fields.getOrElse(3) { "" },
            type = fields.getOrElse(4) { "" },
            amount = fields.getOrElse(5) { "" },
            currency = fields.getOrElse(6) { "" },
            account1 = fields.getOrElse(7) { "" },
            account2 = fields.getOrElse(8) { "" },
            note = fields.getOrElse(9) { "" },
            reimbursed = fields.getOrElse(10) { "" },
            fee = fields.getOrElse(11) { "" },
            coupon = fields.getOrElse(12) { "" },
            recorder = fields.getOrElse(13) { "" },
            flag = fields.getOrElse(14) { "" },
            tags = fields.getOrElse(15) { "" },
            images = fields.getOrElse(16) { "" },
            relatedBill = fields.getOrElse(17) { "" },
        )

        /** 判断一列字段是否为钱迹表头。 */
        fun isHeader(fields: List<String>): Boolean =
            fields.size >= 2 && fields[0].trim() == "ID" && fields[1].trim().startsWith("时间")

        /** 钱迹「类型」列 → 本地枚举，含「债务-还款」。 */
        fun typeOf(raw: String?): TxnType? = when (val v = raw?.trim()) {
            "支出" -> TxnType.EXPENSE
            "收入" -> TxnType.INCOME
            "退款" -> TxnType.REFUND
            "转账" -> TxnType.TRANSFER
            "还款", TYPE_DEBT_REPAYMENT -> TxnType.REPAYMENT
            else -> TxnType.fromCn(v)
        }

        /**
         * 本地枚举 → 钱迹「类型」列。
         *
         * 注意：余额校准（BALANCE_ADJUST）在钱迹里对应「平账」，即
         * 支出 + 不计收支；本项目里它的资产净额由校准锚点体现。
         */
        fun typeToCn(type: TxnType): String = when (type) {
            TxnType.EXPENSE, TxnType.BALANCE_ADJUST -> "支出"
            TxnType.INCOME -> "收入"
            TxnType.REFUND -> "退款"
            TxnType.TRANSFER -> "转账"
            TxnType.REPAYMENT -> "还款"
        }

        /** 逗号 / 全角逗号 / 顿号分隔的多值列。 */
        fun splitList(raw: String?): List<String> {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty()) return emptyList()
            return s.split(',', '，', '、').map { it.trim() }.filter { it.isNotEmpty() }
        }
    }
}
