package com.example.heatradar.core.monitor

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.math.roundToInt

class FloatingWindowManager(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onClose: () -> Unit = {}
) {
    private var floatingView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isShowing = false
    private val isCollapsed: MutableState<Boolean> = mutableStateOf(false)
    private val alertLevelState: MutableState<AlertLevel> = mutableStateOf(AlertLevel.NORMAL)
    private var currentAnimator: ValueAnimator? = null

    private val lifecycleOwner = FloatingLifecycleOwner()

    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private val marginPx: Int by lazy { dpToPx(8) }
    private val edgePaddingPx: Int by lazy { dpToPx(4) }
    private val expandedWidthPx: Int by lazy { dpToPx(260) }
    private val collapsedSizePx: Int by lazy { dpToPx(44) }

    private fun updateScreenSize() {
        val metrics = context.resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
    }

    fun show() {
        if (isShowing) {
            expand()
            return
        }

        updateScreenSize()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = marginPx
            y = dpToPx(100)
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
        }

        lifecycleOwner.onCreate()
        lifecycleOwner.onStart()
        lifecycleOwner.onResume()

        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)

            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)

            setContent {
                val state by MonitorService.monitorState.collectAsState()
                FloatingOverlayContent(
                    state = state,
                    isCollapsed = isCollapsed.value,
                    alertLevel = alertLevelState.value,
                    onClose = onClose,
                    onDrag = { dx, dy -> onDragging(dx, dy) },
                    onDragEnd = { snapToEdge() },
                    onCollapse = { collapse() },
                    onExpand = { expand() }
                )
            }
        }

        try {
            windowManager.addView(composeView, params)
            floatingView = composeView
            layoutParams = params
            isShowing = true
        } catch (e: Exception) {
            Log.e("FloatingWindow", "Failed to add floating window", e)
            lifecycleOwner.onPause()
            lifecycleOwner.onStop()
            lifecycleOwner.onDestroy()
        }
    }

    private fun onDragging(dx: Float, dy: Float) {
        currentAnimator?.cancel()
        val view = floatingView ?: return
        val params = layoutParams ?: return

        params.x += dx.toInt()
        params.y += dy.toInt()

        val maxX = screenWidth - dpToPx(30)
        val minX = 0
        params.x = params.x.coerceIn(minX, maxX)
        params.y = params.y.coerceIn(0, screenHeight - dpToPx(100))

        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.e("FloatingWindow", "Failed to update position", e)
        }
    }

    private fun snapToEdge() {
        val view = floatingView ?: return
        val params = layoutParams ?: return

        updateScreenSize()

        val collapsed = isCollapsed.value
        val viewWidth = if (collapsed) collapsedSizePx else expandedWidthPx
        val centerX = params.x + viewWidth / 2
        val targetX: Int

        if (centerX < screenWidth / 2) {
            targetX = edgePaddingPx
        } else {
            targetX = screenWidth - viewWidth - edgePaddingPx
        }

        animateTo(params.x, targetX)
    }

    private fun animateTo(startX: Int, endX: Int, duration: Long = 250) {
        val view = floatingView ?: return
        val params = layoutParams ?: return

        currentAnimator?.cancel()
        val animator = ValueAnimator.ofInt(startX, endX)
        animator.duration = duration
        animator.addUpdateListener { animation ->
            params.x = animation.animatedValue as Int
            try {
                windowManager.updateViewLayout(view, params)
            } catch (_: Exception) {}
        }
        animator.start()
        currentAnimator = animator
    }

    private fun collapse() {
        if (isCollapsed.value) return
        isCollapsed.value = true

        val params = layoutParams ?: return
        updateScreenSize()

        val centerX = params.x + expandedWidthPx / 2
        val targetX: Int
        if (centerX < screenWidth / 2) {
            targetX = edgePaddingPx
        } else {
            targetX = screenWidth - collapsedSizePx - edgePaddingPx
        }

        animateTo(params.x, targetX)
    }

    private fun expand() {
        if (!isCollapsed.value) return
        isCollapsed.value = false

        val params = layoutParams ?: return
        updateScreenSize()

        val targetX: Int
        val currentX = params.x

        if (currentX + collapsedSizePx / 2 < screenWidth / 2) {
            targetX = marginPx
        } else {
            targetX = screenWidth - expandedWidthPx - marginPx
        }

        animateTo(params.x, targetX)
    }

    fun hide() {
        if (!isShowing) return
        currentAnimator?.cancel()
        floatingView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e("FloatingWindow", "Failed to remove floating window", e)
            }
        }
        lifecycleOwner.onPause()
        lifecycleOwner.onStop()
        lifecycleOwner.onDestroy()
        floatingView = null
        layoutParams = null
        isShowing = false
        isCollapsed.value = false
    }

    fun isShowing(): Boolean = isShowing

    fun setAlertLevel(level: AlertLevel) {
        if (alertLevelState.value != level) {
            alertLevelState.value = level
        }
    }

    private fun dpToPx(dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density).roundToInt()
    }
}

private class FloatingLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    init {
        lifecycleRegistry.currentState = Lifecycle.State.INITIALIZED
        savedStateRegistryController.performRestore(null)
    }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    fun onCreate() {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun onStart() {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    fun onResume() {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun onPause() {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    fun onStop() {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}
