package com.example.mockgo

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView

/** 模拟运行期间显示的小悬浮框：可拖动，点一下回到本应用。停止模拟时移除。 */
class FloatBadge(private val ctx: Context) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: TextView? = null

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun show() {
        if (view != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(ctx)) return

        val tv = TextView(ctx).apply {
            text = "● 模拟中"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xCC1E88E5.toInt())
            }
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(140)
        }

        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        tv.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (moved || Math.abs(dx) > slop || Math.abs(dy) > slop) {
                        moved = true
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        try {
                            ctx.startActivity(
                                Intent(ctx, MainActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            )
                        } catch (_: Exception) {}
                    }
                }
            }
            true
        }
        try {
            wm.addView(tv, lp)
            view = tv
        } catch (_: Exception) {
            // 没有悬浮窗权限或系统拒绝：忽略，不影响模拟
        }
    }

    fun setText(s: String) { view?.text = s }

    fun hide() {
        val v = view ?: return
        view = null
        try { wm.removeView(v) } catch (_: Exception) {}
    }
}
