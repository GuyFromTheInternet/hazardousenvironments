package com.abandonsearch.hazardgrid.ui.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import kotlin.math.min

class HazardMarkerDrawable(
    private val isActive: Boolean,
) : Drawable() {

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = if (isActive) ACTIVE_HALO else INACTIVE_HALO
    }

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = if (isActive) ACTIVE_CORE else INACTIVE_CORE
    }

    private val bladePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }

    private val outerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
    }

    private val centerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = YELLOW
    }

    private val centerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }

    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }

    private val scratch = Path()

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val size = min(bounds.width(), bounds.height()).toFloat()
        if (size <= 0f) return

        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val radius = size / 2f

        canvas.drawCircle(cx, cy, radius, haloPaint)

        val coreRadius = radius * 0.78f
        canvas.drawCircle(cx, cy, coreRadius, corePaint)

        outerStrokePaint.strokeWidth = radius * 0.08f
        canvas.drawCircle(cx, cy, coreRadius - outerStrokePaint.strokeWidth * 0.5f, outerStrokePaint)

        canvas.save()
        canvas.translate(cx, cy)
        val outerRadius = coreRadius * 0.92f
        val innerRadius = coreRadius * 0.45f
        val outerRect = android.graphics.RectF(-outerRadius, -outerRadius, outerRadius, outerRadius)
        val innerRect = android.graphics.RectF(-innerRadius, -innerRadius, innerRadius, innerRadius)
        val startAngles = floatArrayOf(-90f, 30f, 150f)
        val sweep = 60f
        startAngles.forEach { start ->
            scratch.reset()
            scratch.arcTo(outerRect, start, sweep, false)
            scratch.arcTo(innerRect, start + sweep, -sweep, false)
            scratch.close()
            canvas.drawPath(scratch, bladePaint)
        }

        canvas.restore()

        val centerRadius = innerRadius * 0.62f
        canvas.drawCircle(cx, cy, centerRadius, centerFillPaint)

        centerStrokePaint.strokeWidth = radius * 0.08f
        canvas.drawCircle(cx, cy, innerRadius * 0.92f, centerStrokePaint)

        canvas.drawCircle(cx, cy, innerRadius * 0.42f, hubPaint)
    }

    override fun setAlpha(alpha: Int) {
        haloPaint.alpha = alpha
        corePaint.alpha = alpha
        bladePaint.alpha = alpha
        hubPaint.alpha = alpha
        outerStrokePaint.alpha = alpha
        centerStrokePaint.alpha = alpha
        centerFillPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        haloPaint.colorFilter = colorFilter
        corePaint.colorFilter = colorFilter
        bladePaint.colorFilter = colorFilter
        hubPaint.colorFilter = colorFilter
        outerStrokePaint.colorFilter = colorFilter
        centerStrokePaint.colorFilter = colorFilter
        centerFillPaint.colorFilter = colorFilter
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        private val ACTIVE_HALO = Color.parseColor("#CCFF5050")
        private val INACTIVE_HALO = Color.parseColor("#55F5C400")
        private val ACTIVE_CORE = Color.parseColor("#FFFF00")
        private val INACTIVE_CORE = Color.parseColor("#FFE7B400")
        private val YELLOW = Color.parseColor("#FFFF00")
    }
}
