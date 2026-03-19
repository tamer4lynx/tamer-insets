package com.nanofuxion.tamerinsets

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.util.Log
import com.lynx.jsbridge.LynxMethod
import com.lynx.jsbridge.LynxModule
import com.lynx.react.bridge.Callback
import com.lynx.react.bridge.JavaOnlyArray
import com.lynx.react.bridge.JavaOnlyMap
import com.lynx.tasm.behavior.LynxContext
import org.json.JSONObject

class TamerInsetsModule(context: Context) : LynxModule(context) {
    data class InsetsState(
        val top: Int,
        val left: Int,
        val right: Int,
        val bottom: Int,
    )

    companion object {
        @Volatile
        var instance: TamerInsetsModule? = null
            private set

        @Volatile
        private var hostView: View? = null

        fun attachHostView(view: View?) {
            if (hostView !== view) {
                hostView?.let { old ->
                    ViewCompat.setOnApplyWindowInsetsListener(old, null)
                    instance?.removeFocusListener(old)
                }
            }
            Log.i("TamerInsets", "attachHostView: $view (instance is ${if (instance != null) "SET" else "NULL"})")
            hostView = view
            view?.let {
                instance?.setupInsetsListener(it)
            }
        }

        fun reRequestInsets() {
            val view = hostView ?: return
            instance?.resetCachedValues()
            view.post {
                ViewCompat.requestApplyInsets(view)
                ViewCompat.getRootWindowInsets(view)?.let { insets ->
                    instance?.updateInsets(insets)
                    instance?.updateKeyboardState(insets)
                }
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var focusListener: ViewTreeObserver.OnGlobalFocusChangeListener? = null

    private var lastTop: Int = -1
    private var lastLeft: Int = -1
    private var lastRight: Int = -1
    private var lastBottom: Int = -1
    private var lastImeVisible: Boolean = false
    private var lastImeHeight: Int = 0

    init {
        Log.i("TamerInsets", "TamerInsetsModule init")
        instance = this
        mainHandler.post {
            Log.i("TamerInsets", "TamerInsetsModule mainHandler init, hostView is ${if (hostView != null) "SET" else "NULL"}")
            hostView?.let { setupInsetsListener(it) }
        }
    }

    fun resetCachedValues() {
        lastTop = -1
        lastLeft = -1
        lastRight = -1
        lastBottom = -1
        lastImeVisible = false
        lastImeHeight = 0
    }

    fun removeFocusListener(view: View) {
        focusListener?.let { listener ->
            if (view.viewTreeObserver.isAlive) {
                view.viewTreeObserver.removeOnGlobalFocusChangeListener(listener)
            }
            focusListener = null
        }
    }

    fun setupInsetsListener(view: View) {
        resetCachedValues()
        removeFocusListener(view)
        focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, _ ->
            view.postDelayed({ Companion.reRequestInsets() }, 100)
            view.postDelayed({ Companion.reRequestInsets() }, 250)
        }
        view.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            updateInsets(insets)
            updateKeyboardState(insets)
            insets
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.post { reRequestInsets() }
                v.postDelayed({ reRequestInsets() }, 50)
            }
            override fun onViewDetachedFromWindow(v: View) {}
        })
        view.post {
            ViewCompat.requestApplyInsets(view)
            ViewCompat.getRootWindowInsets(view)?.let {
                updateInsets(it)
                updateKeyboardState(it)
            } ?: view.postDelayed({ reRequestInsets() }, 100)
        }
    }

    private fun updateKeyboardState(insets: WindowInsetsCompat) {
        val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
        val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        val effectiveVisible = imeVisible && imeHeight > 0
        val effectiveHeight = if (effectiveVisible) imeHeight else 0
        if (effectiveVisible == lastImeVisible && effectiveHeight == lastImeHeight) return
        lastImeVisible = effectiveVisible
        lastImeHeight = effectiveHeight
        Log.d("TamerInsets", "updateKeyboard: visible=$effectiveVisible, height=$effectiveHeight")
        val map = JavaOnlyMap().apply {
            putBoolean("visible", effectiveVisible)
            putDouble("height", effectiveHeight.toDouble())
            putDouble("duration", 0.0)
        }
        emitGlobalEvent("tamer-insets:keyboard", map)
    }

    private fun updateInsets(insets: WindowInsetsCompat) {
        val systemBars = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
        val displayCutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
        val top = maxOf(systemBars.top, displayCutout.top)
        val left = maxOf(systemBars.left, displayCutout.left)
        val right = maxOf(systemBars.right, displayCutout.right)
        val bottom = maxOf(systemBars.bottom, displayCutout.bottom)
        if (top == lastTop && left == lastLeft && right == lastRight && bottom == lastBottom) return
        lastTop = top
        lastLeft = left
        lastRight = right
        lastBottom = bottom
        Log.d("TamerInsets", "updateInsets: top=$top, bottom=$bottom, left=$left, right=$right")
        val map = JavaOnlyMap().apply {
            putDouble("top", top.toDouble())
            putDouble("left", left.toDouble())
            putDouble("right", right.toDouble())
            putDouble("bottom", bottom.toDouble())
        }
        emitGlobalEvent("tamer-insets:change", map)
    }

    private fun emitGlobalEvent(name: String, map: JavaOnlyMap) {
        mainHandler.post {
            val view = hostView ?: return@post
            if (!view.isAttachedToWindow) return@post
            val lynxView = view as? com.lynx.tasm.LynxView ?: return@post
            val lynxContext = lynxView.lynxContext ?: return@post
            try {
                val payload = JSONObject().apply {
                    if (map.hasKey("top")) put("top", map.getDouble("top"))
                    if (map.hasKey("left")) put("left", map.getDouble("left"))
                    if (map.hasKey("right")) put("right", map.getDouble("right"))
                    if (map.hasKey("bottom")) put("bottom", map.getDouble("bottom"))
                    if (map.hasKey("visible")) put("visible", map.getBoolean("visible"))
                    if (map.hasKey("height")) put("height", map.getDouble("height"))
                    if (map.hasKey("duration")) put("duration", map.getDouble("duration"))
                }.toString()
                val params = JavaOnlyArray().apply {
                    pushMap(JavaOnlyMap().apply { putString("payload", payload) })
                }
                lynxContext.sendGlobalEvent(name, params)
            } catch (e: Exception) {
                Log.w("TamerInsets", "emitGlobalEvent failed: $name", e)
            }
        }
    }

    private fun currentInsetsMap(): JavaOnlyMap {
        return JavaOnlyMap().apply {
            putDouble("top", lastTop.coerceAtLeast(0).toDouble())
            putDouble("left", lastLeft.coerceAtLeast(0).toDouble())
            putDouble("right", lastRight.coerceAtLeast(0).toDouble())
            putDouble("bottom", lastBottom.coerceAtLeast(0).toDouble())
        }
    }

    private fun currentKeyboardMap(): JavaOnlyMap {
        val visible = lastImeVisible && lastImeHeight > 0
        val height = if (visible) lastImeHeight else 0
        return JavaOnlyMap().apply {
            putBoolean("visible", visible)
            putDouble("height", height.toDouble())
            putDouble("duration", 0.0)
        }
    }

    @LynxMethod
    fun getInsets(callback: Callback) {
        mainHandler.post {
            hostView?.let { view ->
                ViewCompat.getRootWindowInsets(view)?.let { updateInsets(it) }
            }
            val map = currentInsetsMap()
            try {
                callback.invoke(map)
            } catch (e: Exception) {
                Log.w("TamerInsets", "getInsets callback.invoke failed", e)
            }
        }
    }

    @LynxMethod
    fun getKeyboard(callback: Callback) {
        mainHandler.post {
            hostView?.let { view ->
                ViewCompat.getRootWindowInsets(view)?.let { updateKeyboardState(it) }
            }
            val map = currentKeyboardMap()
            try {
                callback.invoke(map)
            } catch (e: Exception) {
                Log.w("TamerInsets", "getKeyboard callback.invoke failed", e)
            }
        }
    }
}
