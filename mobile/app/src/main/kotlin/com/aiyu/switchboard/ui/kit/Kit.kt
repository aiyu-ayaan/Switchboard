package com.aiyu.switchboard.ui.kit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import com.aiyu.switchboard.ui.components.ShapeIcon

/** Expressive press: the element sinks on the theme's fast spatial spring and springs back. */
fun Modifier.bouncyClickable(enabled: Boolean = true, haptic: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "press")
    val h = LocalHapticFeedback.current
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(source, indication = androidx.compose.material3.ripple(), enabled = enabled) {
            if (haptic) h.performHapticFeedback(HapticFeedbackType.ContextClick)
            onClick()
        }
}

/** The card every pod sits in: 24 dp, tonal container, hairline outline. */
@Composable
fun SectionCard(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.surfaceContainer, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = color),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) { Column(Modifier.padding(padding), content = content) }
}

/** Pod header: shape badge, title and subtitle, and an optional "Action ›" link. */
@Composable
fun PodHeader(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    shape: RoundedPolygon = androidx.compose.material3.MaterialShapes.Cookie9Sided,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        ShapeIcon(icon, shape, cs.primaryContainer, cs.onPrimaryContainer, size = 36.dp, iconSize = 18.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
        if (action != null && onAction != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.bouncyClickable(onClick = onAction).padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
            ) {
                Text(action, style = MaterialTheme.typography.labelMedium, color = cs.primary, fontWeight = FontWeight.SemiBold)
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Open $action", tint = cs.primary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Small monospace readout chip, e.g. `50%` or `DDC/CI`. */
@Composable
fun MonoBadge(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.surfaceContainerHigh, content: Color = MaterialTheme.colorScheme.onSurface) {
    Surface(shape = RoundedCornerShape(8.dp), color = color, modifier = modifier) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = content,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
        )
    }
}

@Composable
fun MonoCaption(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = color, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Round icon button used beside sliders (mute, sun). */
@Composable
fun RoundIcon(icon: ImageVector, description: String?, modifier: Modifier = Modifier, container: Color = MaterialTheme.colorScheme.surfaceContainerHighest, tint: Color = MaterialTheme.colorScheme.primary, size: Dp = 36.dp, onClick: (() -> Unit)? = null) {
    Surface(shape = CircleShape, color = container, modifier = modifier.size(size).let { if (onClick != null) it.bouncyClickable(onClick = onClick) else it }) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, description, tint = tint, modifier = Modifier.size(size * 0.5f)) }
    }
}

/**
 * Slider for a live hardware value with icon and monospace badge. Sends at
 * most every 120 ms while dragged and once on release, and ignores pushed
 * values for 2 s after a touch so a stale reading cannot pull the handle back.
 */
@Composable
fun LevelRow(
    value: Int?,
    label: String,
    onSend: (Int) -> Unit,
    icon: ImageVector? = null,
    onIcon: (() -> Unit)? = null,
    iconContainer: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    badge: String? = null,
    enabled: Boolean = true,
) {
    var local by remember { mutableFloatStateOf((value ?: 0).toFloat()) }
    var touched by remember { mutableLongStateOf(0L) }
    var sent by remember { mutableLongStateOf(0L) }
    LaunchedEffect(value) {
        if (value != null && System.currentTimeMillis() - touched > 2000) local = value.toFloat()
    }
    val haptic = LocalHapticFeedback.current
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (icon != null) RoundIcon(icon, if (onIcon != null) label else null, container = iconContainer, tint = iconTint, onClick = onIcon)
        Slider(
            value = local,
            onValueChange = {
                val now = System.currentTimeMillis()
                if (it.toInt() / 10 != local.toInt() / 10) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                local = it
                touched = now
                if (now - sent > 120) {
                    sent = now
                    onSend(it.toInt())
                }
            },
            onValueChangeFinished = {
                touched = System.currentTimeMillis()
                if (local.toInt() == 0 || local.toInt() == 100) haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onSend(local.toInt())
            },
            valueRange = 0f..100f,
            enabled = enabled && value != null,
            colors = SliderDefaults.colors(thumbColor = cs.primary, activeTrackColor = cs.primary, inactiveTrackColor = cs.surfaceContainerHighest),
            modifier = Modifier.weight(1f).semantics { contentDescription = label },
        )
        MonoBadge(badge ?: if (value == null) "—" else "${local.toInt()}%", Modifier.width(52.dp))
    }
}

@Composable
fun Gap(h: Dp = 12.dp) = Spacer(Modifier.height(h))
