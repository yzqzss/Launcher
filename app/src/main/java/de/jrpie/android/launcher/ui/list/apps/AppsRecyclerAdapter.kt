package de.jrpie.android.launcher.ui.list.apps

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Service
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.os.Build
import android.widget.Toast
import androidx.annotation.RequiresApi
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import de.jrpie.android.launcher.Application
import de.jrpie.android.launcher.R
import de.jrpie.android.launcher.actions.Action
import de.jrpie.android.launcher.actions.Gesture
import de.jrpie.android.launcher.apps.AbstractDetailedAppInfo
import de.jrpie.android.launcher.apps.AppFilter
import de.jrpie.android.launcher.apps.AppInfo
import de.jrpie.android.launcher.actions.ShortcutAction
import de.jrpie.android.launcher.apps.DetailedAppInfo
import de.jrpie.android.launcher.apps.PinnedShortcutInfo
import de.jrpie.android.launcher.getAppShortcuts
import de.jrpie.android.launcher.isDefaultHomeScreen
import de.jrpie.android.launcher.removeUnusedShortcuts
import de.jrpie.android.launcher.ui.styledAlertDialogBuilder
import de.jrpie.android.launcher.preferences.LauncherPreferences
import de.jrpie.android.launcher.preferences.list.AppNameFormat
import de.jrpie.android.launcher.preferences.list.ListLayout
import de.jrpie.android.launcher.ui.list.AbstractListActivity
import de.jrpie.android.launcher.ui.markPaused
import de.jrpie.android.launcher.ui.transformMonochrome

/**
 * A [RecyclerView] (efficient scrollable list) containing all apps on the users device.
 * The apps details are represented by [AppInfo].
 *
 * @param activity - the activity this is in
 * @param intention - why the list is displayed ("view", "pick")
 * @param forGesture - the action which an app is chosen for (when the intention is "pick")
 */
@SuppressLint("NotifyDataSetChanged")
class AppsRecyclerAdapter(
    val activity: AppCompatActivity,
    val root: View,
    private val intention: AbstractListActivity.Companion.Intention = AbstractListActivity.Companion.Intention.VIEW,
    private val forGesture: String? = "",
    private var appFilter: AppFilter = AppFilter(activity, ""),
    private val layout: ListLayout,
    private val nameFormat: AppNameFormat
) :
    RecyclerView.Adapter<AppsRecyclerAdapter.ViewHolder>() {


    private val apps = (activity.applicationContext as Application).apps
    private val appsListDisplayed: MutableList<AbstractDetailedAppInfo> = mutableListOf()
    private val theme = LauncherPreferences.theme()
    private val colorTheme = theme.colorTheme()
    private val grayscale = colorTheme.monochromeIcons()

    // temporarily disable auto launch
    var disableAutoLaunch: Boolean = false

    // active auto-launch accidental touch prevention, see [AutoLaunchTouchPrevention]
    private var touchPrevention: AutoLaunchTouchPrevention? = null

    init {
        apps.observe(this.activity as AppCompatActivity) {
            updateAppsList()
        }
        updateAppsList()
    }


    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView),
        View.OnClickListener {
        var textView: TextView = itemView.findViewById(R.id.list_apps_row_name)
        var img: ImageView = itemView.findViewById(R.id.list_apps_row_icon)

        override fun onClick(v: View) {
            val rect = Rect()
            img.getGlobalVisibleRect(rect)
            selectItem(bindingAdapterPosition, rect)
        }

        init {
            itemView.setOnClickListener(this)
        }
    }


    override fun onBindViewHolder(viewHolder: ViewHolder, i: Int) {
        var appLabel = appsListDisplayed[i].getCustomLabel(activity)

        val appIcon = appsListDisplayed[i].getIcon(activity)

        viewHolder.img.transformMonochrome(grayscale, colorTheme)
        viewHolder.img.markPaused(appsListDisplayed[i].isPaused())
        viewHolder.img.setImageDrawable(appIcon?.constantState?.newDrawable() ?: appIcon)

        if (layout.useBadgedText) {
            appLabel = activity.packageManager.getUserBadgedLabel(
                appLabel,
                appsListDisplayed[i].getUser(activity)
            ).toString()
        }
        viewHolder.textView.text = nameFormat.format(appLabel)


        // decide when to show the options popup menu about
        if (intention == AbstractListActivity.Companion.Intention.VIEW) {
            viewHolder.textView.setOnLongClickListener {
                showOptionsPopup(
                    viewHolder,
                    appsListDisplayed[i]
                )
            }
            viewHolder.img.setOnLongClickListener {
                showOptionsPopup(
                    viewHolder,
                    appsListDisplayed[i]
                )
            }
            // ensure onClicks are actually caught
            viewHolder.textView.setOnClickListener { viewHolder.onClick(viewHolder.textView) }
            viewHolder.img.setOnClickListener { viewHolder.onClick(viewHolder.img) }
        } else if (intention == AbstractListActivity.Companion.Intention.PICK) {
            // choosing an action for a gesture: long press to choose one of the app's shortcuts
            viewHolder.itemView.setOnLongClickListener { showShortcutPicker(appsListDisplayed[i]) }
            viewHolder.textView.setOnLongClickListener { showShortcutPicker(appsListDisplayed[i]) }
            viewHolder.img.setOnLongClickListener { showShortcutPicker(appsListDisplayed[i]) }
            viewHolder.textView.setOnClickListener { viewHolder.onClick(viewHolder.textView) }
            viewHolder.img.setOnClickListener { viewHolder.onClick(viewHolder.img) }
        }
    }

    /**
     * Lets the user bind one of the app's shortcuts (e.g. "Scan", "Pay") to the gesture
     * an action is being chosen for, instead of opening the app.
     */
    private fun showShortcutPicker(appInfo: AbstractDetailedAppInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return false
        val rawInfo = (appInfo as? DetailedAppInfo)?.getRawInfo() ?: return false
        val gesture = forGesture?.let { Gesture.byId(it) } ?: return false
        val shortcuts = getAppShortcuts(rawInfo, activity)
        if (shortcuts.isEmpty()) {
            Toast.makeText(
                activity,
                if (isDefaultHomeScreen(activity)) {
                    R.string.toast_app_has_no_shortcuts
                } else {
                    R.string.toast_shortcuts_need_default_launcher
                },
                Toast.LENGTH_SHORT
            ).show()
            return true
        }
        val labels = shortcuts.map { (it.shortLabel ?: it.longLabel ?: it.id).toString() }
        styledAlertDialogBuilder(activity)
            .setTitle(appInfo.getCustomLabel(activity))
            .setItems(labels.toTypedArray()) { _, which ->
                val shortcut = shortcuts[which]
                keepShortcutPinned(shortcut)
                Action.setActionForGesture(gesture, ShortcutAction(PinnedShortcutInfo(shortcut)))
                activity.finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        return true
    }

    /**
     * Pins the shortcut, so that it stays available even if the app removes it as a dynamic
     * shortcut (bound shortcuts are also pinned on every start, see [removeUnusedShortcuts]).
     */
    @RequiresApi(Build.VERSION_CODES.N_MR1)
    private fun keepShortcutPinned(shortcut: ShortcutInfo) {
        val launcherApps = activity.getSystemService(Service.LAUNCHER_APPS_SERVICE) as LauncherApps
        try {
            val pinned = launcherApps.getShortcuts(
                LauncherApps.ShortcutQuery().apply {
                    setPackage(shortcut.`package`)
                    setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
                },
                shortcut.userHandle
            )?.map { it.id }.orEmpty()
            // pinShortcuts replaces the pinned shortcuts of the package, so keep the others
            launcherApps.pinShortcuts(shortcut.`package`, (pinned + shortcut.id).distinct(), shortcut.userHandle)
        } catch (_: Exception) {
            // not the default launcher or profile locked: pinned again on the next start
        }
    }

    @Suppress("SameReturnValue")
    private fun showOptionsPopup(
        viewHolder: ViewHolder,
        appInfo: AbstractDetailedAppInfo
    ): Boolean {
        AppContextMenu(appInfo, activity).show(viewHolder.textView)
        return true
    }


    override fun getItemCount(): Int {
        return appsListDisplayed.size
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val view: View = inflater.inflate(layout.layoutResource, parent, false)
        val viewHolder = ViewHolder(view)
        return viewHolder
    }

    fun selectItem(pos: Int, rect: Rect = Rect()) {
        // an explicit selection (e.g. pressing enter) supersedes a pending auto launch
        (touchPrevention as? AutoLaunchTouchPrevention.InLauncher)?.cancel()
        val appInfo = appsListDisplayed.getOrNull(pos) ?: return
        when (intention) {
            AbstractListActivity.Companion.Intention.VIEW -> {
                appInfo.getAction().invoke(activity, rect)
            }

            AbstractListActivity.Companion.Intention.PICK -> {
                activity.finish()
                forGesture ?: return
                val gesture = Gesture.byId(forGesture) ?: return
                Action.setActionForGesture(gesture, appInfo.getAction())
            }
        }
    }

    fun updateAppsList(triggerAutoLaunch: Boolean = false) {
        appsListDisplayed.clear()
        apps.value?.let { appsListDisplayed.addAll(appFilter(it)) }

        if (touchPrevention != null) {
            // The keyboard is closed while touch prevention is active; input that still
            // arrives (e.g. from a hardware keyboard) only extends it, it never launches again.
            if (triggerAutoLaunch) touchPrevention?.extend()
        } else if (triggerAutoLaunch &&
            appsListDisplayed.size == 1
            && intention == AbstractListActivity.Companion.Intention.VIEW
            && !disableAutoLaunch
            && LauncherPreferences.functionality().searchAutoLaunch()
        ) {
            val app = appsListDisplayed[0]
            touchPrevention = AutoLaunchTouchPrevention.launch(
                activity, root, app,
                doLaunch = { app.getAction().invoke(activity) },
                onFinished = { touchPrevention = null }
            )
        }

        notifyDataSetChanged()
    }

    /**
     * The function [setSearchString] is used to search elements within this [RecyclerView].
     */
    fun setSearchString(search: String) {
        appFilter.query = search
        updateAppsList(true)

    }

    fun setFavoritesVisibility(v: AppFilter.Companion.AppSetVisibility) {
        appFilter.favoritesVisibility = v
        updateAppsList()
    }
}
