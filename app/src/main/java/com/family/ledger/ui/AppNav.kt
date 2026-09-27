package com.family.ledger.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.family.ledger.AppContainer
import com.family.ledger.ui.add.AddBillScreen
import com.family.ledger.ui.assets.AssetManageScreen
import com.family.ledger.ui.family.FamilyScreen
import com.family.ledger.ui.home.HomeScreen
import com.family.ledger.ui.list.BillListScreen
import com.family.ledger.ui.onboarding.IdentityScreen
import com.family.ledger.ui.settings.SettingsScreen
import com.family.ledger.ui.stats.StatsScreen

/** 全部路由。单 Activity + Navigation Compose，不新增 Activity。 */
object Routes {
    const val HOME = "home"
    const val ADD = "add"
    const val LIST = "list"
    const val STATS = "stats"
    const val ASSETS = "assets"
    const val FAMILY = "family"
    const val SETTINGS = "settings"
}

@Composable
fun AppNav(container: AppContainer) {
    // 首次启动先问「你是哪位？」—— 没有登录、没有密码、没有家庭码；
    // 选一次名字就进主界面，之后两台手机自动认同一本账。
    var identityChosen by remember { mutableStateOf(container.settings.identityChosen &&
        (container.settings.myMemberId in com.family.ledger.core.FixedPeople.names ||
            com.family.ledger.core.FixedPeople.idForName(container.settings.myDisplayName) != null)) }
    if (!identityChosen) {
        IdentityScreen(container = container, onChosen = { identityChosen = true })
        return
    }

    var ready by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(identityChosen) {
        runCatching { container.family.ensureBootstrap() }.onSuccess { ready = true }
            .onFailure { error = it.message }
    }
    if (!ready) { Text(error ?: "正在打开家庭账本…"); return }

    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = Routes.HOME) {

        composable(Routes.HOME) {
            HomeScreen(
                container = container,
                onAddBill = { nav.navigate(Routes.ADD) { launchSingleTop = true } },
                onOpenList = { nav.navigate(Routes.LIST) { launchSingleTop = true } },
                onOpenStats = { nav.navigate(Routes.STATS) { launchSingleTop = true } },
                onOpenAssets = { nav.navigate(Routes.ASSETS) { launchSingleTop = true } },
                onOpenFamily = { nav.navigate(Routes.FAMILY) { launchSingleTop = true } },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } },
            )
        }

        composable(Routes.ADD) {
            AddBillScreen(
                container = container,
                onDone = { nav.popBackStack(Routes.HOME, inclusive = false) },
                onCancel = { nav.popBackStack(Routes.HOME, inclusive = false) },
            )
        }

        composable(Routes.LIST) {
            BillListScreen(container = container, onBack = { nav.popBackStack() })
        }

        composable(Routes.STATS) {
            StatsScreen(container = container, onBack = { nav.popBackStack() })
        }

        composable(Routes.ASSETS) {
            AssetManageScreen(container = container, onBack = { nav.popBackStack() })
        }

        composable(Routes.FAMILY) {
            FamilyScreen(container = container, onBack = { nav.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(container = container, onBack = { nav.popBackStack() }, onDebug = { nav.navigate("auto_debug") })
        }
        composable("auto_debug") { com.family.ledger.ui.settings.AutoDebugScreen(container) { nav.popBackStack() } }
    }
}
