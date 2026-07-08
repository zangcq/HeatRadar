package com.example.heatradar.core.monitor

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

    private val lifecycleOwner = FloatingLifecycleOwner()

    fun show() {
        if (isShowing) {
            Log.d("FloatingWindow", "Already showing, skipping")
            return
        }

        val marginPx = dpToPx(16)

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
                    onClose = onClose,
                    onDrag = { dx, dy -> updatePosition(dx, dy) }
                )
            }
        }

        try {
            windowManager.addView(composeView, params)
            floatingView = composeView
            layoutParams = params
            isShowing = true
            Log.d("FloatingWindow", "Floating window added successfully at (${params.x}, ${params.y})")
        } catch (e: Exception) {
            Log.e("FloatingWindow", "Failed to add floating window", e)
            lifecycleOwner.onPause()
            lifecycleOwner.onStop()
            lifecycleOwner.onDestroy()
        }
    }

    private fun updatePosition(dx: Float, dy: Float) {
        val view = floatingView ?: return
        val params = layoutParams ?: return
        params.x += dx.toInt()
        params.y += dy.toInt()
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.e("FloatingWindow", "Failed to update position", e)
        }
    }

    fun hide() {
        if (!isShowing) return
        floatingView?.let {
            try {
                windowManager.removeView(it)
                Log.d("FloatingWindow", "Floating window removed")
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
    }

    fun isShowing(): Boolean = isShowing

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
