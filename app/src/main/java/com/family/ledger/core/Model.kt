package com.family.ledger.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 金额一律以「分」为单位的 Long 存储与计算，绝不用 Double。
 * 展示时再格式化为 ¥1,234.56。
 */
object Money {

    fun format(cents: Long, symbol: String = "¥"): String {
        val neg = cents < 0
        val abs = if (neg) -cents else cents
        val yuan = abs / 100
        val fen = abs % 100
        val grouped = yuan.toString().reversed().chunked(3).joinToString(",").reversed()
        return buildString {
            if (neg) append('-')
            append(symbol)
            append(grouped)
            if (fen != 0L) {
                append('.')
                append(fen.toString().padStart(2, '0'))
            }
        }
    }

    /** 解析用户输入 / CSV 金额字符串，容忍千分位、¥、空格、全角小数点。 */
    fun parseToCents(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val cleaned = raw.trim()
            .replace("¥", "").replace("￥", "")
            .replace(",", "").replace("，", "")
            .replace("。", ".")
            .replace(" ", "")
        if (cleaned.isEmpty() || cleaned == "-" || cleaned == ".") return 0L
        val neg = cleaned.startsWith("-")
        val body = if (neg || cleaned.startsWith("+")) cleaned.substring(1) else cleaned
        val dot = body.indexOf('.')
        val intPart: String
        val fracPart: String
        if (dot >= 0) {
            intPart = body.substring(0, dot)
            fracPart = body.substring(dot + 1)
        } else {
            intPart = body
            fracPart = ""
        }
        val intVal = intPart.filter { it.isDigit() }.ifEmpty { "0" }.toLongOrNull() ?: 0L
        // 小数最多两位，多余直接截断（与钱迹一致）
        val frac2 = fracPart.filter { it.isDigit() }.padEnd(2, '0').take(2)
        val fracVal = frac2.toLongOrNull() ?: 0L
        val cents = intVal * 100 + fracVal
        return if (neg) -cents else cents
    }

    /** 供 CSV 导出：保留两位小数。 */
    fun toPlainString(cents: Long): String {
        val neg = cents < 0
        val abs = if (neg) -cents else cents
        val s = "${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
        return if (neg) "-$s" else s
    }
}

object TimeFmt {
    val ZONE: ZoneId = ZoneId.systemDefault()
    private val CSV = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun toCsv(epochMillis: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZONE).format(CSV)

    fun toDay(epochMillis: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZONE).format(DAY)

    /** 解析钱迹 CSV 时间，容错 "yyyy-MM-dd HH:mm:ss" / "yyyy-MM-dd HH:mm" / "yyyy-MM-dd"。 */
    fun parseCsv(raw: String?): Long? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        return try {
            when {
                s.length >= 19 -> LocalDateTime.parse(s.substring(0, 19), CSV)
                s.length == 16 -> LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                s.length == 10 -> LocalDate.parse(s, DAY).atStartOfDay()
                else -> LocalDateTime.parse(s.substringBefore('.'), CSV)
            }.atZone(ZONE).toInstant().toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    fun startOfDay(epochMillis: Long): Long =
        Instant.ofEpochMilli(epochMillis).atZone(ZONE).toLocalDate().atStartOfDay(ZONE).toInstant().toEpochMilli()

    fun startOfMonth(epochMillis: Long): Long =
        Instant.ofEpochMilli(epochMillis).atZone(ZONE).toLocalDate().withDayOfMonth(1)
            .atStartOfDay(ZONE).toInstant().toEpochMilli()
}

/** 轻量 ID 生成：时间前缀保证同一设备内单调，随机后缀防碰撞。 */
object Ids {
    private val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz"

    /** 家庭配对码用的字母表：去掉 0/O/1/I 等易混字符，方便口头念给配偶。 */
    private const val CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"

    fun newId(prefix: String = ""): String {
        val ts = System.currentTimeMillis().toString(36)
        val rnd = (1..8).map { ALPHABET.random() }.joinToString("")
        return if (prefix.isEmpty()) "$ts$rnd" else "$prefix$ts$rnd"
    }

    /** 8 位家庭配对码，两台手机输入同一个码才会同步到同一个家庭。 */
    fun newFamilyCode(): String = (1..8).map { CODE_ALPHABET.random() }.joinToString("")

    /** 归一化用户输入的配对码：去空格与分隔符、统一大写。 */
    fun normalizeFamilyCode(raw: String?): String? {
        val s = raw?.uppercase()?.filter { it.isLetterOrDigit() } ?: return null
        return s.ifEmpty { null }
    }
}
