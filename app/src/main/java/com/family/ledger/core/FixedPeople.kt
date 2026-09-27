package com.family.ledger.core

/** 人物是固定业务身份；设备与人物分别存储。FAMILY 只用于消费维度。 */
object FixedPeople {
    const val A = "PERSON_A"
    const val B = "PERSON_B"
    const val FAMILY = "FAMILY"
    const val HOUSEHOLD = com.family.ledger.BuildConfig.HOUSEHOLD_ID
    val names = linkedMapOf(A to com.family.ledger.BuildConfig.PERSON_A_NAME, B to com.family.ledger.BuildConfig.PERSON_B_NAME)
    fun idForName(name: String?): String? = names.entries.firstOrNull { it.value == name?.trim() }?.key
    fun requireId(id: String): String = id.also { require(it in names) { "只支持用户 A和用户 B" } }
    fun name(id: String?): String = if (id == FAMILY) "家庭共同消费" else names[id] ?: "未选择"
}
