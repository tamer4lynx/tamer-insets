package com.nanofuxion.tamerinsets

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.util.Log
import kotlin.math.min
import com.lynx.jsbridge.LynxMethod
import com.lynx.jsbridge.LynxModule
import com.lynx.react.bridge.Callback
import com.lynx.react.bridge.JavaOnlyArray
import com.lynx.react.bridge.JavaOnlyMap
import com.lynx.tasm.behavior.LynxContext
import org.json.JSONObject

class TamerInsetsModule(private val appContext: Context) : LynxModule(appContext) {
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
                    instance?.updateInsets(insets, allowDefer = true)
                    instance?.updateKeyboardState(insets)
                }
            }
        }

        /**
         * JSON dictionary for embedding in Lynx `initialData` so the JS bundle
         * reads real insets on its very first render rather than starting at zero
         * and snapping when `tamer-insets:change` arrives ~50–150 ms later.
         * Returns `null` when no host view is attached yet (cold launch path).
         *
         * Does NOT require `instance` to be set — Lynx constructs the module only
         * during `renderTemplateUrl`, so hub callers run before the per-LynxView
         * instance exists. Reads the live `WindowInsetsCompat` directly.
         */
        fun currentInsetsSnapshotJson(): String? {
            val view = hostView ?: return null
            val d = view.context.resources.displayMetrics.density.toDouble()
            val state = computeInsetsStateForSnapshot(view) ?: return null
            return "{\"top\":${state.top / d},\"right\":${state.right / d},\"bottom\":${state.bottom / d},\"left\":${state.left / d}}"
        }

        @SuppressLint("NewApi")
        private fun computeInsetsStateForSnapshot(view: View): InsetsState? {
            val live = ViewCompat.getRootWindowInsets(view)
            val fromLive = live?.let { layoutInsetsForSnapshot(it, view.rootView ?: view) }
            // ViewCompat.getRootWindowInsets returns null in onCreate before the first
            // layout pass (cold launch path). Fall back to WindowManager.currentWindowMetrics
            // which is populated immediately on API 30+.
            val fromMetrics = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                (view.context as? Activity)?.let { act ->
                    try {
                        val metrics = act.windowManager.currentWindowMetrics
                        val compat = WindowInsetsCompat.toWindowInsetsCompat(metrics.windowInsets)
                        layoutInsetsForSnapshot(compat, view.rootView ?: view)
                    } catch (_: Throwable) {
                        null
                    }
                }
            } else null
            return when {
                fromLive != null && fromMetrics != null -> InsetsState(
                    maxOf(fromLive.top, fromMetrics.top),
                    maxOf(fromLive.left, fromMetrics.left),
                    maxOf(fromLive.right, fromMetrics.right),
                    maxOf(fromLive.bottom, fromMetrics.bottom),
                )
                fromLive != null -> fromLive
                fromMetrics != null -> fromMetrics
                else -> null
            }
        }

        private fun layoutInsetsForSnapshot(insets: WindowInsetsCompat, rootView: View): InsetsState {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val typeMask =
                    WindowInsetsCompat.Type.statusBars() or
                        WindowInsetsCompat.Type.displayCutout() or
                        WindowInsetsCompat.Type.navigationBars() or
                        WindowInsetsCompat.Type.captionBar()
                val ins = insets.getInsets(typeMask)
                return InsetsState(ins.top, ins.left, ins.right, ins.bottom)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                val bottom = min(insets.systemWindowInsetBottom, insets.stableInsetBottom)
                @Suppress("DEPRECATION")
                return InsetsState(
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetRight,
                    bottom,
                )
            }
            val visibleRect = Rect()
            rootView.getWindowVisibleDisplayFrame(visibleRect)
            return InsetsState(
                visibleRect.top,
                visibleRect.left,
                rootView.width - visibleRect.right,
                rootView.height - visibleRect.bottom,
            )
        }
    }

    private val density: Double get() = appContext.resources.displayMetrics.density.toDouble()
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

    private var pendingInsetDecreaseRunnable: Runnable? = null
    private val INSET_DECREASE_DEFER_MS = 48L
    private val INSET_DECREASE_THRESHOLD_PX = 2

    private var insetListenerCoalescePosted = false
    private var coalescedListenerInsets: WindowInsetsCompat? = null

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
        pendingInsetDecreaseRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingInsetDecreaseRunnable = null
        insetListenerCoalescePosted = false
        coalescedListenerInsets = null
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
            coalescedListenerInsets = insets
            if (!insetListenerCoalescePosted) {
                insetListenerCoalescePosted = true
                mainHandler.post {
                    insetListenerCoalescePosted = false
                    val toApply = coalescedListenerInsets
                    coalescedListenerInsets = null
                    toApply?.let { updateInsets(it, allowDefer = true) }
                }
            }
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
                updateInsets(it, allowDefer = true)
                updateKeyboardState(it)
            } ?: view.postDelayed({ reRequestInsets() }, 100)
        }
    }

    /**
     * Root layout insets aligned with `react-native-safe-area-context` [SafeAreaUtils]:
     * - API 30+: single [WindowInsetsCompat.getInsets] with
     *   statusBars | displayCutout | navigationBars | captionBar
     * - API 23–29: system window insets; bottom = min(systemWindowInsetBottom, stableInsetBottom)
     *   (avoids treating IME as nav inset)
     * - Below API 23: [View.getWindowVisibleDisplayFrame] on [rootView] (same as RN base path)
     */
    private fun layoutInsetsForHost(insets: WindowInsetsCompat, rootView: View?): InsetsState {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val typeMask =
                WindowInsetsCompat.Type.statusBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.navigationBars() or
                    WindowInsetsCompat.Type.captionBar()
            val ins = insets.getInsets(typeMask)
            return InsetsState(ins.top, ins.left, ins.right, ins.bottom)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            val bottom = min(
                insets.systemWindowInsetBottom,
                insets.stableInsetBottom,
            )
            @Suppress("DEPRECATION")
            return InsetsState(
                insets.systemWindowInsetTop,
                insets.systemWindowInsetLeft,
                insets.systemWindowInsetRight,
                bottom,
            )
        }
        val rv = rootView ?: return InsetsState(0, 0, 0, 0)
        val visibleRect = Rect()
        rv.getWindowVisibleDisplayFrame(visibleRect)
        return InsetsState(
            visibleRect.top,
            visibleRect.left,
            rv.width - visibleRect.right,
            rv.height - visibleRect.bottom,
        )
    }

    /**
     * Same bottom inset as [updateInsets]. Single source of truth so JS `keyboard.height` composes
     * with `useInsets().bottom` like iOS (overlap − safe bottom).
     */
    private fun bottomInsetForLayout(insets: WindowInsetsCompat): Int {
        val root = hostView?.rootView ?: hostView
        return layoutInsetsForHost(insets, root).bottom
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
                putDouble("height", effectiveHeight / density)
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

    private fun mergeInsetStates(primary: InsetsState, vararg others: InsetsState?): InsetsState {
        var t = primary.top
        var l = primary.left
        var r = primary.right
        var b = primary.bottom
        for (o in others) {
            if (o == null) continue
            t = maxOf(t, o.top)
            l = maxOf(l, o.left)
            r = maxOf(r, o.right)
            b = maxOf(b, o.bottom)
        }
        return InsetsState(t, l, r, b)
    }

    /**
     * During FragmentTransaction / stack push animations, [OnApplyWindowInsetsListener] can briefly
     * report zero or reduced system bar insets while [WindowManager.currentWindowMetrics] still
     * reflects the real bars — same idea as max(host, window) on iOS.
     */
    @SuppressLint("NewApi")
    private fun stableInsetsFromWindowMetrics(hostView: View): InsetsState? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val act = hostView.context as? Activity ?: return null
        return try {
            val metrics = act.windowManager.currentWindowMetrics
            val compat = WindowInsetsCompat.toWindowInsetsCompat(metrics.windowInsets)
            val root = hostView.rootView ?: hostView
            layoutInsetsForHost(compat, root)
        } catch (e: Exception) {
            Log.w("TamerInsets", "stableInsetsFromWindowMetrics failed", e)
            null
        }
    }

    private fun updateInsets(insets: WindowInsetsCompat, allowDefer: Boolean = true) {
        val hv = hostView ?: return
        val root = hv.rootView ?: hv
        val fromListener = layoutInsetsForHost(insets, root)
        val fromRoot = ViewCompat.getRootWindowInsets(hv)?.let { layoutInsetsForHost(it, root) }
        val fromMetrics = stableInsetsFromWindowMetrics(hv)
        val state = mergeInsetStates(fromListener, fromRoot, fromMetrics)
        val top = state.top
        val left = state.left
        val right = state.right
        val bottom = state.bottom

        if (allowDefer) {
            val vertDecrease =
                (lastTop >= 0 && top < lastTop - INSET_DECREASE_THRESHOLD_PX) ||
                    (lastBottom >= 0 && bottom < lastBottom - INSET_DECREASE_THRESHOLD_PX)
            if (vertDecrease) {
                pendingInsetDecreaseRunnable?.let { mainHandler.removeCallbacks(it) }
                val r = Runnable {
                    pendingInsetDecreaseRunnable = null
                    hostView?.let { v ->
                        ViewCompat.getRootWindowInsets(v)?.let { updateInsets(it, allowDefer = false) }
                    }
                }
                pendingInsetDecreaseRunnable = r
                mainHandler.postDelayed(r, INSET_DECREASE_DEFER_MS)
                return
            }
        }
        pendingInsetDecreaseRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingInsetDecreaseRunnable = null

        if (top == lastTop && left == lastLeft && right == lastRight && bottom == lastBottom) return
        lastTop = top
        lastLeft = left
        lastRight = right
        lastBottom = bottom
        Log.d("TamerInsets", "updateInsets: top=$top, bottom=$bottom, left=$left, right=$right")
        val map = JavaOnlyMap().apply {
            putDouble("top", top / density)
            putDouble("left", left / density)
            putDouble("right", right / density)
            putDouble("bottom", bottom / density)
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
            putDouble("top", lastTop.coerceAtLeast(0) / density)
            putDouble("left", lastLeft.coerceAtLeast(0) / density)
            putDouble("right", lastRight.coerceAtLeast(0) / density)
            putDouble("bottom", lastBottom.coerceAtLeast(0) / density)
        }
    }

    private fun currentKeyboardMap(): JavaOnlyMap {
        val visible = lastImeVisible && lastImeHeight > 0
        val height = if (visible) lastImeHeight else 0
        return JavaOnlyMap().apply {
            putBoolean("visible", visible)
            putDouble("height", height / density)
            putDouble("duration", 0.0)
        }
    }

    @LynxMethod
    fun getInsets(callback: Callback) {
        mainHandler.post {
            hostView?.let { view ->
                ViewCompat.getRootWindowInsets(view)?.let { updateInsets(it, allowDefer = true) }
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
