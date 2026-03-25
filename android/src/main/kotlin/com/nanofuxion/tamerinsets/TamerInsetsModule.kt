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
            // Do NOT reset cached values here — that would cause a spurious
            // visible=false emission before the IME insets arrive.
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

    // Pending hide: when we see visible=false while the IME was open, we delay
    // the emission by HIDE_DEBOUNCE_MS. If a visible=true arrives within that
    // window the hide is cancelled (handles the focus-transfer / re-open case).
    private var pendingHideRunnable: Runnable? = null
    private val HIDE_DEBOUNCE_MS = 80L

    /** Invalidates delayed [scheduleKeyboardInsetRefresh] runs after hide or a new show. */
    private var keyboardInsetRefreshGen: Int = 0

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
        // Keep lastImeVisible / lastImeHeight as-is so a reRequestInsets()
        // triggered by focus change doesn't emit a spurious hide event.
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

    /**
     * Same bottom inset as [updateInsets] (system bars + cutout). Single source of truth
     * so JS `keyboard.height` composes with `useInsets().bottom` like iOS (overlap − safe bottom).
     */
    private fun bottomInsetForLayout(insets: WindowInsetsCompat): Int {
        val systemBars = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
        val displayCutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
        return maxOf(systemBars.bottom, displayCutout.bottom)
    }

    /**
     * Keyboard overlap above the bottom layout inset — same units as [emitGlobalEvent] insets.
     */
    private fun keyboardOverlapPx(insets: WindowInsetsCompat): Int {
        val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
        val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        if (!imeVisible || imeBottom <= 0) return 0
        val layoutBottom = bottomInsetForLayout(insets)
        return maxOf(imeBottom - layoutBottom, 0)
    }

    /**
     * Some ROMs (e.g. LineageOS) report stale IME bottom on the first frames after show;
     * switching IME forces a new inset pass and fixes layout — we mimic that by re-reading
     * after short delays when the keyboard just became visible or its height changed.
     */
    private fun scheduleKeyboardInsetRefresh(gen: Int) {
        val delaysMs = longArrayOf(60L, 180L, 400L)
        for (delay in delaysMs) {
            mainHandler.postDelayed({
                if (gen != keyboardInsetRefreshGen) return@postDelayed
                Companion.reRequestInsets()
            }, delay)
        }
    }

    private fun updateKeyboardState(insets: WindowInsetsCompat) {
        val effectiveHeight = keyboardOverlapPx(insets)
        val effectiveVisible = effectiveHeight > 0

        if (effectiveVisible) {
            // Cancel any pending hide and apply the open immediately.
            pendingHideRunnable?.let { mainHandler.removeCallbacks(it) }
            pendingHideRunnable = null
            if (effectiveVisible == lastImeVisible && effectiveHeight == lastImeHeight) return
            lastImeVisible = effectiveVisible
            lastImeHeight = effectiveHeight
            val refreshGen = ++keyboardInsetRefreshGen
            Log.d("TamerInsets", "updateKeyboard: visible=true, height=$effectiveHeight")
            val map = JavaOnlyMap().apply {
                putBoolean("visible", true)
                putDouble("height", effectiveHeight.toDouble())
                putDouble("duration", 0.0)
            }
            emitGlobalEvent("tamer-insets:keyboard", map)
            scheduleKeyboardInsetRefresh(refreshGen)
        } else {
            // Debounce the hide so a rapid hide→show (focus transfer / re-open)
            // doesn't flash the layout into the collapsed state.
            if (!lastImeVisible && lastImeHeight == 0) return  // already hidden
            pendingHideRunnable?.let { mainHandler.removeCallbacks(it) }
            val runnable = Runnable {
                pendingHideRunnable = null
                if (lastImeVisible || lastImeHeight > 0) {
                    keyboardInsetRefreshGen++
                    lastImeVisible = false
                    lastImeHeight = 0
                    Log.d("TamerInsets", "updateKeyboard: visible=false (debounced)")
                    val map = JavaOnlyMap().apply {
                        putBoolean("visible", false)
                        putDouble("height", 0.0)
                        putDouble("duration", 0.0)
                    }
                    emitGlobalEvent("tamer-insets:keyboard", map)
                }
            }
            pendingHideRunnable = runnable
            mainHandler.postDelayed(runnable, HIDE_DEBOUNCE_MS)
        }
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
