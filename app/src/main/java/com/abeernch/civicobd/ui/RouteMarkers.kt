package com.abeernch.civicobd.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The start and finish markers on route maps: a green disc where a drive began, and a chequered
 * flag badge where it ended. Each is drawn once, at the screen's own resolution, into a small picture
 * (smooth edges, a soft shadow, crisp squares), then placed on the map.
 */
object RouteMarkers {
    private val cache = HashMap<String, Bitmap>()

    /** Places the start marker centred on (x, y). */
    fun drawStart(canvas: Canvas, x: Float, y: Float, density: Float) = place(canvas, start(density), x, y)

    /** Places the finish marker centred on (x, y). */
    fun drawFinish(canvas: Canvas, x: Float, y: Float, density: Float) = place(canvas, finish(density), x, y)

    private val placePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private fun place(canvas: Canvas, b: Bitmap, x: Float, y: Float) {
        // Whole pixels, so the picture lands one to one on the screen and stays sharp.
        canvas.drawBitmap(b, (x - b.width / 2f).roundToInt().toFloat(), (y - b.height / 2f).roundToInt().toFloat(), placePaint)
    }

    /** A square picture with the marker at its centre and room around it for the shadow. */
    private fun canvasFor(radius: Float, density: Float): Pair<Bitmap, Canvas> {
        val half = ceil(radius + 4.5f * density).toInt()
        val b = Bitmap.createBitmap(half * 2, half * 2, Bitmap.Config.ARGB_8888)
        return b to Canvas(b)
    }

    /** A soft shadow under the marker, a touch lower, fading to nothing. */
    private fun shadow(c: Canvas, cx: Float, cy: Float, radius: Float, density: Float) {
        val r = radius + 3.5f * density
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy + 1.5f * density, r,
                intArrayOf(0x73000000, 0x40000000, 0x00000000), floatArrayOf(0.62f, 0.8f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        c.drawCircle(cx, cy + 1.5f * density, r, p)
    }

    private fun start(density: Float): Bitmap = cache.getOrPut("start@$density") {
        val radius = 8f * density
        val (b, c) = canvasFor(radius, density)
        val cx = b.width / 2f
        val cy = b.height / 2f
        shadow(c, cx, cy, radius, density)
        // A thin dark edge, so the marker holds its shape on light streets too.
        c.drawCircle(cx, cy, radius + 0.8f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 })
        // The green disc, lit very slightly from above.
        c.drawCircle(cx, cy, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, cy - radius, 0f, cy + radius, 0xFF9BE275.toInt(), 0xFF5CBF43.toInt(), Shader.TileMode.CLAMP)
        })
        // A darker green rim, as on the reference.
        c.drawCircle(cx, cy, radius - 0.5f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.0f * density
            color = 0xFF4C9F38.toInt()
        })
        b
    }

    private fun finish(density: Float): Bitmap = cache.getOrPut("finish@$density") {
        val radius = 10f * density
        val (b, c) = canvasFor(radius, density)
        val cx = b.width / 2f
        val cy = b.height / 2f
        shadow(c, cx, cy, radius, density)
        c.drawCircle(cx, cy, radius + 0.8f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 })
        // White rim.
        c.drawCircle(cx, cy, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
        // The chequered flag: whole-pixel squares, laid so a corner meets at the centre, inside a smooth circle.
        val cell = (3.1f * density).roundToInt().coerceAtLeast(3)
        val tile = Bitmap.createBitmap(cell * 2, cell * 2, Bitmap.Config.ARGB_8888)
        Canvas(tile).apply {
            val dark = Paint().apply { color = 0xFF141414.toInt() }
            val light = Paint().apply { color = 0xFFF4F4F4.toInt() }
            drawRect(0f, 0f, cell.toFloat(), cell.toFloat(), dark)
            drawRect(cell.toFloat(), 0f, cell * 2f, cell.toFloat(), light)
            drawRect(0f, cell.toFloat(), cell.toFloat(), cell * 2f, light)
            drawRect(cell.toFloat(), cell.toFloat(), cell * 2f, cell * 2f, dark)
        }
        val checker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = false
            shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
                setLocalMatrix(Matrix().apply { setTranslate(cx.roundToInt().toFloat(), cy.roundToInt().toFloat()) })
            }
        }
        c.drawCircle(cx, cy, radius - 2.2f * density, checker)
        tile.recycle()
        b
    }
}
