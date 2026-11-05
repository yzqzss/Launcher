package de.jrpie.android.launcher.ui.list

import android.os.Bundle
import de.jrpie.android.launcher.apps.AppFilter
import de.jrpie.android.launcher.ui.UIObjectActivity


/**
 * This abstract class bundles common logic used in [AppListActivity] and [SelectActionActivity].
 * - [AppListActivity] is used to view all apps and edit their settings
 * - [SelectActionActivity] is used to choose an app / intent to be launched
 */
sealed class AbstractListActivity : UIObjectActivity() {
    // TODO: remove this
    abstract val intention: Intention

    // TODO: this should be a view model
    var favoritesVisibility: AppFilter.Companion.AppSetVisibility =
        AppFilter.Companion.AppSetVisibility.VISIBLE
    var privateSpaceVisibility: AppFilter.Companion.AppSetVisibility =
        AppFilter.Companion.AppSetVisibility.VISIBLE
    var hiddenVisibility: AppFilter.Companion.AppSetVisibility =
        AppFilter.Companion.AppSetVisibility.HIDDEN

    // TODO: only needed for [SelectActionActivity]
    var forGesture: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        intent.extras?.let { bundle ->
            @Suppress("deprecation") // required to support API level < 33
            favoritesVisibility = bundle.getSerializable(KEY_FAVORITES_VISIBILITY)
                    as? AppFilter.Companion.AppSetVisibility ?: favoritesVisibility
            @Suppress("deprecation") // required to support API level < 33
            privateSpaceVisibility = bundle.getSerializable(KEY_PRIVATE_SPACE_VISIBILITY)
                    as? AppFilter.Companion.AppSetVisibility ?: privateSpaceVisibility
            @Suppress("deprecation") // required to support API level < 33
            hiddenVisibility = bundle.getSerializable(KEY_HIDDEN_VISIBILITY)
                    as? AppFilter.Companion.AppSetVisibility ?: hiddenVisibility
            forGesture = bundle.getString(KEY_FOR_GESTURE)
        }
    }

    /**
     * Set while a system prompt started from this activity is expected to pause it
     * (the private space unlock prompt, cf. #330), so that it is not closed then.
     */
    var keepOpenOnPause = false
    private var pausedForPrompt = false

    override fun onResume() {
        super.onResume()
        if (pausedForPrompt) {
            // back from the prompt
            pausedForPrompt = false
            keepOpenOnPause = false
        }
    }

    override fun onPause() {
        super.onPause()

        // ensure that the activity closes when an app is launched
        // and when the user navigates to recent apps
        if (keepOpenOnPause) {
            pausedForPrompt = true
        } else if (!isChangingConfigurations) {
            finish()
        }
    }

    companion object {
        const val KEY_FAVORITES_VISIBILITY = "favoritesVisibility"
        const val KEY_PRIVATE_SPACE_VISIBILITY = "privateSpaceVisibility"
        const val KEY_HIDDEN_VISIBILITY = "hiddenVisibility"
        const val KEY_FOR_GESTURE = "forGesture"

        enum class Intention {
            VIEW, // used for [AppListActivity]
            PICK  // used for [SelectActionActivity]
        }
    }
}