package com.aiyu.switchboard.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath

/** Icon on an M3 Expressive shape (e.g. `MaterialShapes.Cookie9Sided`), as in Findex. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ShapeIcon(
    icon: ImageVector?,
    shape: RoundedPolygon,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    iconSize: Dp = 24.dp,
    spin: Boolean = false,
) {
    val angle = if (spin) {
        val t = rememberInfiniteTransition(label = "spin")
        t.animateFloat(0f, 360f, infiniteRepeatable(tween(16_000, easing = LinearEasing)), label = "angle").value
    } else 0f
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size).rotate(angle).clip(shape.toShape()).background(container))
        if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(iconSize))
    }
}

/** A shape that morphs between [from] and [to] on the theme's expressive spring. */
@Composable
fun MorphShapeIcon(
    icon: ImageVector?,
    from: RoundedPolygon,
    to: RoundedPolygon,
    morphed: Boolean,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    iconSize: Dp = 24.dp,
) {
    val morph = remember(from, to) { Morph(from, to) }
    val p by animateFloatAsState(if (morphed) 1f else 0f, MaterialTheme.motionScheme.slowSpatialSpec(), label = "morph")
    Box(modifier.size(size).clip(MorphShape(morph, p)).background(container), contentAlignment = Alignment.Center) {
        if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(iconSize))
    }
}

/** Loops a morph forever: the "alive" state for empty and waiting screens. */
@Composable
fun BreathingShape(from: RoundedPolygon, to: RoundedPolygon, container: Color, modifier: Modifier = Modifier, size: Dp = 160.dp, content: @Composable () -> Unit = {}) {
    val morph = remember(from, to) { Morph(from, to) }
    val t = rememberInfiniteTransition(label = "breathe")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600), RepeatMode.Reverse), label = "p")
    val r by t.animateFloat(0f, 90f, infiniteRepeatable(tween(5200, easing = LinearEasing), RepeatMode.Reverse), label = "r")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size).rotate(r).clip(MorphShape(morph, p)).background(container))
        content()
    }
}

class MorphShape(private val morph: Morph, private val progress: Float) : Shape {
    private val matrix = Matrix()
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        matrix.reset()
        matrix.scale(size.width, size.height)
        val path = morph.toPath(progress).asComposePath()
        path.transform(matrix)
        return Outline.Generic(path)
    }
}
