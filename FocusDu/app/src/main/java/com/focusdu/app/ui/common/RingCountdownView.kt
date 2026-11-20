package com.focusdu.app.ui.common

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * P06 环形倒计时：进度圆环 + 中央剩余时间。progress = 剩余/目标。
 */
class RingCountdownView(context: android.content.Context) : View(context) {

    private var progress = 1f          // 1 = 满（刚开始）
    private var text = "25:00"
    private var subText = ""
    private var ringColor = 0xFF2E7D6B.toInt()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFDCE7E3.toInt()
    }
    private val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = 0xFF1B4D42.toInt()
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = 0xFF8A9A94.toInt()
    }
    private val rect = RectF()

    fun update(remainingRatio: Float, timeText: String, sub: String, color: Int) {
        progress = remainingRatio.coerceIn(0f, 1f)
        text = timeText
        subText = sub
        ringColor = color
        contentDescription = "$sub 剩余 $timeText"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val stroke = size * 0.07f
        val half = size / 2f - stroke
        rect.set(cx - half, cy - half, cx + half, cy + half)

        bgPaint.strokeWidth = stroke
        canvas.drawArc(rect, 0f, 360f, false, bgPaint)
        fgPaint.strokeWidth = stroke
        fgPaint.color = ringColor
        canvas.drawArc(rect, -90f, 360f * progress, false, fgPaint)

        timePaint.textSize = size * 0.22f
        canvas.drawText(text, cx, cy + size * 0.06f, timePaint)
        subPaint.textSize = size * 0.09f
        if (subText.isNotEmpty()) {
            canvas.drawText(subText, cx, cy + size * 0.24f, subPaint)
        }
    }
}
