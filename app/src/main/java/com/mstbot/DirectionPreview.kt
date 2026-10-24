package com.mstbot

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 手機外框裡畫出「從中心往哪拉、拉多長」，方向和長度改了就即時更新。 */
class DirectionPreview(ctx: Context) : View(ctx) {
    var direction = Direction.RIGHT_UP
        set(v) {
            field = v
            updateDescription()
            invalidate()
        }
    var length = 0.3f
        set(v) {
            field = v
            invalidate()
        }

    private val d = ctx.resources.displayMetrics.density
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * d
        color = Palette.border
    }
    private val screen = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.bg }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4 * d
        strokeCap = Paint.Cap.ROUND
        color = Palette.accent
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.fg }
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.muted }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.fg
        textSize = 11 * d
        textAlign = Paint.Align.CENTER
    }
    private val dash = DashPathEffect(floatArrayOf(8 * d, 7 * d), 0f)
    private val rect = RectF()

    init {
        updateDescription()
    }

    private fun updateDescription() {
        contentDescription = "預覽：從螢幕中心${direction.label}拖曳"
    }

    override fun onDraw(canvas: Canvas) {
        val ph = height - 16 * d
        val pw = ph * 9f / 19.5f
        val left = (width - pw) / 2
        val top = 8 * d
        rect.set(left, top, left + pw, top + ph)
        canvas.drawRoundRect(rect, pw * 0.14f, pw * 0.14f, screen)
        canvas.drawRoundRect(rect, pw * 0.14f, pw * 0.14f, frame)

        val cx = rect.centerX()
        val cy = rect.centerY()
        // 長度是螢幕寬的比例，45 度：x、y 各走長度的 1/√2
        val k = length * pw / sqrt(2f)
        arrow.pathEffect = if (direction == Direction.RANDOM) dash else null
        val right = direction != Direction.LEFT_UP
        val leftSide = direction != Direction.RIGHT_UP
        if (right) drawArrow(canvas, cx, cy, cx + k, cy - k)
        if (leftSide) drawArrow(canvas, cx, cy, cx - k, cy - k)
        if (direction == Direction.ALTERNATE) {
            drawBadge(canvas, cx + k + 10 * d, cy - k - 10 * d, "1")
            drawBadge(canvas, cx - k - 10 * d, cy - k - 10 * d, "2")
        }
        canvas.drawCircle(cx, cy, 5 * d, dot)
    }

    private fun drawArrow(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        c.drawLine(x1, y1, x2, y2, arrow)
        val a = atan2(y2 - y1, x2 - x1)
        val head = 11 * d
        val saved = arrow.pathEffect
        arrow.pathEffect = null
        for (s in listOf(-0.5f, 0.5f)) {
            c.drawLine(x2, y2, x2 - head * cos(a + s), y2 - head * sin(a + s), arrow)
        }
        arrow.pathEffect = saved
    }

    private fun drawBadge(c: Canvas, x: Float, y: Float, s: String) {
        c.drawCircle(x, y, 9 * d, badge)
        c.drawText(s, x, y - (badgeText.descent() + badgeText.ascent()) / 2, badgeText)
    }
}
