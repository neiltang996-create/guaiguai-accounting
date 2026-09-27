package com.family.ledger.auto

/** Normalize OCR variants of structural labels; never guess or substitute monetary digits. */
object OcrTextNormalizer {
    fun normalize(raw: String): String {
        val simplified = raw.trim().trimStart('|', '丨').replace('−', '-').replace('－', '-')
            .replace('戶', '户').replace('稱', '称').replace('銀', '银').replace('儲', '储')
            .replace('賬', '账').replace('額', '额').replace('記', '记').replace('號', '号')
            .replace('間', '间').replace('時', '时').replace('幣', '币')
        return when (simplified) {
            "支付万式" -> "支付方式"
            "付款万式" -> "付款方式"
            else -> simplified
        }
    }
}
