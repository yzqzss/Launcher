package de.jrpie.android.launcher.ui.list.apps

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.AttributeSet
import android.view.Choreographer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import de.jrpie.android.launcher.R
import de.jrpie.android.launcher.apps.AbstractDetailedAppInfo
import de.jrpie.android.launcher.preferences.LauncherPreferences
import kotlin.math.max
import kotlin.math.min

/**
 * Auto-Launch Accidental Touch Prevention.
 *
 * When the search auto-launches its single match, the user is often still typing
 * (e.g. "teleg" already matches Telegram while "ram" is still being typed). Those taps
 * must not hit the launched app. This blocks touches for a short, configurable time
 * (extended by every tap, up to a maximum) and shows a progress bar shrinking from
 * right to left. The keyboard is closed as soon as the auto launch triggers.
 *
 * Two modes:
 *  - [InLauncher] (no permission needed): the launcher stays in the foreground, covered
 *    by a full-screen view that swallows touches; the app is opened when the time is up.
 *  - [SystemOverlay] (needs "display over other apps"): the app is opened immediately and
 *    a floating window covers the area where the keyboard was, on top of the app.
 *
 * Timing uses [Choreographer] frame callbacks and [SystemClock.uptimeMillis], not
 * [android.animation.Animator]s, so it is unaffected by the system animation scale
 * (developer options → animations off).
 */
sealed class AutoLaunchTouchPrevention(
    protected val context: Context,
    val app: AbstractDetailedAppInfo,
    private val onFinished: () -> Unit,
) : Choreographer.FrameCallback {

    private val extensionMs: Long
    private val startTime = SystemClock.uptimeMillis()
    private var deadline: Long
    private val hardDeadline: Long

    private var progressView: ProgressView? = null
    private var finished = false

    init {
        val prefs = LauncherPreferences.functionality()
        val duration = durationMs().toLong()
        extensionMs = max(0, prefs.searchAutoLaunchTouchPreventionExtension()).toLong()
        val maxDuration =
            max(duration, prefs.searchAutoLaunchTouchPreventionMaxDuration().toLong())
        deadline = startTime + duration
        hardDeadline = startTime + maxDuration
    }

    /** Inflates the blocking view: icon, "Opening …" and progress bar. Every touch extends. */
    @SuppressLint("ClickableViewAccessibility")
    protected fun createView(parent: ViewGroup?): View {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.auto_launch_touch_prevention, parent, false)
        view.findViewById<ImageView>(R.id.auto_launch_touch_prevention_icon)
            .setImageDrawable(app.getIcon(context))
        view.findViewById<TextView>(R.id.auto_launch_touch_prevention_label).text =
            context.getString(R.string.auto_launch_touch_prevention_opening, app.getCustomLabel(context))
        progressView = view.findViewById(R.id.auto_launch_touch_prevention_progress)
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_POINTER_DOWN
            ) {
                extend()
            }
            true // swallow every touch
        }
        return view
    }

    protected fun startTimer() {
        Choreographer.getInstance().postFrameCallback(this)
    }

    /** Called on every tap while active. */
    fun extend() {
        if (finished) return
        deadline = min(hardDeadline, deadline + extensionMs)
    }

    fun cancel() = finish(timeUp = false)

    override fun doFrame(frameTimeNanos: Long) {
        if (finished) return
        val now = SystemClock.uptimeMillis()
        if (now >= deadline) {
            finish(timeUp = true)
            return
        }
        val total = (deadline - startTime).coerceAtLeast(1)
        progressView?.fraction = (deadline - now).toFloat() / total
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun finish(timeUp: Boolean) {
        if (finished) return
        finished = true
        Choreographer.getInstance().removeFrameCallback(this)
        removeView()
        onFinished()
        onEnd(timeUp)
    }

    protected abstract fun removeView()
    protected abstract fun onEnd(timeUp: Boolean)

    /** Launcher stays in front, covered by a full-screen view; app opens when time is up. */
    class InLauncher(
        private val activity: Activity,
        app: AbstractDetailedAppInfo,
        private val launch: () -> Unit,
        onFinished: () -> Unit,
    ) : AutoLaunchTouchPrevention(activity, app, onFinished) {

        private var view: View? = null
        private val backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = cancel()
        }
        private val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) cancel()
        }

        /** @return false if the view could not be shown */
        fun start(): Boolean {
            val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return false
            view = createView(root).also { root.addView(it) }
            (activity as? ComponentActivity)?.onBackPressedDispatcher?.addCallback(backCallback)
            (activity as? LifecycleOwner)?.lifecycle?.addObserver(lifecycleObserver)
            startTimer()
            return true
        }

        override fun removeView() {
            backCallback.remove()
            (activity as? LifecycleOwner)?.lifecycle?.removeObserver(lifecycleObserver)
            view?.let { (it.parent as? ViewGroup)?.removeView(it) }
            view = null
        }

        override fun onEnd(timeUp: Boolean) {
            if (timeUp) launch()
        }
    }

    /** App opens immediately; a floating window covers the former keyboard area on top of it. */
    class SystemOverlay(
        context: Context,
        app: AbstractDetailedAppInfo,
        onFinished: () -> Unit,
    ) : AutoLaunchTouchPrevention(context.applicationContext, app, onFinished) {

        private val windowManager =
            this.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        private var view: View? = null

        /** @return false if the window could not be shown */
        fun start(heightPx: Int): Boolean {
            @Suppress("DEPRECATION")
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                heightPx,
                type,
                // not focusable: the launched app keeps input focus, we only eat touches
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM
                // no window animation: must work with animations disabled and not be one
                windowAnimations = 0
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setFitInsetsTypes(0)
                }
            }
            val v = createView(null)
            return try {
                windowManager.addView(v, params)
                view = v
                startTimer()
                true
            } catch (_: Exception) {
                false
            }
        }

        override fun removeView() {
            view?.let {
                try {
                    windowManager.removeView(it)
                } catch (_: Exception) {
                }
            }
            view = null
        }

        override fun onEnd(timeUp: Boolean) {}
    }

    companion object {
        fun durationMs(): Int =
            max(0, LauncherPreferences.functionality().searchAutoLaunchTouchPreventionDuration())

        fun canUseSystemOverlay(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

        /**
         * Hides the keyboard and launches [app], protected according to the settings.
         * @return the active prevention, or null if none is active (app already launched)
         */
        fun launch(
            activity: Activity,
            anchor: View,
            app: AbstractDetailedAppInfo,
            doLaunch: () -> Unit,
            onFinished: () -> Unit,
        ): AutoLaunchTouchPrevention? {
            // measure the keyboard before closing it
            val imeHeight = ViewCompat.getRootWindowInsets(anchor)
                ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
            hideKeyboard(activity, anchor)

            if (durationMs() <= 0) {
                doLaunch(); return null
            }

            if (LauncherPreferences.functionality().searchAutoLaunchTouchPreventionSystemOverlay()
                && canUseSystemOverlay(activity)
            ) {
                val screenHeight = activity.resources.displayMetrics.heightPixels
                val extra = (48 * activity.resources.displayMetrics.density).toInt()
                val height = if (imeHeight > 0) imeHeight + extra else (screenHeight * 0.45f).toInt()
                val overlay = SystemOverlay(activity, app, onFinished)
                doLaunch()
                return if (overlay.start(min(height, screenHeight))) overlay else null
            }

            val inLauncher = InLauncher(activity, app, doLaunch, onFinished)
            if (inLauncher.start()) return inLauncher
            doLaunch()
            return null
        }

        private fun hideKeyboard(activity: Activity, anchor: View) {
            val imm = activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow((activity.currentFocus ?: anchor).windowToken, 0)
            activity.currentFocus?.clearFocus()
        }
    }

    /** A plain horizontal bar whose filled part shrinks from right to left. No animators. */
    class ProgressView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null
    ) : View(context, attrs) {

        var fraction: Float = 1f
            set(value) {
                field = value.coerceIn(0f, 1f)
                invalidate()
            }

        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x44FFFFFF }
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val radius = height / 2f
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, radius, radius, trackPaint)
            rect.right = width * fraction
            if (rect.right > 0f) canvas.drawRoundRect(rect, radius, radius, barPaint)
        }
    }
}
