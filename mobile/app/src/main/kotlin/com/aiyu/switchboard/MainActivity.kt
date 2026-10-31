package com.aiyu.switchboard

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
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
        // The mark grows and fades as the splash dissolves into the app.
        splash.setOnExitAnimationListener { provider ->
            val icon: View = provider.iconView
            val ease = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
            AnimatorSet().apply {
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
        handle(intent)
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
