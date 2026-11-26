package com.focusdu.app.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** P02 的简洁机位示意图：桌面、手机、头部、台灯和 50–70cm 距离线。 */
class PlacementDiagramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(4)
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(10), dp(8)), 0f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, resolveSize(dp(240).toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        fill.color = Color.parseColor("#EAF1EE")
        canvas.drawRoundRect(dp(4), dp(4), w - dp(4), h - dp(4), dp(20), dp(20), fill)

        stroke.color = Color.parseColor("#60746C")
        canvas.drawLine(dp(24), h * 0.78f, w - dp(24), h * 0.78f, stroke) // desk

        fill.color = Color.parseColor("#2E7D6B")
        canvas.drawCircle(w * 0.35f, h * 0.38f, dp(28), fill) // face/head
        stroke.color = Color.parseColor("#2E7D6B")
        canvas.drawLine(w * 0.26f, h * 0.58f, w * 0.44f, h * 0.58f, stroke) // shoulders

        stroke.color = Color.parseColor("#173B32")
        canvas.drawRoundRect(
            w * 0.76f,
            h * 0.20f,
            w * 0.87f,
            h * 0.68f,
            dp(10),
            dp(10),
            stroke,
        ) // phone
        canvas.drawCircle(w * 0.815f, h * 0.27f, dp(4), fill)

        dashed.color = Color.parseColor("#C65A1E")
        canvas.drawLine(w * 0.39f, h * 0.36f, w * 0.76f, h * 0.36f, dashed)

        stroke.color = Color.parseColor("#E4B44C")
        canvas.drawLine(w * 0.15f, h * 0.12f, w * 0.15f, h * 0.28f, stroke)
        canvas.drawCircle(w * 0.15f, h * 0.31f, dp(12), fill.apply { color = Color.parseColor("#E4B44C") })
        canvas.drawLine(w * 0.19f, h * 0.20f, w * 0.29f, h * 0.34f, stroke)
    }

    private fun dp(value: Int): Float = value * resources.displayMetrics.density
}
