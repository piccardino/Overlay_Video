package com.example.vhpmatchpresentation.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.example.vhpmatchpresentation.data.PlayerStats
import kotlin.math.cos
import kotlin.math.sin

class RadarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val labels = arrayOf("ATT", "BLK", "SRV", "SET", "DEF")
    private var values = floatArrayOf(75f, 70f, 80f, 68f, 72f)
    private var teamColor: Int = Color.parseColor("#0284C7")

    private var animationProgress = 1.0f

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#475569")
    }

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#334155")
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private val polygonPath = Path()
    private val webPath = Path()

    fun setStats(stats: PlayerStats, teamColorHex: String = "#0284C7", animate: Boolean = true) {
        values[0] = stats.attack.coerceIn(0, 100).toFloat()
        values[1] = stats.block.coerceIn(0, 100).toFloat()
        values[2] = stats.serve.coerceIn(0, 100).toFloat()
        values[3] = stats.set.coerceIn(0, 100).toFloat()
        values[4] = stats.defense.coerceIn(0, 100).toFloat()

        try {
            teamColor = Color.parseColor(teamColorHex)
        } catch (e: Exception) {
            teamColor = Color.parseColor("#0284C7")
        }

        val r = Color.red(teamColor)
        val g = Color.green(teamColor)
        val b = Color.blue(teamColor)
        fillPaint.color = Color.argb(100, r, g, b)
        strokePaint.color = Color.rgb(r, g, b)

        if (animate) {
            val animator = ValueAnimator.ofFloat(0f, 1.0f)
            animator.duration = 600
            animator.interpolator = DecelerateInterpolator()
            animator.addUpdateListener { anim ->
                animationProgress = anim.animatedValue as Float
                invalidate()
            }
            animator.start()
        } else {
            animationProgress = 1.0f
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()
        val centerX = width / 2f
        val centerY = height / 2f

        val padding = 36f
        val maxRadius = (Math.min(width, height) / 2f) - padding
        if (maxRadius <= 0) return

        val count = labels.size
        val angleStep = (2.0 * Math.PI / count).toFloat()

        val levels = 4
        for (l in 1..levels) {
            val levelRadius = maxRadius * (l.toFloat() / levels)
            webPath.reset()
            for (i in 0 until count) {
                val angle = i * angleStep - (Math.PI / 2.0).toFloat()
                val x = (centerX + levelRadius * cos(angle.toDouble())).toFloat()
                val y = (centerY + levelRadius * sin(angle.toDouble())).toFloat()
                if (i == 0) webPath.moveTo(x, y) else webPath.lineTo(x, y)
            }
            webPath.close()
            canvas.drawPath(webPath, gridPaint)
        }

        for (i in 0 until count) {
            val angle = i * angleStep - (Math.PI / 2.0).toFloat()
            val endX = (centerX + maxRadius * cos(angle.toDouble())).toFloat()
            val endY = (centerY + maxRadius * sin(angle.toDouble())).toFloat()

            canvas.drawLine(centerX, centerY, endX, endY, axisPaint)

            val labelRadius = maxRadius + 22f
            val labelX = (centerX + labelRadius * cos(angle.toDouble())).toFloat()
            val labelY = (centerY + labelRadius * sin(angle.toDouble())).toFloat() + (labelPaint.textSize / 3f)

            canvas.drawText(labels[i], labelX, labelY, labelPaint)
        }

        polygonPath.reset()
        for (i in 0 until count) {
            val valNorm = (values[i] / 100f) * animationProgress
            val r = maxRadius * valNorm
            val angle = i * angleStep - (Math.PI / 2.0).toFloat()
            val x = (centerX + r * cos(angle.toDouble())).toFloat()
            val y = (centerY + r * sin(angle.toDouble())).toFloat()

            if (i == 0) polygonPath.moveTo(x, y) else polygonPath.lineTo(x, y)
        }
        polygonPath.close()

        canvas.drawPath(polygonPath, fillPaint)
        canvas.drawPath(polygonPath, strokePaint)
    }
}
