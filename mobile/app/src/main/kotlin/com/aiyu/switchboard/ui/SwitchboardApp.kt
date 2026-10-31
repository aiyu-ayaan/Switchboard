package com.aiyu.switchboard.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aiyu.switchboard.AppViewModel
import com.aiyu.switchboard.data.parsePairUri
import com.aiyu.switchboard.ui.home.HomeScreen
import com.aiyu.switchboard.ui.pair.AddScreen
import com.aiyu.switchboard.ui.pair.CodeScreen
import com.aiyu.switchboard.ui.pair.PairingScreen
import com.aiyu.switchboard.ui.pair.ScanScreen
import com.aiyu.switchboard.ui.settings.SettingsScreen
import com.aiyu.switchboard.ui.theme.SwitchboardTheme
import com.aiyu.switchboard.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow

object Routes {
    const val HOME = "home"
    const val ADD = "add"
    const val SCAN = "scan"
    const val CODE = "code"
    const val PAIRING = "pairing"
    const val SETTINGS = "settings"
}

@Composable
fun SwitchboardApp(vm: AppViewModel, deepLink: MutableStateFlow<String?>) {
    val data by vm.vault.data.collectAsStateWithLifecycle()
    val mode = when (data.theme) {
        "light" -> ThemeMode.Light
        "dark" -> ThemeMode.Dark
        else -> ThemeMode.System
    }
    SwitchboardTheme(mode = mode, dynamicColor = data.dynamicColor) {
        val nav = rememberNavController()
        val link by deepLink.collectAsStateWithLifecycle()
        LaunchedEffect(link) {
            val offer = link?.let(::parsePairUri) ?: return@LaunchedEffect
            deepLink.value = null
            vm.pair(offer.target, offer.secret)
            nav.navigate(Routes.PAIRING) { launchSingleTop = true }
        }
        // Forward screens slide in from the right a little; back reverses.
        val slide = AnimatedContentTransitionScope.SlideDirection.Start
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            enterTransition = { slideIntoContainer(slide, tween(400)) { it / 6 } + fadeIn(tween(300)) },
            exitTransition = { fadeOut(tween(150)) },
            popEnterTransition = { fadeIn(tween(300)) },
            popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(250)) { it / 6 } + fadeOut(tween(200)) },
        ) {
            composable(Routes.HOME) {
                HomeScreen(vm, onAdd = { nav.navigate(Routes.ADD) }, onScan = { nav.navigate(Routes.SCAN) }, onCode = { nav.navigate(Routes.CODE) }, onSettings = { nav.navigate(Routes.SETTINGS) })
            }
            composable(Routes.ADD) {
                AddScreen(vm, onBack = { nav.popBackStack() }, onScan = { nav.navigate(Routes.SCAN) }, onCode = { nav.navigate(Routes.CODE) }, onPairing = { nav.navigate(Routes.PAIRING) })
            }
            composable(Routes.SCAN) {
                ScanScreen(
                    onBack = { nav.popBackStack() },
                    onCode = { nav.navigate(Routes.CODE) { popUpTo(Routes.SCAN) { inclusive = true } } },
                    onFound = { offer ->
                        vm.pair(offer.target, offer.secret)
                        nav.navigate(Routes.PAIRING) { popUpTo(Routes.SCAN) { inclusive = true } }
                    },
                )
            }
            composable(Routes.CODE) {
                CodeScreen(vm, onBack = { nav.popBackStack() }, onPairing = { nav.navigate(Routes.PAIRING) { popUpTo(Routes.CODE) { inclusive = true } } })
            }
            composable(Routes.PAIRING) {
                PairingScreen(vm, onDone = {
                    vm.clearPairing()
                    nav.popBackStack(Routes.HOME, inclusive = false)
                }, onRetry = {
                    vm.clearPairing()
                    nav.popBackStack()
                })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(vm, onBack = { nav.popBackStack() })
            }
        }
    }
}
