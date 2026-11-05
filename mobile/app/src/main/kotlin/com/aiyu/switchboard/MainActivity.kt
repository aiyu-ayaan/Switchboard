package com.aiyu.switchboard

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.PathInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.animation.doOnEnd
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.aiyu.switchboard.data.parsePairUri
import com.aiyu.switchboard.ui.SwitchboardApp
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    /** A `switchboard://pair` link opened from the camera app or a browser. */
    val deepLink = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // The mark grows and fades as the splash dissolves into the app, once the connection animation
        // (drawable-v31/splash_icon) has played out; the splash stays up until remove().
        splash.setOnExitAnimationListener { provider ->
            val icon: View = provider.iconView
            val ease = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
            // Wall-clock (epoch) millis, not uptime; 0 before Android 12, so there is nothing to wait for.
            val iconEnd = provider.iconAnimationStartMillis + provider.iconAnimationDurationMillis
            AnimatorSet().apply {
                startDelay = (iconEnd - System.currentTimeMillis()).coerceAtLeast(0)
                playTogether(
                    ObjectAnimator.ofFloat(icon, View.SCALE_X, 1f, 1.6f),
                    ObjectAnimator.ofFloat(icon, View.SCALE_Y, 1f, 1.6f),
                    ObjectAnimator.ofFloat(provider.view, View.ALPHA, 1f, 0f),
                )
                duration = 320
                interpolator = ease
                doOnEnd { provider.remove() }
                start()
            }
        }
        enableEdgeToEdge()
        // Incoming-file and transfer notifications need this on Android 13+; ask once, up front.
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // A restored or recents-launched activity gets the pair link it was opened with
        // again; its token is spent, so pairing again would only fail.
        if (savedInstanceState == null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) handle(intent)
        setContent { SwitchboardApp(vm, deepLink) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val data = intent?.dataString ?: return
        if (parsePairUri(data) != null) deepLink.value = data
    }
}
