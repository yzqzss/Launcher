package de.jrpie.android.launcher.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.UserHandle
import android.os.UserManager
import de.jrpie.android.launcher.actions.Action
import de.jrpie.android.launcher.actions.AppAction
import de.jrpie.android.launcher.getUserFromId

/**
 * Stores information used to create [de.jrpie.android.launcher.ui.list.apps.AppsRecyclerAdapter] rows.
 */
class DetailedAppInfo(
    private val app: AppInfo,
    private val label: CharSequence,
    private val icon: Drawable,
    private val privateSpace: Boolean,
    private val removable: Boolean = true,
    private val paused: Boolean = false,
) : AbstractDetailedAppInfo {

    /** @param profilePaused whether the profile of the app is paused (quiet mode) */
    constructor(activityInfo: LauncherActivityInfo, private: Boolean, profilePaused: Boolean = false) : this(
        AppInfo(
            activityInfo.applicationInfo.packageName,
            activityInfo.name,
            activityInfo.user.hashCode()
        ),
        activityInfo.label,
        activityInfo.getBadgedIcon(0),
        private,
        // App can be uninstalled iff it is not a system app
        activityInfo.applicationInfo.flags.and(ApplicationInfo.FLAG_SYSTEM) == 0,
        profilePaused || isSuspended(activityInfo.applicationInfo)
    )

    override fun isPaused(): Boolean {
        return paused
    }


    override fun getLabel(): String {
        return label.toString()
    }

    override fun getIcon(context: Context): Drawable {
        return icon
    }

    override fun getRawInfo(): AppInfo {
        return app
    }

    override fun getUser(context: Context): UserHandle {
        return getUserFromId(app.user, context)
    }

    override fun isPrivate(): Boolean {
        return privateSpace
    }

    override fun isRemovable(): Boolean {
        return removable
    }

    override fun getAction(): Action {
        return AppAction(app)
    }


    companion object {
        fun fromAppInfo(appInfo: AppInfo, context: Context): DetailedAppInfo? {
            return appInfo.getLauncherActivityInfo(context)?.let {
                DetailedAppInfo(it, it.user == getPrivateSpaceUser(context), isProfilePaused(it.user, context))
            }
        }

        private fun isSuspended(info: ApplicationInfo): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                    info.flags and ApplicationInfo.FLAG_SUSPENDED != 0

        fun isProfilePaused(user: UserHandle, context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
            val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
            return userManager.isQuietModeEnabled(user)
        }
    }
}