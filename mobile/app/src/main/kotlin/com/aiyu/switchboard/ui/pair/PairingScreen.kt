package com.aiyu.switchboard.ui.pair

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiyu.switchboard.AppViewModel
import com.aiyu.switchboard.data.fingerprint
import com.aiyu.switchboard.net.PairState
import com.aiyu.switchboard.ui.components.MorphShapeIcon

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PairingScreen(vm: AppViewModel, onDone: () -> Unit, onRetry: () -> Unit) {
    val pairing by vm.pairing.collectAsStateWithLifecycle()
    val p = pairing ?: return LaunchedEffect(Unit) { onDone() }
    val state by p.state.collectAsState()
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(state) {
        when (state) {
            is PairState.Done -> haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            is PairState.Failed -> haptic.performHapticFeedback(HapticFeedbackType.Reject)
            else -> {}
        }
    }

    Surface(Modifier.fillMaxSize(), color = cs.surface) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        AnimatedContent(state, transitionSpec = { (scaleIn(spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow), 0.6f) + fadeIn()) togetherWith fadeOut() }, contentKey = { it::class }, label = "pair") { s ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when (s) {
                    PairState.Connecting -> {
                        ContainedLoadingIndicator(Modifier.size(140.dp))
                        Spacer(Modifier.height(32.dp))
                        Text("Pairing with ${p.target.name}", style = MaterialTheme.typography.headlineMediumEmphasized, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        Surface(shape = RoundedCornerShape(20.dp), color = cs.surfaceContainerHigh) {
                            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Check the computer shows this fingerprint", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                                Text(fingerprint(p.target.pk), style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace, color = cs.primary, modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                    is PairState.Done -> {
                        MorphShapeIcon(Icons.Rounded.Check, MaterialShapes.Circle, MaterialShapes.Sunny, true, cs.primary, cs.onPrimary, size = 160.dp, iconSize = 72.dp)
                        Spacer(Modifier.height(32.dp))
                        Text("Paired with ${s.host.name}", style = MaterialTheme.typography.displaySmallEmphasized, textAlign = TextAlign.Center)
                        Text("It reconnects by itself whenever you're on the same network.", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                        Spacer(Modifier.height(32.dp))
                        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text("Start controlling", style = MaterialTheme.typography.titleMedium) }
                    }
                    is PairState.Failed -> {
                        MorphShapeIcon(Icons.Rounded.Close, MaterialShapes.Circle, MaterialShapes.Cookie6Sided, true, cs.errorContainer, cs.onErrorContainer, size = 140.dp, iconSize = 64.dp)
                        Spacer(Modifier.height(32.dp))
                        Text("Couldn't pair", style = MaterialTheme.typography.displaySmallEmphasized)
                        Text(s.message, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                        Spacer(Modifier.height(32.dp))
                        FilledTonalButton(onClick = onRetry, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Try again") }
                        TextButton(onClick = onDone) { Text("Cancel") }
                    }
                }
            }
        }
    }
    }
}
