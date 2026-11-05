package com.aiyu.switchboard.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import com.aiyu.switchboard.ui.home.SystemsScreen
import com.aiyu.switchboard.ui.system.HostScreen
import com.aiyu.switchboard.ui.system.Section
import com.aiyu.switchboard.ui.system.SectionScreen
import com.aiyu.switchboard.ui.pair.AddScreen
import com.aiyu.switchboard.ui.pair.CodeScreen
import com.aiyu.switchboard.ui.pair.PairingScreen
import com.aiyu.switchboard.ui.pair.ScanScreen
import com.aiyu.switchboard.ui.settings.SettingsScreen
import com.aiyu.switchboard.ui.settings.UpdatePrompt
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
    const val SYSTEM = "system/{id}"
    fun system(id: String) = "system/$id"
    const val SECTION = "system/{id}/{section}"
    fun section(id: String, s: Section) = "system/$id/${s.name}"
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
        // M3 shared axis X: forward slides in from the end while the old screen
        // slides away and fades through; back reverses it, and the leaving
        // screen shrinks so a predictive back gesture previews what is behind.
        val emph = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
        val accel = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            enterTransition = { slideInHorizontally(tween(450, easing = emph)) { it / 3 } + fadeIn(tween(250, 90)) },
            exitTransition = { slideOutHorizontally(tween(450, easing = emph)) { -it / 5 } + fadeOut(tween(120)) },
            popEnterTransition = { slideInHorizontally(tween(400, easing = emph)) { -it / 5 } + fadeIn(tween(250, 60)) + scaleIn(tween(400, easing = emph), 0.96f) },
            popExitTransition = { scaleOut(tween(300, easing = accel), 0.88f) + slideOutHorizontally(tween(300, easing = accel)) { it / 4 } + fadeOut(tween(200, 100)) },
        ) {
            composable(Routes.HOME) {
                SystemsScreen(
                    vm,
                    onOpen = { nav.navigate(Routes.system(it)) },
                    onSection = { id, sec -> nav.navigate(Routes.section(id, sec)) },
                    onScan = { nav.navigate(Routes.SCAN) },
                    onCode = { nav.navigate(Routes.CODE) },
                    onAdd = { nav.navigate(Routes.ADD) },
                    onSettings = { nav.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.SYSTEM) { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                HostScreen(
                    vm,
                    id,
                    onSwitch = { nav.popBackStack(Routes.HOME, inclusive = false) },
                    onOpen = { nav.navigate(Routes.section(id, it)) },
                    onSettings = { nav.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.SECTION) { entry ->
                val section = runCatching { Section.valueOf(entry.arguments?.getString("section").orEmpty()) }.getOrDefault(Section.Audio)
                SectionScreen(vm, entry.arguments?.getString("id").orEmpty(), section, onBack = { nav.popBackStack() })
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
        UpdatePrompt(vm.vault)
    }
}
