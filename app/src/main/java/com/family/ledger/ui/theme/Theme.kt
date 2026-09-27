package com.family.ledger.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ---------- 品牌色 ----------

/** 主色：乖乖记账绿。 */
val LedgerGreen = Color(0xFF2E7D6B)
val LedgerGreenDeep = Color(0xFF1F5C4E)
val LedgerGreenSoft = Color(0xFF85B8AA)

/** 家庭共享资产标识色（暖橙）—— 全 App 统一用它标记「共享」。 */
val SharedOrange = Color(0xFFD98324)
val SharedOrangeContainerLight = Color(0xFFFFF2E0)
val SharedOrangeContainerDark = Color(0xFF46331C)

/** 个人资产标识色（蓝）。 */
val PersonalBlue = Color(0xFF3A6EA5)
val PersonalBlueContainerLight = Color(0xFFEAF1F9)
val PersonalBlueContainerDark = Color(0xFF22303F)

/** 金额语义色。 */
val ExpenseRed = Color(0xFFC0392B)
val IncomeGreen = Color(0xFF2E7D6B)
val MutedGray = Color(0xFF787F8A)

private val LightColors = lightColorScheme(
    primary = LedgerGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3EDE6),
    onPrimaryContainer = LedgerGreenDeep,
    secondary = PersonalBlue,
    onSecondary = Color.White,
    secondaryContainer = PersonalBlueContainerLight,
    onSecondaryContainer = Color(0xFF14304C),
    tertiary = SharedOrange,
    onTertiary = Color.White,
    tertiaryContainer = SharedOrangeContainerLight,
    onTertiaryContainer = Color(0xFF5A3A0C),
    background = Color(0xFFF5F7F6),
    onBackground = Color(0xFF1B1F1E),
    surface = Color.White,
    onSurface = Color(0xFF1B1F1E),
    surfaceVariant = Color(0xFFEDF1EF),
    onSurfaceVariant = Color(0xFF4A5450),
    outlineVariant = Color(0xFFDCE3E0),
    error = ExpenseRed,
)

private val DarkColors = darkColorScheme(
    primary = LedgerGreenSoft,
    onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF23453C),
    onPrimaryContainer = Color(0xFFD1DDD7),
    secondary = Color(0xFF9CC3EA),
    onSecondary = Color(0xFF0E2739),
    secondaryContainer = PersonalBlueContainerDark,
    onSecondaryContainer = Color(0xFFD6E5F5),
    tertiary = Color(0xFFF0B478),
    onTertiary = Color(0xFF43290A),
    tertiaryContainer = SharedOrangeContainerDark,
    onTertiaryContainer = Color(0xFFFFE2BA),
    background = Color(0xFF171B1A),
    onBackground = Color(0xFFDCE2DE),
    surface = Color(0xFF202523),
    surfaceDim = Color(0xFF171B1A),
    surfaceBright = Color(0xFF343D38),
    surfaceContainerLowest = Color(0xFF141816),
    surfaceContainerLow = Color(0xFF1C211E),
    surfaceContainer = Color(0xFF232A26),
    surfaceContainerHigh = Color(0xFF29312C),
    surfaceContainerHighest = Color(0xFF303A33),
    onSurface = Color(0xFFDCE2DE),
    surfaceVariant = Color(0xFF262C2A),
    onSurfaceVariant = Color(0xFFBFC9C4),
    outlineVariant = Color(0xFF39423F),
    error = Color(0xFFF08A7C),
)

/** 语义化的业务强调色，随明暗主题切换。 */
@Immutable
data class LedgerAccents(
    /** 家庭共享资产的强调色。 */
    val family: Color,
    val familyContainer: Color,
    /** 个人资产的强调色。 */
    val personal: Color,
    val personalContainer: Color,
    val expense: Color,
    val income: Color,
    val transfer: Color,
)

private val LightAccents = LedgerAccents(
    family = SharedOrange,
    familyContainer = SharedOrangeContainerLight,
    personal = PersonalBlue,
    personalContainer = PersonalBlueContainerLight,
    expense = ExpenseRed,
    income = IncomeGreen,
    transfer = MutedGray,
)

private val DarkAccents = LedgerAccents(
    family = Color(0xFFF0B478),
    familyContainer = SharedOrangeContainerDark,
    personal = Color(0xFF9CC3EA),
    personalContainer = PersonalBlueContainerDark,
    expense = Color(0xFFF08A7C),
    income = Color(0xFF87BFAA),
    transfer = Color(0xFFA5ADB6),
)

private val LocalLedgerAccents = staticCompositionLocalOf { LightAccents }

/** 便捷读取入口：`LedgerTheme.accents.family`。 */
object LedgerTheme {
    val accents: LedgerAccents
        @Composable @ReadOnlyComposable get() = LocalLedgerAccents.current
}

/** 金额展示样式：等宽视觉、字号大，钱迹风格。 */
val AmountHuge: TextStyle = TextStyle(
    fontSize = 44.sp,
    fontWeight = FontWeight.SemiBold,
    letterSpacing = 0.5.sp,
)
val AmountLarge: TextStyle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
val AmountMedium: TextStyle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium)
val AmountSmall: TextStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium)

private val LedgerTypography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun FamilyLedgerTheme(
    darkTheme: Boolean = ThemePrefs.resolveDark(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkColors else LightColors
    val accents = if (darkTheme) DarkAccents else LightAccents
    CompositionLocalProvider(LocalLedgerAccents provides accents) {
        MaterialTheme(colorScheme = scheme, typography = LedgerTypography, content = content)
    }
}
