package com.family.ledger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.family.ledger.data.db.entity.AssetEntity
import com.family.ledger.ui.assets.AssetGroups
import java.util.Locale

enum class SymbolTone(val dark: Color, val light: Color) {
    APRICOT(Color(0xFFE3B080), Color(0xFF925220)),
    JADE(Color(0xFF91C5AD), Color(0xFF286C50)),
    SKY(Color(0xFF93BEE0), Color(0xFF32668E)),
    LILAC(Color(0xFFB9A9DA), Color(0xFF725198)),
    ROSE(Color(0xFFE0A1AC), Color(0xFF995160)),
    GOLD(Color(0xFFD8C080), Color(0xFF896C19)),
    SLATE(Color(0xFFA9BEC5), Color(0xFF4C6872));

    @Composable fun color(): Color = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) dark else light
}

data class LedgerSymbol(val icon: ImageVector, val tone: SymbolTone)

/** 少量 Material 中没有合适形状的分类使用同一 24px 网格绘制。 */
private object DetailGlyphs {
    private fun vector(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(pathData = PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd).build()
    val gold = vector("GoldBars", "M8,3H16L18,9H6ZM9.4,5L8.7,7H15.3L14.6,5ZM3,12H10L12,20H1ZM4.5,14L3.5,18H9.5L8.5,14ZM14,12H21L23,20H12ZM15.5,14L14.5,18H20.5L19.5,14Z")
    val trousers = vector("Trousers", "M5,2H19L21,22H14L12,10L10,22H3ZM7,4L6.8,6H17.2L17,4Z")
    val skirt = vector("Skirt", "M8,2H16L17,6L22,21H2L7,6ZM9,4L8.7,6H15.3L15,4ZM8.8,8L5.5,19H7.5L10,8ZM14,8L16.5,19H18.5L15.2,8Z")
    val shoe = vector("Sneaker", "M3,7H7C8,11 11,12 14,10L16,12L14,14L15.5,15L18,13.5L20,15C22,16 23,17 23,20V22H1V10C1,8 2,7 3,7ZM3,19V20H21V19Z")
    val underwear = vector("Underwear", "M2,4H22L20,13C17,13 15,16 14,21H10C9,16 7,13 4,13ZM4.5,6L5,8H19L19.5,6ZM6,10L7,12C9,13 11,16 12,18C13,16 15,13 17,12L18,10Z")
    val apple = vector("Fruit", "M12,6C11,2 14,1 17,2C17,5 15,6 12,6ZM12,8C17,4 22,8 21,14C20,19 17,23 14,21C13,20 11,20 10,21C7,23 4,19 3,14C2,8 7,4 12,8Z")
    val milk = vector("MilkCarton", "M6,2H16V6L20,10V22H4V10L6,6ZM8,4V6H14V4ZM8,8L6,10H14L12,8ZM6,12V20H14V12ZM16,12V20H18V12Z")
}

private fun symbol(icon: ImageVector, tone: SymbolTone) = LedgerSymbol(icon, tone)

/** 先精确匹配真实分类，避免“家电”被“家”、“话费”被泛化成其他分类。 */
private val categorySymbols: Map<String, LedgerSymbol> = buildMap {
    fun entry(names: String, icon: ImageVector, tone: SymbolTone) {
        names.split('|').forEach { put(it.lowercase(Locale.ROOT), symbol(icon, tone)) }
    }
    entry("收入", Icons.Default.AccountBalanceWallet, SymbolTone.JADE)
    entry("收红包|红包", Icons.Default.Redeem, SymbolTone.ROSE)
    entry("福利", Icons.Default.VolunteerActivism, SymbolTone.GOLD)
    entry("交通", Icons.Default.Commute, SymbolTone.SKY)
    entry("会员|订阅", Icons.Default.CardMembership, SymbolTone.LILAC)
    entry("住房相关费用|住房", Icons.Default.Apartment, SymbolTone.APRICOT)
    entry("其它|其他|未分类", Icons.Default.MoreHoriz, SymbolTone.SLATE)
    entry("医疗|医院|健康", Icons.Default.LocalHospital, SymbolTone.ROSE)
    entry("吃|餐饮|餐饮美食|食品", Icons.Default.Restaurant, SymbolTone.APRICOT)
    entry("娱乐", Icons.Default.Celebration, SymbolTone.LILAC)
    entry("学习|教育", Icons.Default.School, SymbolTone.SKY)
    entry("家电", Icons.Default.Kitchen, SymbolTone.SKY)
    entry("工作|办公", Icons.Default.Work, SymbolTone.SLATE)
    entry("旅行|旅游", Icons.Default.Luggage, SymbolTone.JADE)
    entry("日用品|生活用品", Icons.Default.ShoppingBasket, SymbolTone.JADE)
    entry("植物", Icons.Default.Yard, SymbolTone.JADE)
    entry("汽车/加油|汽车", Icons.Default.DirectionsCar, SymbolTone.SKY)
    entry("烟酒", Icons.Default.Liquor, SymbolTone.APRICOT)
    entry("珠宝首饰|首饰", Icons.Default.Diamond, SymbolTone.LILAC)
    entry("电器数码|数码", Icons.Default.Devices, SymbolTone.SKY)
    entry("电瓶车|电动车", Icons.Default.ElectricMoped, SymbolTone.JADE)
    entry("社交|人情", Icons.Default.Groups, SymbolTone.ROSE)
    entry("结婚|婚礼", Icons.Default.Favorite, SymbolTone.ROSE)
    entry("衣服|服饰", Icons.Default.Checkroom, SymbolTone.LILAC)
    entry("话费网费", Icons.Default.Router, SymbolTone.SKY)
    entry("请客送礼|礼物", Icons.Default.CardGiftcard, SymbolTone.ROSE)
    entry("运动|健身", Icons.Default.FitnessCenter, SymbolTone.JADE)
    entry("黄金", DetailGlyphs.gold, SymbolTone.GOLD)

    entry("工资", Icons.Default.Payments, SymbolTone.JADE)
    entry("二手置换", Icons.Default.CurrencyExchange, SymbolTone.SKY)
    entry("奖金", Icons.Default.EmojiEvents, SymbolTone.GOLD)
    entry("餐补", Icons.Default.FoodBank, SymbolTone.APRICOT)
    entry("其它收益|收益|利息|投资|理财", Icons.AutoMirrored.Filled.TrendingUp, SymbolTone.JADE)
    entry("年终奖", Icons.Default.WorkspacePremium, SymbolTone.GOLD)
    entry("报账|报销", Icons.AutoMirrored.Filled.ReceiptLong, SymbolTone.SKY)
    entry("退税", Icons.Default.PriceCheck, SymbolTone.JADE)
    entry("打车|出租车", Icons.Default.LocalTaxi, SymbolTone.GOLD)
    entry("公交", Icons.Default.DirectionsBus, SymbolTone.SKY)
    entry("地铁", Icons.Default.Subway, SymbolTone.SKY)
    entry("火车|高铁", Icons.Default.Train, SymbolTone.SKY)
    entry("共享单车|自行车", Icons.Default.PedalBike, SymbolTone.JADE)
    entry("iCloud", Icons.Default.Cloud, SymbolTone.SKY)
    entry("小米云", Icons.Default.CloudSync, SymbolTone.APRICOT)
    entry("百度网盘", Icons.Default.CloudDownload, SymbolTone.SKY)
    entry("BiliBili", Icons.Default.SmartDisplay, SymbolTone.ROSE)
    entry("Apple Music", Icons.Default.LibraryMusic, SymbolTone.ROSE)
    entry("网易云音乐", Icons.Default.MusicNote, SymbolTone.LILAC)
    entry("美团", Icons.Default.DeliveryDining, SymbolTone.GOLD)
    entry("起点阅读", Icons.AutoMirrored.Filled.MenuBook, SymbolTone.APRICOT)
    entry("WPS", Icons.AutoMirrored.Filled.Article, SymbolTone.SKY)
    entry("房贷", Icons.Default.RealEstateAgent, SymbolTone.APRICOT)
    entry("燃气", Icons.Default.Whatshot, SymbolTone.APRICOT)
    entry("电费", Icons.Default.Bolt, SymbolTone.GOLD)
    entry("物业费", Icons.Default.Domain, SymbolTone.SLATE)
    entry("装修", Icons.Default.FormatPaint, SymbolTone.APRICOT)
    entry("水电|水费", Icons.Default.WaterDrop, SymbolTone.SKY)
    entry("购物", Icons.Default.ShoppingBag, SymbolTone.LILAC)
    entry("彩票", Icons.Default.ConfirmationNumber, SymbolTone.GOLD)
    entry("快递", Icons.Default.LocalShipping, SymbolTone.APRICOT)
    entry("还款|债务-还款", Icons.Default.CreditScore, SymbolTone.SKY)
    entry("转账", Icons.Default.SwapHoriz, SymbolTone.SKY)
    entry("退款", Icons.AutoMirrored.Filled.Undo, SymbolTone.JADE)
    entry("余额校准", Icons.Default.Tune, SymbolTone.SLATE)
    entry("午餐", Icons.Default.LunchDining, SymbolTone.APRICOT)
    entry("饮料", Icons.Default.LocalBar, SymbolTone.ROSE)
    entry("晚餐", Icons.Default.DinnerDining, SymbolTone.APRICOT)
    entry("外卖", Icons.Default.DeliveryDining, SymbolTone.GOLD)
    entry("买菜", Icons.Default.Eco, SymbolTone.JADE)
    entry("零食", Icons.Default.Cookie, SymbolTone.APRICOT)
    entry("早餐", Icons.Default.BreakfastDining, SymbolTone.GOLD)
    entry("咖啡", Icons.Default.Coffee, SymbolTone.APRICOT)
    entry("水果", DetailGlyphs.apple, SymbolTone.ROSE)
    entry("海鲜", Icons.Default.SetMeal, SymbolTone.SKY)
    entry("矿泉水", Icons.Default.LocalDrink, SymbolTone.SKY)
    entry("充饭卡", Icons.Default.CreditCard, SymbolTone.GOLD)
    entry("肉类", Icons.Default.KebabDining, SymbolTone.APRICOT)
    entry("雪糕", Icons.Default.Icecream, SymbolTone.ROSE)
    entry("火锅", Icons.Default.SoupKitchen, SymbolTone.APRICOT)
    entry("烧烤", Icons.Default.OutdoorGrill, SymbolTone.APRICOT)
    entry("甜品", Icons.Default.Cake, SymbolTone.ROSE)
    entry("奶茶", Icons.Default.LocalCafe, SymbolTone.APRICOT)
    entry("牛奶", DetailGlyphs.milk, SymbolTone.SKY)
    entry("茶", Icons.Default.EmojiFoodBeverage, SymbolTone.JADE)
    entry("鸡蛋", Icons.Default.Egg, SymbolTone.GOLD)
    entry("夜宵", Icons.Default.NightsStay, SymbolTone.LILAC)
    entry("演出", Icons.Default.TheaterComedy, SymbolTone.ROSE)
    entry("电影", Icons.Default.Movie, SymbolTone.LILAC)
    entry("景区门票", Icons.Default.Landscape, SymbolTone.JADE)
    entry("K歌", Icons.Default.Mic, SymbolTone.ROSE)
    entry("书籍", Icons.Default.Book, SymbolTone.APRICOT)
    entry("党费", Icons.Default.Flag, SymbolTone.ROSE)
    entry("酒店", Icons.Default.Hotel, SymbolTone.SKY)
    entry("飞机|机票", Icons.Default.Flight, SymbolTone.SKY)
    entry("日用", Icons.Default.Inventory2, SymbolTone.JADE)
    entry("卫生巾", Icons.Default.Spa, SymbolTone.ROSE)
    entry("居家", Icons.Default.Weekend, SymbolTone.APRICOT)
    entry("理发", Icons.Default.ContentCut, SymbolTone.SKY)
    entry("化妆品", Icons.Default.FaceRetouchingNatural, SymbolTone.ROSE)
    entry("寄存费", Icons.Default.Lock, SymbolTone.SLATE)
    entry("洗洁精", Icons.Default.Soap, SymbolTone.JADE)
    entry("柴米油盐", Icons.Default.RiceBowl, SymbolTone.APRICOT)
    entry("纸巾", Icons.Default.Layers, SymbolTone.SLATE)
    entry("停车费|停车", Icons.Default.LocalParking, SymbolTone.SKY)
    entry("油费|加油", Icons.Default.LocalGasStation, SymbolTone.APRICOT)
    entry("洗车", Icons.Default.LocalCarWash, SymbolTone.SKY)
    entry("车险|保险", Icons.Default.Security, SymbolTone.JADE)
    entry("过路费", Icons.Default.Toll, SymbolTone.SLATE)
    entry("充电", Icons.Default.EvStation, SymbolTone.JADE)
    entry("汽车罚款", Icons.Default.Gavel, SymbolTone.APRICOT)
    entry("维修保养", Icons.Default.CarRepair, SymbolTone.SLATE)
    entry("酒", Icons.Default.WineBar, SymbolTone.ROSE)
    entry("烟", Icons.Default.SmokingRooms, SymbolTone.SLATE)
    entry("手机", Icons.Default.Smartphone, SymbolTone.SKY)
    entry("相机", Icons.Default.PhotoCamera, SymbolTone.SLATE)
    entry("手机配件", Icons.Default.Headphones, SymbolTone.LILAC)
    entry("外套", Icons.Default.DryCleaning, SymbolTone.LILAC)
    entry("内衣|文胸", DetailGlyphs.underwear, SymbolTone.ROSE)
    entry("饰品", Icons.Default.AutoAwesome, SymbolTone.GOLD)
    entry("球鞋", DetailGlyphs.shoe, SymbolTone.SKY)
    entry("裙子", DetailGlyphs.skirt, SymbolTone.ROSE)
    entry("裤子", DetailGlyphs.trousers, SymbolTone.SKY)
    entry("话费", Icons.Default.SimCard, SymbolTone.SKY)
    entry("网费", Icons.Default.Wifi, SymbolTone.SKY)
    entry("鲜花", Icons.Default.LocalFlorist, SymbolTone.ROSE)
    entry("礼金", Icons.Default.Mail, SymbolTone.ROSE)
    entry("宠物|猫|狗", Icons.Default.Pets, SymbolTone.APRICOT)
}

fun categorySymbol(name: String, parent: String? = null): LedgerSymbol {
    val key = name.trim().lowercase(Locale.ROOT)
    categorySymbols[key]?.let { return it }
    val related = listOf("信用卡" to "还款", "咖啡" to "咖啡", "奶茶" to "奶茶", "外卖" to "外卖",
        "话费" to "话费", "网费" to "网费", "电瓶车" to "电瓶车", "婚" to "结婚", "黄金" to "黄金",
        "餐" to "餐饮", "医" to "医疗", "药" to "医疗", "宠物" to "宠物", "运动" to "运动",
        "衣" to "衣服", "鞋" to "球鞋", "房" to "住房", "旅游" to "旅行", "工资" to "工资", "收益" to "收益")
    related.firstOrNull { key.contains(it.first) }?.let { return categorySymbols.getValue(it.second) }
    parent?.let { categorySymbols[it.trim().lowercase(Locale.ROOT)] }?.let { return it }
    return categorySymbols.getValue("其它")
}

fun categoryIcon(name: String): ImageVector = categorySymbol(name).icon

fun assetGroupSymbol(group: String): LedgerSymbol = when (group) {
    "信用卡" -> symbol(Icons.Default.CreditCard, SymbolTone.LILAC)
    "银行卡" -> symbol(Icons.Default.AccountBalance, SymbolTone.SKY)
    "支付宝" -> symbol(Icons.Default.AccountBalanceWallet, SymbolTone.SKY)
    "微信" -> symbol(Icons.Default.Forum, SymbolTone.JADE)
    "充值卡" -> symbol(Icons.Default.Redeem, SymbolTone.APRICOT)
    "现金" -> symbol(Icons.Default.Payments, SymbolTone.GOLD)
    "投资理财" -> symbol(Icons.AutoMirrored.Filled.TrendingUp, SymbolTone.JADE)
    "应收款" -> symbol(Icons.AutoMirrored.Filled.CallReceived, SymbolTone.JADE)
    "应付款" -> symbol(Icons.AutoMirrored.Filled.CallMade, SymbolTone.ROSE)
    else -> symbol(Icons.Default.Folder, SymbolTone.SLATE)
}

fun assetSymbol(asset: AssetEntity): LedgerSymbol = when {
    asset.name.contains("小荷包") -> symbol(Icons.Default.Savings, SymbolTone.GOLD)
    asset.name.contains("余额宝") -> symbol(Icons.AutoMirrored.Filled.TrendingUp, SymbolTone.JADE)
    else -> assetGroupSymbol(AssetGroups.of(asset))
}

@Composable
fun SymbolBadge(symbol: LedgerSymbol, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val tint = symbol.tone.color()
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center) {
        Icon(symbol.icon, contentDescription = null, modifier = Modifier.size(size * 0.55f), tint = tint)
    }
}
