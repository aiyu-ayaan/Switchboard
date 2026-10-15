package com.switchboard.app.screen

import kotlin.math.max
import kotlin.math.min

/**
 * Where the desktop's picture sits on this phone's screen: fitted to the view,
 * then zoomed and panned by the user.
 *
 * Kept free of Compose types so the mapping that every tap depends on -- a
 * finger position back to a fraction of the desktop -- is plain arithmetic a
 * unit test can pin down. A mapping that is a few pixels off is a remote screen
 * where every click lands on the wrong button.
 */
data class Viewport(
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f
) {
    /** The picture's rectangle in view coordinates: left, top, width, height. */
    data class Frame(val left: Float, val top: Float, val width: Float, val height: Float) {
        val right get() = left + width
        val bottom get() = top + height
    }

    fun frame(viewW: Float, viewH: Float, imageW: Float, imageH: Float): Frame {
        if (viewW <= 0f || viewH <= 0f || imageW <= 0f || imageH <= 0f) return Frame(0f, 0f, 0f, 0f)
        val fit = min(viewW / imageW, viewH / imageH)
        val w = imageW * fit * zoom
        val h = imageH * fit * zoom
        return Frame((viewW - w) / 2f + panX, (viewH - h) / 2f + panY, w, h)
    }

    /**
     * A view position as fractions of the picture, or null when it is outside
     * the picture -- a tap on the letterbox should do nothing rather than click
     * the nearest edge of the desktop.
     */
    fun toImage(x: Float, y: Float, viewW: Float, viewH: Float, imageW: Float, imageH: Float): Pair<Float, Float>? {
        val f = frame(viewW, viewH, imageW, imageH)
        if (f.width <= 0f || f.height <= 0f) return null
        val u = (x - f.left) / f.width
        val v = (y - f.top) / f.height
        if (u < 0f || u > 1f || v < 0f || v > 1f) return null
        return u to v
    }

    /** Like [toImage], but clamps to the picture's edge instead of refusing. */
    fun toImageClamped(x: Float, y: Float, viewW: Float, viewH: Float, imageW: Float, imageH: Float): Pair<Float, Float> {
        val f = frame(viewW, viewH, imageW, imageH)
        if (f.width <= 0f || f.height <= 0f) return 0f to 0f
        return ((x - f.left) / f.width).coerceIn(0f, 1f) to ((y - f.top) / f.height).coerceIn(0f, 1f)
    }

    /** Zooms by [factor] keeping the point under ([focusX], [focusY]) fixed. */
    fun zoomedBy(
        factor: Float,
        focusX: Float,
        focusY: Float,
        viewW: Float,
        viewH: Float,
        imageW: Float,
        imageH: Float
    ): Viewport {
        val before = frame(viewW, viewH, imageW, imageH)
        if (before.width <= 0f) return this
        val nextZoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val u = (focusX - before.left) / before.width
        val v = (focusY - before.top) / before.height
        val scaledW = before.width / zoom * nextZoom
        val scaledH = before.height / zoom * nextZoom
        val left = focusX - u * scaledW
        val top = focusY - v * scaledH
        return Viewport(nextZoom, left - (viewW - scaledW) / 2f, top - (viewH - scaledH) / 2f)
            .clamped(viewW, viewH, imageW, imageH)
    }

    fun pannedBy(dx: Float, dy: Float, viewW: Float, viewH: Float, imageW: Float, imageH: Float): Viewport =
        copy(panX = panX + dx, panY = panY + dy).clamped(viewW, viewH, imageW, imageH)

    /**
     * Keeps the picture covering the view along any axis where it is larger
     * than the view, and centred along any axis where it is not: panning past
     * the edge of the desktop only ever shows black.
     */
    fun clamped(viewW: Float, viewH: Float, imageW: Float, imageH: Float): Viewport {
        val f = Viewport(zoom).frame(viewW, viewH, imageW, imageH)
        val limitX = max(0f, (f.width - viewW) / 2f)
        val limitY = max(0f, (f.height - viewH) / 2f)
        return copy(panX = panX.coerceIn(-limitX, limitX), panY = panY.coerceIn(-limitY, limitY))
    }

    val isZoomed get() = zoom > 1.01f

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 6f
    }
}
