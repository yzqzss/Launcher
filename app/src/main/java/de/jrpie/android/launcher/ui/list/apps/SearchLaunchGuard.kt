package de.jrpie.android.launcher.ui.list.apps

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import de.jrpie.android.launcher.R
import de.jrpie.android.launcher.apps.AbstractDetailedAppInfo
import de.jrpie.android.launcher.preferences.LauncherPreferences
import kotlin.math.max
import kotlin.math.min

/**
 * Delays an auto-launch from the search by a short, configurable time and covers the
 * launcher with a touch-swallowing overlay showing a shrinking progress bar.
 *
 * While the guard is active the launcher stays in the foreground, so keystrokes the user is
 * still typing (e.g. "ram" after "teleg" already matched Telegram) land in the launcher's search
 * field instead of the launched app. Every tap on the overlay and every further keystroke
 * extends the guard, up to a configurable maximum.
 *
 * The progress is driven by [Choreographer] frame callbacks and wall-clock time, not by
 * [android.animation.Animator]s, so it is not affected by the system animator duration scale
 * (developer options → animations off).
 */
class SearchLaunchGuard(
    private val activity: Activity,
    val app: AbstractDetailedAppInfo,
    private val launch: () -> Unit,
    private val onFinished: () -> Unit,
) : Choreographer.FrameCallback {

    private val extensionMs: Long
    private val startTime = SystemClock.uptimeMillis()
    private var deadline: Long
    private val hardDeadline: Long

    private var overlay: View? = null
    private var progressView: LaunchGuardProgressView? = null
    var active = false
        private set
    private var finished = false

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = cancel()
    }
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_PAUSE) cancel()
    }

    init {
        val prefs = LauncherPreferences.functionality()
        val duration = durationMs().toLong()
        extensionMs = max(0, prefs.searchAutoLaunchGuardExtension()).toLong()
        val maxDuration = max(duration, prefs.searchAutoLaunchGuardMaxDuration().toLong())
        deadline = startTime + duration
        hardDeadline = startTime + maxDuration
    }

    @SuppressLint("ClickableViewAccessibility")
    fun start() {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: run {
            finish(true); return
        }
        val view = LayoutInflater.from(activity).inflate(R.layout.search_launch_guard, root, false)
        view.findViewById<ImageView>(R.id.search_launch_guard_icon)
            .setImageDrawable(app.getIcon(activity))
        view.findViewById<TextView>(R.id.search_launch_guard_label).text =
            activity.getString(R.string.launch_guard_opening, app.getCustomLabel(activity))
        progressView = view.findViewById(R.id.search_launch_guard_progress)
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_POINTER_DOWN
            ) {
                extend()
            }
            true // swallow every touch
        }
        root.addView(view)
        overlay = view
        active = true

        (activity as? ComponentActivity)?.onBackPressedDispatcher?.addCallback(backCallback)
        (activity as? LifecycleOwner)?.lifecycle?.addObserver(lifecycleObserver)

        Choreographer.getInstance().postFrameCallback(this)
    }

    /** Called on any tap / keystroke while the guard is shown. */
    fun extend() {
        if (!active) return
        deadline = min(hardDeadline, deadline + extensionMs)
    }

    fun cancel() = finish(false)

    override fun doFrame(frameTimeNanos: Long) {
        if (!active) return
        val now = SystemClock.uptimeMillis()
        if (now >= deadline) {
            finish(true)
            return
        }
        val total = (deadline - startTime).coerceAtLeast(1)
        progressView?.fraction = (deadline - now).toFloat() / total
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun finish(doLaunch: Boolean) {
        if (finished) return
        finished = true
        active = false
        Choreographer.getInstance().removeFrameCallback(this)
        backCallback.remove()
        (activity as? LifecycleOwner)?.lifecycle?.removeObserver(lifecycleObserver)
        overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        overlay = null
        onFinished()
        if (doLaunch) launch()
    }

    companion object {
        fun durationMs(): Int =
            max(0, LauncherPreferences.functionality().searchAutoLaunchGuardDuration())
    }
}

/** A plain horizontal bar whose filled part shrinks from right to left. No animators involved. */
class LaunchGuardProgressView @JvmOverloads constructor(
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
