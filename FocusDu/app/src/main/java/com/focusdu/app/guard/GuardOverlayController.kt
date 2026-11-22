package com.focusdu.app.guard

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.focusdu.app.R

/**
 * P10 的运行时拦截页。使用用户明确授权的悬浮窗显示，不依赖无障碍或锁机。
 * “退出”必须点击两次，且只放行当前这一轮，不用不可解除的方式阻止用户。
 */
class GuardOverlayController(
    private val context: Context,
    private val onReturnFocus: () -> Unit,
    private val onConfirmExit: (String) -> Unit,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var overlayView: View? = null
    private var shownPackage: String? = null
    private var exitConfirmationPending = false

    fun show(packageName: String): Boolean {
        if (overlayView != null && shownPackage == packageName) return false
        hide()
        shownPackage = packageName
        exitConfirmationPending = false

        val body = TextView(context).apply {
            text = context.getString(R.string.guard_overlay_message, appLabel(packageName))
            textSize = 16f
            setTextColor(Color.parseColor("#4C5A55"))
            gravity = Gravity.CENTER
        }
        val exitButton = actionButton(context.getString(R.string.guard_overlay_exit_once)).apply {
            setOnClickListener {
                if (!exitConfirmationPending) {
                    exitConfirmationPending = true
                    text = context.getString(R.string.guard_overlay_exit_confirm)
                    body.text = context.getString(R.string.guard_overlay_exit_hint)
                } else {
                    hide()
                    onConfirmExit(packageName)
                }
            }
        }
        val returnButton = actionButton(context.getString(R.string.guard_overlay_return)).apply {
            setOnClickListener {
                hide()
                onReturnFocus()
            }
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(32), dp(28), dp(32))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.WHITE)
            }
            addView(TextView(context).apply {
                text = context.getString(R.string.guard_overlay_title)
                textSize = 25f
                setTextColor(Color.parseColor("#173B32"))
                gravity = Gravity.CENTER
            })
            addView(body, spaceTop(dp(16)))
            addView(returnButton, spaceTop(dp(24)))
            addView(exitButton, spaceTop(dp(12)))
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.parseColor("#F0F5F2"))
            addView(card, LinearLayout.LayoutParams(-1, -2))
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
        }
        return runCatching { windowManager.addView(root, params) }
            .onSuccess { overlayView = root }
            .onFailure { shownPackage = null }
            .isSuccess
    }

    fun hide() {
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        overlayView = null
        shownPackage = null
        exitConfirmationPending = false
    }

    private fun actionButton(label: String) = TextView(context).apply {
        text = label
        textSize = 16f
        gravity = Gravity.CENTER
        setPadding(dp(20), dp(14), dp(20), dp(14))
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.parseColor("#2E7D6B"))
        }
        isClickable = true
    }

    private fun spaceTop(top: Int) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = top
    }

    private fun appLabel(packageName: String): String = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
