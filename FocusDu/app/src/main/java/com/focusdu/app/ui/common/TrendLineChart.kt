package com.focusdu.app.ui.common

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * P13 历史趋势折线图：零依赖自绘，最多 2 条系列（如 有效学习时长 / 风险率）。
 * 每条系列独立归一化（各自 0..1 显示趋势形状），点上方标注原始值。
 */
class TrendLineChart(
    context: android.content.Context,
    private val seriesColors: List<Int> = listOf(0xFF2E7D6B.toInt(), 0xFFC65A1E.toInt()),
) : View(context) {

    data class Series(val name: String, val values: List<Float>, val format: (Float) -> String)

    private var series: List<Series> = emptyList()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 26f
        textAlign = Paint.Align.CENTER
        color = 0xFF8A9A94.toInt()
    }
    private val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f }
    private val path = Path()

    fun setData(data: List<Series>) {
        series = data
        contentDescription = data.joinToString("，") { "${it.name}趋势折线" }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(0xFFEAF1EE.toInt())

        if (series.isEmpty() || series.all { it.values.size < 2 }) {
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("至少完成 2 轮后显示趋势", w / 2, h / 2, textPaint)
            return
        }

        var legendX = 24f
        val hasTwo = series.size > 1
        series.forEachIndexed { si, s ->
            val color = seriesColors[si % seriesColors.size]
            if (hasTwo) {
                legendPaint.color = color
                canvas.drawText("● ${s.name}", legendX, 44f, legendPaint)
                legendX += legendPaint.measureText("● ${s.name}") + 40f
            }
            val n = s.values.size
            val top = if (hasTwo) 70f else 30f
            val bottom = h - 40f
            val stepX = w / (n - 1).coerceAtLeast(1)
            val minV = s.values.min()
            val maxV = s.values.max()
            val range = (maxV - minV).takeIf { it > 1e-9f } ?: 1f

            path.reset()
            s.values.forEachIndexed { i, v ->
                val x = if (n == 1) w / 2 else i * stepX
                val y = bottom - ((v - minV) / range) * (bottom - top)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            linePaint.color = color
            canvas.drawPath(path, linePaint)

            s.values.forEachIndexed { i, v ->
                val x = if (n == 1) w / 2 else i * stepX
                val y = bottom - ((v - minV) / range) * (bottom - top)
                dotPaint.color = color
                canvas.drawCircle(x, y, 10f, dotPaint)
                textPaint.color = 0xFF4A5A54.toInt()
                textPaint.textSize = 24f
                canvas.drawText(s.format(v), x.coerceIn(60f, w - 60f), y - 18f, textPaint)
            }
        }
    }
}
