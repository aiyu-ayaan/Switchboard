package com.switchboard.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.switchboard.app.net.ButtonAction
import com.switchboard.app.net.MouseButton
import com.switchboard.app.net.RemoteKey
import com.switchboard.app.net.ScreenSettings
import com.switchboard.app.screen.RemoteScreen
import com.switchboard.app.screen.Viewport
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** How touches on the picture drive the desktop. */
private enum class TouchMode { Direct, Trackpad }

/** Stream presets, named for what the user notices rather than the numbers. */
private enum class Quality(val label: String, val scale: Float, val jpeg: Int, val fps: Int) {
    Sharp("Sharp text", 1.35f, 82, 15),
    Balanced("Balanced", 1f, 70, 20),
    Fast("Smooth motion", 0.7f, 55, 30)
}

/** Pixels of finger travel per wheel notch for a two-finger scroll. */
private const val SCROLL_PX_PER_NOTCH = 48f

/** Long-press is a right click, as on every touch-first desktop. */
private const val LONG_PRESS_MS = 450L

/** Trackpad gain: desktop pixels per finger pixel at no zoom. */
private const val TRACKPAD_GAIN = 1.6f

/**
 * Prefixed to the hidden text field so Backspace on an "empty" field still
 * changes something the field can report.
 */
private const val SENTINEL = "​​​​"

/**
 * The desktop's screen, live, with the phone as its touchscreen.
 *
 * Direct mode is a touchscreen: a tap clicks where the finger is, a drag drags
 * there, a long press right-clicks. Trackpad mode moves the desktop's own
 * pointer relatively, for the precise work a fingertip is too broad for. In
 * both, two fingers scroll the desktop, and pinching zooms the picture on the
 * phone -- once zoomed, two fingers pan the picture instead, because a phone
 * looking at a 4K desktop needs to get close to read it.
 */
@Composable
fun RemoteScreenScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val remote = remember { RemoteScreen.get() }
    val picture by remote.picture.collectAsState()
    val status by remote.status.collectAsState()
    val cursor by remote.cursor.collectAsState()
    val haptics = LocalHaptics.current
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current

    var mode by remember { mutableStateOf(TouchMode.Direct) }
    var quality by remember { mutableStateOf(Quality.Balanced) }
    var viewport by remember { mutableStateOf(Viewport()) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var chromeVisible by remember { mutableStateOf(true) }
    var keyboardOpen by remember { mutableStateOf(false) }
    var qualityMenu by remember { mutableStateOf(false) }
    var landscapeLocked by remember { mutableStateOf(false) }
    var displayMenu by remember { mutableStateOf(false) }
    // Empty asks for the primary monitor; the desktop answers with what it
    // actually chose, which is what the menu marks.
    var wantedDisplay by remember { mutableStateOf("") }
    val displays by remote.displays.collectAsState()
    val shownDisplay by remote.display.collectAsState()

    // The phone's long edge in pixels: the desktop is asked for roughly what
    // this screen can actually show, not its own full resolution.
    val longEdgePx = with(density) {
        max(configuration.screenWidthDp, configuration.screenHeightDp).dp.roundToPx()
    }

    fun settings() = ScreenSettings(
        maxWidth = (longEdgePx * quality.scale).toInt().coerceIn(640, 2560),
        quality = quality.jpeg,
        fps = quality.fps,
        // "All" is the one choice worth more pixels than the phone has: three
        // monitors side by side need zooming into, and zooming needs detail.
        display = wantedDisplay
    ).let { if (wantedDisplay == ScreenSettings.ALL_DISPLAYS) it.copy(maxWidth = 3200) else it }

    LaunchedEffect(quality, longEdgePx, wantedDisplay) {
        viewport = Viewport()
        remote.start(settings())
    }

    // Immersive while looking at the desktop: the status and navigation bars
    // would otherwise cover its taskbar and menu bar, which are the parts of a
    // desktop people reach for most.
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            remote.stop()
        }
    }

    BackHandler { onBack() }

    val current by rememberUpdatedState(picture)
    val currentMode by rememberUpdatedState(mode)

    Box(modifier.fillMaxSize().background(Color.Black)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged {
                    viewSize = it
                    val pic = current
                    if (pic != null) {
                        viewport = viewport.clamped(
                            it.width.toFloat(), it.height.toFloat(),
                            pic.image.width.toFloat(), pic.image.height.toFloat()
                        )
                    }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val pic = current ?: return@awaitEachGesture
                        val vw = size.width.toFloat()
                        val vh = size.height.toFloat()
                        val iw = pic.image.width.toFloat()
                        val ih = pic.image.height.toFloat()
                        val slop = viewConfiguration.touchSlop

                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downAt = System.currentTimeMillis()
                        val start = down.position
                        var last = start
                        var moved = false
                        var dragging = false
                        var longPressed = false
                        var multi = false
                        var multiMode = 0 // 0 undecided, 1 zoom/pan picture, 2 scroll desktop
                        var spanStart = 0f
                        var lastSpan = 0f
                        var lastCentroid = Offset.Zero
                        var travel = Offset.Zero
                        var lastPointSent = 0L

                        fun sendPoint(p: Offset, force: Boolean = false) {
                            val now = System.currentTimeMillis()
                            if (!force && now - lastPointSent < 12) return
                            lastPointSent = now
                            val (u, v) = viewport.toImageClamped(p.x, p.y, vw, vh, iw, ih)
                            remote.pointTo(u, v)
                        }

                        while (true) {
                            val event: PointerEvent? = if (!moved && !multi && !longPressed) {
                                val remaining = LONG_PRESS_MS - (System.currentTimeMillis() - downAt)
                                withTimeoutOrNull(max(1L, remaining)) { awaitPointerEvent() }
                            } else {
                                awaitPointerEvent()
                            }

                            if (event == null) {
                                // Held still: a right click where the finger is.
                                longPressed = true
                                haptics.longPress()
                                if (currentMode == TouchMode.Direct) sendPoint(start, force = true)
                                remote.click(MouseButton.RIGHT)
                                continue
                            }

                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) {
                                event.changes.forEach { it.consume() }
                                break
                            }

                            if (pressed.size >= 2) {
                                val a = pressed[0].position
                                val b = pressed[1].position
                                val centroid = (a + b) / 2f
                                val span = hypot(a.x - b.x, a.y - b.y)
                                if (!multi) {
                                    multi = true
                                    if (dragging) {
                                        remote.button(MouseButton.LEFT, ButtonAction.UP)
                                        dragging = false
                                    }
                                    spanStart = span
                                    lastSpan = span
                                    lastCentroid = centroid
                                    travel = Offset.Zero
                                } else {
                                    val pan = centroid - lastCentroid
                                    travel += pan
                                    if (multiMode == 0) {
                                        multiMode = when {
                                            abs(span - spanStart) > slop * 2 -> 1
                                            viewport.isZoomed && travel.getDistance() > slop -> 1
                                            travel.getDistance() > slop -> 2
                                            else -> 0
                                        }
                                    }
                                    when (multiMode) {
                                        1 -> {
                                            val factor = if (lastSpan > 0f) span / lastSpan else 1f
                                            viewport = viewport
                                                .zoomedBy(factor, centroid.x, centroid.y, vw, vh, iw, ih)
                                                .pannedBy(pan.x, pan.y, vw, vh, iw, ih)
                                        }

                                        2 -> {
                                            // Content follows the fingers, as on any
                                            // touchscreen: fingers moving down scroll up.
                                            remote.scroll(
                                                -pan.x.toDouble() / SCROLL_PX_PER_NOTCH,
                                                pan.y.toDouble() / SCROLL_PX_PER_NOTCH
                                            )
                                        }
                                    }
                                    lastSpan = span
                                    lastCentroid = centroid
                                }
                                event.changes.forEach { it.consume() }
                                continue
                            }

                            if (multi || longPressed) {
                                event.changes.forEach { it.consume() }
                                continue
                            }

                            val position = pressed[0].position
                            if (!moved && (position - start).getDistance() > slop) {
                                moved = true
                                if (currentMode == TouchMode.Direct) {
                                    sendPoint(start, force = true)
                                    remote.button(MouseButton.LEFT, ButtonAction.DOWN)
                                    dragging = true
                                    haptics.tick()
                                }
                            }
                            if (moved) {
                                when (currentMode) {
                                    TouchMode.Direct -> sendPoint(position)
                                    TouchMode.Trackpad -> {
                                        val delta = position - last
                                        val frame = viewport.frame(vw, vh, iw, ih)
                                        // Finger pixels to desktop pixels: the picture's
                                        // scale, so a zoomed-in view moves finely.
                                        val toDesktop = if (frame.width > 0f) {
                                            pic.screenWidth / frame.width
                                        } else {
                                            1f
                                        }
                                        remote.move(
                                            delta.x * toDesktop * TRACKPAD_GAIN,
                                            delta.y * toDesktop * TRACKPAD_GAIN
                                        )
                                    }
                                }
                            }
                            last = position
                            event.changes.forEach { it.consume() }
                        }

                        when {
                            dragging -> {
                                sendPoint(last, force = true)
                                remote.button(MouseButton.LEFT, ButtonAction.UP)
                            }

                            multi && multiMode == 0 -> {
                                // A two-finger tap is a right click, as on a laptop pad.
                                haptics.tap()
                                remote.click(MouseButton.RIGHT)
                            }

                            !moved && !multi && !longPressed -> {
                                if (currentMode == TouchMode.Direct) {
                                    if (viewport.toImage(start.x, start.y, vw, vh, iw, ih) == null) {
                                        // A tap on the letterbox toggles the toolbar.
                                        chromeVisible = !chromeVisible
                                        return@awaitEachGesture
                                    }
                                    sendPoint(start, force = true)
                                }
                                haptics.tap()
                                remote.click(MouseButton.LEFT)
                            }
                        }
                    }
                }
        ) {
            val pic = picture ?: return@Canvas
            val iw = pic.image.width.toFloat()
            val ih = pic.image.height.toFloat()
            val f = viewport.frame(size.width, size.height, iw, ih)
            drawImage(
                image = pic.image,
                dstOffset = IntOffset(f.left.toInt(), f.top.toInt()),
                dstSize = IntSize(f.width.toInt(), f.height.toInt()),
                filterQuality = FilterQuality.Medium
            )
            cursor?.let { (u, v) ->
                val x = f.left + u * f.width
                val y = f.top + v * f.height
                val s = 18f * (density.density / 2f).coerceAtLeast(1f)
                val arrow = Path().apply {
                    moveTo(x, y)
                    lineTo(x, y + s)
                    lineTo(x + s * 0.28f, y + s * 0.74f)
                    lineTo(x + s * 0.62f, y + s * 0.72f)
                    close()
                }
                drawPath(arrow, Color.White)
                drawPath(arrow, Color.Black, style = Stroke(width = 1.5f))
            }
        }

        // ---- Status ----

        when (val s = status) {
            RemoteScreen.Status.Starting, RemoteScreen.Status.Idle -> if (picture == null) {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.size(12.dp))
                    Text("Connecting to the desktop's screen…", color = Color.White)
                }
            }

            is RemoteScreen.Status.Failed -> Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.align(Alignment.Center).padding(24.dp)
            ) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        s.message,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.size(12.dp))
                    Button(onClick = {
                        haptics.tap()
                        remote.start(settings())
                    }) { Text("Try again") }
                }
            }

            RemoteScreen.Status.Streaming -> Unit
        }

        // ---- Toolbar ----

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding()
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color.Black.copy(alpha = 0.62f),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ToolbarButton(Icons.AutoMirrored.Filled.ArrowBack, "Back") { onBack() }
                    ToolbarButton(
                        if (mode == TouchMode.Direct) Icons.Filled.TouchApp else Icons.Filled.Mouse,
                        if (mode == TouchMode.Direct) "Touch mode" else "Trackpad mode"
                    ) {
                        mode = if (mode == TouchMode.Direct) TouchMode.Trackpad else TouchMode.Direct
                    }
                    ToolbarButton(Icons.Filled.Keyboard, "Keyboard") { keyboardOpen = !keyboardOpen }
                    Box {
                        ToolbarButton(Icons.Filled.HighQuality, "Quality") { qualityMenu = true }
                        DropdownMenu(expanded = qualityMenu, onDismissRequest = { qualityMenu = false }) {
                            Quality.entries.forEach { q ->
                                DropdownMenuItem(
                                    text = { Text(if (q == quality) "✓ ${q.label}" else q.label) },
                                    onClick = {
                                        haptics.tap()
                                        quality = q
                                        qualityMenu = false
                                    }
                                )
                            }
                        }
                    }
                    if (displays.size > 1) {
                        Box {
                            ToolbarButton(Icons.Filled.DesktopWindows, "Display") { displayMenu = true }
                            DropdownMenu(expanded = displayMenu, onDismissRequest = { displayMenu = false }) {
                                displays.forEach { d ->
                                    val label = buildString {
                                        append(d.name)
                                        append(" · ${d.width}×${d.height}")
                                        if (d.primary) append(" · main")
                                    }
                                    DropdownMenuItem(
                                        text = { Text(if (shownDisplay == d.id) "✓ $label" else label) },
                                        onClick = {
                                            haptics.tap()
                                            wantedDisplay = d.id
                                            displayMenu = false
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = {
                                        val label = "All displays"
                                        Text(if (shownDisplay == ScreenSettings.ALL_DISPLAYS) "✓ $label" else label)
                                    },
                                    onClick = {
                                        haptics.tap()
                                        wantedDisplay = ScreenSettings.ALL_DISPLAYS
                                        displayMenu = false
                                    }
                                )
                            }
                        }
                    }
                    if (viewport.isZoomed) {
                        ToolbarButton(Icons.Filled.ZoomOutMap, "Fit to screen") { viewport = Viewport() }
                    }
                    ToolbarButton(Icons.Filled.ScreenRotation, "Rotate") {
                        landscapeLocked = !landscapeLocked
                        context.findActivity()?.requestedOrientation = if (landscapeLocked) {
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        } else {
                            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }
                    }
                    picture?.let {
                        Text(
                            "${it.screenWidth}×${it.screenHeight}",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(end = 14.dp)
                        )
                    }
                }
            }
        }

        if (keyboardOpen) {
            RemoteKeyboard(
                remote = remote,
                onClose = { keyboardOpen = false },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

@Composable
private fun ToolbarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit
) {
    val haptics = LocalHaptics.current
    IconButton(onClick = {
        haptics.tap()
        onClick()
    }) {
        Icon(icon, contentDescription = description, tint = Color.White)
    }
}

/**
 * The phone's own keyboard, typing into the desktop.
 *
 * The text field is invisible and exists only to own the IME. What it reports
 * is diffed against what it held: characters added are typed, characters
 * removed are Backspaces. Diffing rather than resetting after each key is what
 * keeps predictive keyboards working -- an autocorrection replaces a whole word,
 * and arrives here as "delete these four, type these five".
 */
@Composable
private fun RemoteKeyboard(remote: RemoteScreen, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHaptics.current
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var field by remember { mutableStateOf(TextFieldValue(SENTINEL, TextRange(SENTINEL.length))) }

    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }

    Column(modifier.fillMaxWidth().imePadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.72f))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                "Esc" to RemoteKey.ESCAPE,
                "Tab" to RemoteKey.TAB,
                "←" to RemoteKey.LEFT,
                "↑" to RemoteKey.UP,
                "↓" to RemoteKey.DOWN,
                "→" to RemoteKey.RIGHT,
                "Home" to RemoteKey.HOME,
                "End" to RemoteKey.END,
                "Del" to RemoteKey.DELETE,
                "⌫" to RemoteKey.BACKSPACE,
                "⏎" to RemoteKey.ENTER
            ).forEach { (label, key) ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color.White.copy(alpha = 0.14f),
                    onClick = {
                        haptics.tap()
                        remote.key(key)
                    }
                ) {
                    Text(
                        label,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primary,
                onClick = {
                    haptics.tap()
                    keyboard?.hide()
                    onClose()
                }
            ) {
                Text(
                    "Done",
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }

        BasicTextField(
            value = field,
            onValueChange = { next ->
                val old = field.text
                val new = next.text
                val common = old.commonPrefixWith(new).length
                repeat(old.length - common) { remote.key(RemoteKey.BACKSPACE) }
                remote.text(new.substring(common).replace(SENTINEL_CHAR.toString(), ""))
                // Re-armed only between compositions: resetting mid-word would
                // pull the text out from under the keyboard's suggestion strip.
                field = if (next.composition == null && (new.length < SENTINEL.length || new.length > 64)) {
                    TextFieldValue(SENTINEL, TextRange(SENTINEL.length))
                } else {
                    next
                }
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false
            ),
            modifier = Modifier
                .size(1.dp)
                .focusRequester(focus)
        )
    }
}

private const val SENTINEL_CHAR = '​'

internal fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
