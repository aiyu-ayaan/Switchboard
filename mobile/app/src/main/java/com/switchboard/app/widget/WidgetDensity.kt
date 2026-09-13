package com.switchboard.app.widget

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How much of itself a widget can afford to show at the size the user gave it.
 *
 * A home-screen widget is resized by hand, and a layout built for the size it
 * was designed at simply clips when it is made smaller -- the reading at the
 * bottom disappears without saying so, which is worse than not offering it.
 * Each widget picks a density from its real height and drops whole elements,
 * in order of what can be spared, rather than letting the last one fall off
 * the edge.
 */
enum class WidgetDensity {
    /** Too short for anything but the essentials: no title bar, no chrome. */
    Tiny,

    /** Everything structural, but no secondary lines and smaller figures. */
    Compact,

    /** The full design. */
    Comfortable;

    val showsTitleBar: Boolean get() = this != Tiny
    val showsDetail: Boolean get() = this == Comfortable
}

/**
 * Heights below which elements start coming off.
 *
 * The numbers are what the content actually measures: a Glance title bar is
 * about 48dp with its padding, a stat card with a secondary line about 76dp
 * and without one about 52dp. Two rows of the latter plus a footer is what
 * [WidgetDensity.Compact] has to fit.
 */
internal object WidgetHeights {
    val tinyBelow: Dp = 140.dp
    val compactBelow: Dp = 210.dp
}

internal fun densityFor(height: Dp): WidgetDensity = when {
    height < WidgetHeights.tinyBelow -> WidgetDensity.Tiny
    height < WidgetHeights.compactBelow -> WidgetDensity.Compact
    else -> WidgetDensity.Comfortable
}

/**
 * Whether a second row of stat cards fits.
 *
 * Below this the widget shows CPU and memory only. Half the readings, plainly,
 * beats four readings with two of them sliced in half.
 */
internal fun fitsTwoStatRows(height: Dp): Boolean = height >= 150.dp

/** Whether the age stamp and the "Open" link have room left under the cards. */
internal fun fitsStatFooter(height: Dp): Boolean = height >= 190.dp

/**
 * Whether a connected desktop's card can carry its now-playing and volume
 * lines.
 *
 * A lower bar than [WidgetDensity.showsDetail] on purpose: the host cards sit
 * in a scrolling list, so two extra lines that do not quite fit push content
 * down rather than off the edge. The stat grid has no such escape, which is
 * why it holds to the stricter threshold.
 */
internal fun fitsHostDetail(height: Dp): Boolean = height >= 185.dp

/**
 * Whether the status pill can spell out what a tap does.
 *
 * On a narrow widget "Tap to control" crowds the machine name it sits beside,
 * and the name is the part the user is reading.
 */
internal fun fitsLongStatus(width: Dp): Boolean = width >= 250.dp
