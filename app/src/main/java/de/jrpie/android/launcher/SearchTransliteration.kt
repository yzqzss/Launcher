package de.jrpie.android.launcher

import android.content.Context
import android.content.res.Resources
import android.icu.text.Transliterator
import android.icu.util.ULocale
import android.os.Build
import android.util.LruCache
import androidx.annotation.RequiresApi
import de.jrpie.android.launcher.preferences.LauncherPreferences

/**
 * Romanization of app and widget names for search (similar to Kvaesitso's IcuStringNormalizer),
 * so that e.g. "weixin" or "wx" finds 微信, "telegram" finds Телеграм and "kamera" finds カメラ.
 *
 * Uses ICU transliterators (Android 10+). The transliterator is chosen automatically from the
 * system languages, or set explicitly in the settings (functionality.search_transliteration).
 */
object SearchTransliteration {
    /** Preference value: no transliteration */
    const val DISABLED = "disabled"

    /** Preference value: choose a transliterator from the system languages */
    const val AUTO = ""

    private const val BASE_ID = "Any-Latin;Latin-ASCII"
    private val WORD_SEPARATOR = Regex("[^\\p{L}\\p{N}]+")

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** @return the romanized text, or null if transliteration is disabled or unsupported */
    fun transliterate(text: String): String? {
        if (!isSupported()) return null
        return Impl.transliterate(text)
    }

    /**
     * Search keys derived from [text]: its romanization and the initials of the romanized words
     * (e.g. 微信 → "wei xin", "wx"). Empty if the romanization doesn't change anything.
     */
    fun searchKeys(text: String): List<String> {
        val romanized = transliterate(text) ?: return emptyList()
        if (romanized == text) return emptyList()
        val initials = romanized.split(WORD_SEPARATOR)
            .filter { it.isNotEmpty() }
            .joinToString("") { it.take(1) }
        return listOf(romanized, initials)
    }

    /** (value, label) pairs for the settings: disabled, automatic and those of the system languages */
    fun options(context: Context): List<Pair<String, String>> {
        val options = mutableListOf(
            DISABLED to context.getString(R.string.settings_functionality_search_transliteration_disabled),
            AUTO to context.getString(R.string.settings_functionality_search_transliteration_auto),
        )
        if (isSupported()) {
            options.addAll(Impl.localeOptions())
        }
        return options
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private object Impl {
        private var currentId: String? = null
        private var transliterator: Transliterator? = null
        private val cache = LruCache<String, String>(2000)

        private var autoIdLocales: String? = null
        private var autoId: String = BASE_ID

        @Synchronized
        fun transliterate(text: String): String? {
            val id = transliteratorId() ?: return null
            if (id != currentId) {
                currentId = id
                cache.evictAll()
                transliterator = try {
                    Transliterator.getInstance(id)
                } catch (_: IllegalArgumentException) {
                    Transliterator.getInstance(BASE_ID)
                }
            }
            val t = transliterator ?: return null
            return cache.get(text) ?: t.transliterate(text).also { cache.put(text, it) }
        }

        private fun transliteratorId(): String? {
            val pref = LauncherPreferences.functionality().searchTransliteration() ?: AUTO
            return when {
                pref == DISABLED -> null
                pref.isNotBlank() -> "$pref;$BASE_ID"
                else -> automaticId()
            }
        }

        /** Like Kvaesitso: the first language or script specific transliterator of the system languages. */
        private fun automaticId(): String {
            val locales = Resources.getSystem().configuration.locales
            val tags = locales.toLanguageTags()
            if (tags != autoIdLocales) {
                autoIdLocales = tags
                autoId = localeTransliterators().firstOrNull()?.first?.let { "$it;$BASE_ID" } ?: BASE_ID
            }
            return autoId
        }

        fun localeOptions(): List<Pair<String, String>> = localeTransliterators()

        /** (transliterator id, label) for the languages and scripts of the system languages */
        private fun localeTransliterators(): List<Pair<String, String>> {
            val locales = Resources.getSystem().configuration.locales
            val availableIds = Transliterator.getAvailableIDs().toList()
            val result = linkedMapOf<String, String>()
            val languages = mutableSetOf<String>()
            val scripts = mutableSetOf<String>()
            for (i in 0 until locales.size()) {
                val locale = ULocale.addLikelySubtags(ULocale.forLocale(locales.get(i)))
                if (languages.add(locale.language)) {
                    val prefix = "${locale.language}-${locale.language}_Latn"
                    availableIds.filter { it.startsWith(prefix) }.forEach {
                        result[it] = "${locale.displayLanguage.replaceFirstChar(Char::uppercase)} ($it)"
                    }
                }
                if (locale.script.isNotEmpty() && scripts.add(locale.script)) {
                    val prefix = "${locale.script}-Latn"
                    availableIds.filter { it.startsWith(prefix) }.forEach {
                        result[it] = "${locale.displayScript.replaceFirstChar(Char::uppercase)} ($it)"
                    }
                }
            }
            return result.map { it.key to it.value }
        }
    }
}
