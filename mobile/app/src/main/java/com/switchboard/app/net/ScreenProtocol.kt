package com.switchboard.app.net

import kotlinx.serialization.Serializable

/**
 * Screen sharing wire types, mirroring `backend/internal/protocol/screen.go`.
 *
 * Two directions, named apart: `screen.*` is the desktop shown on this phone,
 * `mirror.*` is this phone shown on the desktop.
 */
object ScreenActions {
    const val SCREEN_START = "screen.start"
    const val SCREEN_STOP = "screen.stop"
    const val SCREEN_FRAME = "screen.frame"
    const val SCREEN_STATE = "screen.state"

    const val INPUT_POINT = "input.point"
    const val INPUT_KEY = "input.key"

    const val MIRROR_START = "mirror.start"
    const val MIRROR_STOP = "mirror.stop"
    const val MIRROR_KEYFRAME = "mirror.keyframe"
    const val MIRROR_FRAME = "mirror.frame"
    const val MIRROR_STATE = "mirror.state"
    const val MIRROR_INPUT = "mirror.input"
    const val MIRROR_APPS = "mirror.apps"
    const val MIRROR_LAUNCH = "mirror.launch"
}

/** Named keys `input.key` and a mirror `key` input accept. */
object RemoteKey {
    const val BACKSPACE = "backspace"
    const val DELETE = "delete"
    const val ENTER = "enter"
    const val TAB = "tab"
    const val ESCAPE = "escape"
    const val LEFT = "left"
    const val RIGHT = "right"
    const val UP = "up"
    const val DOWN = "down"
    const val HOME = "home"
    const val END = "end"
    const val PAGE_UP = "pageUp"
    const val PAGE_DOWN = "pageDown"
}

// ---- Desktop -> this phone ----

@Serializable
data class ScreenSettings(
    val maxWidth: Int = 1600,
    val quality: Int = 70,
    val fps: Int = 20,
    /** A [ScreenDisplay.id], [ALL_DISPLAYS], or empty for the primary monitor. */
    val display: String = ""
) : WirePayload {
    companion object {
        const val ALL_DISPLAYS = "all"
    }
}

/** One of the desktop's monitors, in desktop pixels. */
@Serializable
data class ScreenDisplay(
    val id: String,
    val name: String = id,
    val x: Int = 0,
    val y: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val primary: Boolean = false
)

/** Metadata for one JPEG of the desktop. A frame with no blob moves only the cursor. */
@Serializable
data class ScreenFrameMeta(
    val seq: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    val cursorX: Double = -1.0,
    val cursorY: Double = -1.0
)

@Serializable
data class ScreenState(
    val streaming: Boolean = false,
    val error: String? = null,
    /** The desktop chose to stop, as opposed to failing to capture. */
    val ended: Boolean = false,
    val displays: List<ScreenDisplay> = emptyList(),
    val display: String? = null
)

/** Absolute pointer position as fractions of the shared desktop. */
@Serializable
data class InputPoint(val x: Double, val y: Double) : WirePayload

@Serializable
data class InputKey(val key: String) : WirePayload

// ---- This phone -> desktop ----

@Serializable
data class MirrorSettings(
    val maxSize: Int = 1920,
    val bitrate: Int = 8_000_000,
    val fps: Int = 60
) : WirePayload

@Serializable
data class MirrorFrame(
    val seq: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val ts: Long = 0,
    val key: Boolean = false
) : WirePayload

@Serializable
data class MirrorState(
    val streaming: Boolean = false,
    val awaitingConsent: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    /** Whether the accessibility service that performs the desktop's input is on. */
    val control: Boolean = false,
    val foreground: String? = null,
    val settings: MirrorSettings = MirrorSettings(),
    val error: String? = null
) : WirePayload

object MirrorKind {
    const val TAP = "tap"
    const val LONG_PRESS = "longPress"
    const val SWIPE = "swipe"
    const val SCROLL = "scroll"
    const val TEXT = "text"
    const val KEY = "key"
    const val NAV = "nav"
}

object MirrorNav {
    const val BACK = "back"
    const val HOME = "home"
    const val RECENTS = "recents"
    const val NOTIFICATIONS = "notifications"
    const val QUICK_SETTINGS = "quickSettings"
    const val LOCK = "lock"
    const val SCREENSHOT = "screenshot"
}

/** A position on this phone's screen as fractions, [t] ms into the stroke. */
@Serializable
data class MirrorPoint(val x: Double, val y: Double, val t: Long = 0)

@Serializable
data class MirrorInput(
    val kind: String,
    val points: List<MirrorPoint> = emptyList(),
    val duration: Long = 0,
    val dx: Double = 0.0,
    val dy: Double = 0.0,
    val text: String? = null,
    val key: String? = null,
    val nav: String? = null
)

@Serializable
data class MirrorApp(
    val `package`: String,
    val label: String,
    val icon: String? = null
)

@Serializable
data class MirrorApps(val apps: List<MirrorApp>) : WirePayload

@Serializable
data class MirrorLaunch(val `package`: String)
