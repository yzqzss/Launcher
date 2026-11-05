package de.jrpie.android.launcher.ui.settings.launcher

import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.os.LocaleList
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.DropDownPreference
import java.util.Locale
import android.provider.Settings
import androidx.preference.PreferenceFragmentCompat
import de.jrpie.android.launcher.R
import de.jrpie.android.launcher.actions.lock.LockMethod
import de.jrpie.android.launcher.actions.openAppsList
import de.jrpie.android.launcher.preferences.LauncherPreferences
import de.jrpie.android.launcher.preferences.theme.ColorTheme
import de.jrpie.android.launcher.setDefaultHomeScreen
import de.jrpie.android.launcher.ui.list.apps.AutoLaunchTouchPrevention
import de.jrpie.android.launcher.ui.widgets.manage.ManageWidgetPanelsActivity
import de.jrpie.android.launcher.ui.widgets.manage.ManageWidgetsActivity


/**
 * The [SettingsFragmentLauncher] is a used as a tab in the SettingsActivity.
 *
 * It is used to change themes, select wallpapers ... theme related stuff
 */
class SettingsFragmentLauncher : PreferenceFragmentCompat() {


    private var sharedPreferencesListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, prefKey ->
            if (prefKey?.startsWith("clock.") == true) {
                updateVisibility()
            }
        }

    private fun updateVisibility() {
        val showSeconds = findPreference<androidx.preference.Preference>(
            LauncherPreferences.clock().keys().showSeconds()
        )
        val timeVisible = LauncherPreferences.clock().timeVisible()
        showSeconds?.isVisible = timeVisible

        val background = findPreference<androidx.preference.Preference>(
            LauncherPreferences.theme().keys().background()
        )
        val lightTheme = LauncherPreferences.theme().colorTheme() == ColorTheme.LIGHT
        background?.isVisible = !lightTheme

        val monochromeIcons = findPreference<androidx.preference.Preference>(
            LauncherPreferences.theme().keys().monochromeIcons()
        )
        monochromeIcons?.isVisible = !LauncherPreferences.theme().colorTheme().forceMonochrome

        val hidePausedApps = findPreference<androidx.preference.Preference>(
            LauncherPreferences.apps().keys().hidePausedApps()
        )
        hidePausedApps?.isVisible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
    }

    override fun onStart() {
        super.onStart()
        LauncherPreferences.getSharedPreferences()
            .registerOnSharedPreferenceChangeListener(sharedPreferencesListener)
    }

    override fun onPause() {
        LauncherPreferences.getSharedPreferences()
            .unregisterOnSharedPreferenceChangeListener(sharedPreferencesListener)
        super.onPause()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        val selectWallpaper = findPreference<androidx.preference.Preference>(
            LauncherPreferences.theme().keys().wallpaper()
        )
        selectWallpaper?.setOnPreferenceClickListener {
            // https://github.com/LineageOS/android_packages_apps_Trebuchet/blob/6caab89b21b2b91f0a439e1fd8c4510dcb255819/src/com/android/launcher3/views/OptionsPopupView.java#L271
            val intent = Intent(Intent.ACTION_SET_WALLPAPER)
                .putExtra("com.android.wallpaper.LAUNCH_SOURCE", "app_launched_launcher")
                .putExtra("com.android.launcher3.WALLPAPER_FLAVOR", "focus_wallpaper")
            startActivity(intent)
            true
        }
        val chooseHomeScreen = findPreference<androidx.preference.Preference>(
            LauncherPreferences.general().keys().chooseHomeScreen()
        )
        chooseHomeScreen?.setOnPreferenceClickListener {
            setDefaultHomeScreen(requireContext(), checkDefault = false)
            true
        }

        val manageWidgets = findPreference<androidx.preference.Preference>(
            LauncherPreferences.widgets().keys().widgets()
        )
        manageWidgets?.setOnPreferenceClickListener {
            startActivity(Intent(requireActivity(), ManageWidgetsActivity::class.java))
            true
        }

        val manageWidgetPanels = findPreference<androidx.preference.Preference>(
            LauncherPreferences.widgets().keys().customPanels()
        )
        manageWidgetPanels?.setOnPreferenceClickListener {
            startActivity(Intent(requireActivity(), ManageWidgetPanelsActivity::class.java))
            true
        }

        val hiddenApps = findPreference<androidx.preference.Preference>(
            LauncherPreferences.apps().keys().hidden()
        )
        hiddenApps?.setOnPreferenceClickListener {
            openAppsList(requireContext(), favorite = false, hidden = true)
            true
        }

        val lockMethod = findPreference<androidx.preference.Preference>(
            LauncherPreferences.actions().keys().lockMethod()
        )

        lockMethod?.setOnPreferenceClickListener {
            LockMethod.chooseMethod(requireContext())
            true
        }

        findPreference<androidx.preference.DropDownPreference>(
            LauncherPreferences.theme().keys().colorTheme()
        )?.apply {
            entries = ColorTheme.entries.filter { x -> x.isAvailable() }
                .map { x -> x.getLabel(requireContext()) }.toTypedArray()
            entryValues = ColorTheme.entries.filter { x -> x.isAvailable() }
                .map { x -> x.name }.toTypedArray()
        }


        findPreference<DropDownPreference>(
            LauncherPreferences.functionality().keys().searchInputLanguage()
        )?.let { setupSearchInputLanguage(it) }

        findPreference<androidx.preference.Preference>(
            LauncherPreferences.functionality().keys().searchAutoLaunchTouchPreventionSystemOverlay()
        )?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !AutoLaunchTouchPrevention.canUseSystemOverlay(requireContext())
            ) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + requireContext().packageName)
                    )
                )
            }
            true
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            lockMethod?.isVisible = false
        }

        updateVisibility()
    }

    /** Entries: keyboard decides, English, the system languages, the current value, "Other…". */
    private fun setupSearchInputLanguage(pref: DropDownPreference) {
        val context = requireContext()
        fun label(tag: String): String = when (tag) {
            "" -> context.getString(R.string.settings_functionality_search_input_language_ime)
            CUSTOM_LANGUAGE -> context.getString(R.string.settings_functionality_search_input_language_custom)
            else -> Locale.forLanguageTag(tag).let { locale ->
                locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) } + " ($tag)"
            }
        }

        fun refresh() {
            val tags = linkedSetOf("", "en")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val system = LocaleList.getDefault()
                for (i in 0 until system.size()) tags.add(system[i].toLanguageTag())
            } else {
                tags.add(Locale.getDefault().toLanguageTag())
            }
            pref.value?.takeIf { it.isNotBlank() }?.let { tags.add(it) }
            val values = tags.toList() + CUSTOM_LANGUAGE
            pref.entryValues = values.toTypedArray()
            pref.entries = values.map { label(it) }.toTypedArray()
            pref.summary = label(pref.value ?: "")
        }
        refresh()

        pref.setOnPreferenceChangeListener { _, newValue ->
            if (newValue == CUSTOM_LANGUAGE) {
                val input = EditText(context).apply {
                    setSingleLine()
                    hint = "zh-CN"
                    setText(pref.value)
                }
                AlertDialog.Builder(context, R.style.AlertDialogCustom)
                    .setTitle(R.string.settings_functionality_search_input_language)
                    .setMessage(R.string.settings_functionality_search_input_language_custom_message)
                    .setView(input)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val tag = Locale.forLanguageTag(input.text.toString().trim()).toLanguageTag()
                        if (tag == "und") {
                            Toast.makeText(
                                context,
                                R.string.settings_functionality_search_input_language_invalid,
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            pref.value = tag
                            refresh()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                false
            } else {
                pref.summary = label(newValue as? String ?: "")
                true
            }
        }
    }

    companion object {
        private const val CUSTOM_LANGUAGE = "custom"
    }
}
