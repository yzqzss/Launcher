package de.jrpie.android.launcher.settings.launcher

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PorterDuff
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.TextUtils
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.palette.graphics.Palette
import de.jrpie.android.launcher.*
import de.jrpie.android.launcher.settings.intendedSettingsPause
import kotlinx.android.synthetic.main.settings_launcher.*


/**
 * The [SettingsFragmentLauncher] is a used as a tab in the SettingsActivity.
 *
 * It is used to change themes, select wallpapers ... theme related stuff
 */
class SettingsFragmentLauncher : Fragment(), UIObject {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.settings_launcher, container, false)
    }

    override fun onStart(){
        super<Fragment>.onStart()
        super<UIObject>.onStart()
    }


    override fun applyTheme() {

        setSwitchColor(settings_launcher_switch_screen_timeout, vibrantColor)
        setSwitchColor(settings_launcher_switch_screen_full, vibrantColor)
        setSwitchColor(settings_launcher_switch_auto_launch, vibrantColor)
        setSwitchColor(settings_launcher_switch_auto_keyboard, vibrantColor)
        setSwitchColor(settings_launcher_switch_enable_double, vibrantColor)

        setButtonColor(settings_launcher_button_choose_wallpaper, vibrantColor)
        settings_seekbar_sensitivity.progressDrawable.setColorFilter(vibrantColor, PorterDuff.Mode.SRC_IN);
    }

    override fun setOnClicks() {

        settings_launcher_button_choose_wallpaper.setOnClickListener {
            // https://github.com/LineageOS/android_packages_apps_Trebuchet/blob/6caab89b21b2b91f0a439e1fd8c4510dcb255819/src/com/android/launcher3/views/OptionsPopupView.java#L271
            val intent = Intent(Intent.ACTION_SET_WALLPAPER)
                //.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("com.android.wallpaper.LAUNCH_SOURCE", "app_launched_launcher")
                .putExtra("com.android.launcher3.WALLPAPER_FLAVOR", "focus_wallpaper")
            startActivity(intent);
        }

        settings_launcher_switch_screen_timeout.isChecked = launcherPreferences.getBoolean(PREF_SCREEN_TIMEOUT_DISABLED, false)
        settings_launcher_switch_screen_timeout.setOnCheckedChangeListener { _, isChecked ->  // Toggle screen timeout
            launcherPreferences.edit()
                .putBoolean(PREF_SCREEN_TIMEOUT_DISABLED, isChecked)
                .apply()

            setWindowFlags(activity!!.window)
        }
        settings_launcher_switch_screen_full.isChecked = launcherPreferences.getBoolean(PREF_SCREEN_FULLSCREEN, true)
        settings_launcher_switch_screen_full.setOnCheckedChangeListener { _, isChecked -> // Toggle fullscreen
            launcherPreferences.edit()
                .putBoolean(PREF_SCREEN_FULLSCREEN, isChecked)
                .apply()

            setWindowFlags(activity!!.window)
        }

        settings_launcher_switch_auto_launch.isChecked = launcherPreferences.getBoolean(PREF_SEARCH_AUTO_LAUNCH, false)
        settings_launcher_switch_auto_launch.setOnCheckedChangeListener { _, isChecked -> // Toggle double actions
            launcherPreferences.edit()
                .putBoolean(PREF_SEARCH_AUTO_LAUNCH, isChecked)
                .apply()
        }

        settings_launcher_switch_auto_keyboard.isChecked = launcherPreferences.getBoolean(PREF_SEARCH_AUTO_KEYBOARD, true)
        settings_launcher_switch_auto_keyboard.setOnCheckedChangeListener { _, isChecked -> // Toggle double actions
            launcherPreferences.edit()
                .putBoolean(PREF_SEARCH_AUTO_KEYBOARD, isChecked)
                .apply()
        }

        settings_launcher_switch_enable_double.isChecked = launcherPreferences.getBoolean(PREF_DOUBLE_ACTIONS_ENABLED, false)
        settings_launcher_switch_enable_double.setOnCheckedChangeListener { _, isChecked -> // Toggle double actions
            launcherPreferences.edit()
                .putBoolean(PREF_DOUBLE_ACTIONS_ENABLED, isChecked)
                .apply()

            intendedSettingsPause = true
            activity!!.recreate()
        }

        settings_seekbar_sensitivity.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(p0: SeekBar?, p1: Int, p2: Boolean) {}
                override fun onStartTrackingTouch(p0: SeekBar?) {}
                override fun onStopTrackingTouch(p0: SeekBar?) {
                    launcherPreferences.edit()
                        .putInt(PREF_SLIDE_SENSITIVITY, p0!!.progress * 100 / 4) // scale to %
                        .apply()
                }
            }
        )
    }

    fun resetToCustomTheme(context: Activity) {
        intendedSettingsPause = true
        saveTheme("custom") // TODO: Fix the bug this creates (displays custom theme without chosen img)

    }

    override fun adjustLayout() {

        // Load values into the date-format spinner
        val staticAdapter = ArrayAdapter.createFromResource(
                activity!!, R.array.settings_launcher_time_format_spinner_items,
                android.R.layout.simple_spinner_item )

        staticAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        settings_launcher_format_spinner.adapter = staticAdapter

        settings_launcher_format_spinner.setSelection(launcherPreferences.getInt(PREF_DATE_FORMAT, 0))

        settings_launcher_format_spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                launcherPreferences.edit()
                    .putInt(PREF_DATE_FORMAT, position)
                    .apply()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) { }
        }

        // Load values into the theme spinner
        val staticThemeAdapter = ArrayAdapter.createFromResource(
            activity!!, R.array.settings_launcher_theme_spinner_items,
            android.R.layout.simple_spinner_item )

        staticThemeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        settings_launcher_theme_spinner.adapter = staticThemeAdapter

        val themeInt = when (getSavedTheme(activity!!)) {
            "finn" -> 0
            "dark" -> 1
            else -> 0
        };

        settings_launcher_theme_spinner.setSelection(themeInt)

        settings_launcher_theme_spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                when (position) {
                    0 -> if (getSavedTheme(activity!!) != "finn") resetToDefaultTheme(activity!!)
                    1 -> if (getSavedTheme(activity!!) != "dark") resetToDarkTheme(activity!!)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) { }
        }

        settings_seekbar_sensitivity.progress = launcherPreferences.getInt(PREF_SLIDE_SENSITIVITY, 2) * 4 / 100
    }
}
