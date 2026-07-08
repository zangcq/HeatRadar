package com.example.heatradar.core.monitor

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import kotlin.math.abs
import kotlin.math.roundToInt

class FloatingWindowManager(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onClose: () -> Unit = {}
) {
    private var floatingView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isShowing = false

    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f
    private var isDragging = false

    fun show() {
        if (isShowing) return

        val minWidthPx = dpToPx(220)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = getScreenWidth() - minWidthPx - dpToPx(8)
            y = dpToPx(100)
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
        }

        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val state by MonitorService.monitorState.collectAsState()
                FloatingOverlayContent(
                    state = state,
                    minWidth = minWidthPx,
                    maxWidth = dpToPx(280),
                    onClose = onClose
                )
            }
        }

        composeView.setOnTouchListener { _, event ->
            handleTouch(event, params)
        }

        windowManager.addView(composeView, params)
        floatingView = composeView
        layoutParams = params
        isShowing = true
    }

    fun hide() {
        if (!isShowing) return
        floatingView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        floatingView = null
        layoutParams = null
        isShowing = false
    }

    fun isShowing(): Boolean = isShowing

    private fun handleTouch(event: MotionEvent, params: WindowManager.LayoutParams): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                if (!isDragging && (abs(dx) > 10 || abs(dy) > 10)) {
                    isDragging = true
                }
                if (isDragging) {
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    floatingView?.let { view ->
                        try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
                    }
                    return true
                }
                return false
            }
            MotionEvent.ACTION_UP -> {
                return if (isDragging) {
                    isDragging = false
                    true
                } else {
                    false
                }
            }
        }
        return false
    }

    private fun dpToPx(dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density).roundToInt()
    }

    private fun getScreenWidth(): Int {
        return context.resources.displayMetrics.widthPixels
    }
}
