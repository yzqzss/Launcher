package de.jrpie.android.launcher

import android.content.Context
import android.content.res.Resources
import android.icu.text.Transliterator
import android.icu.util.ULocale
import android.os.Build
import android.util.LruCache
import androidx.annotation.RequiresApi
import de.jrpie.android.launcher.DictionaryPacks.Language
import de.jrpie.android.launcher.preferences.LauncherPreferences

/**
 * Romanization of app and widget names for search (similar to Kvaesitso's IcuStringNormalizer),
 * so that e.g. "weixin" or "wx" finds 微信, "telegram" finds Телеграм and "kamera" finds カメラ.
 *
 * - ICU transliterators (Android 10+), chosen from the system languages by default
 *   (functionality.search_transliteration).
 * - Optional [DictionaryPacks]: all readings of Chinese characters with several readings
 *   (音乐 → "yinyue" and "yinle"), readings of Japanese words and kanji (設定 → "settei").
 * - Lenient forms of Japanese and Korean romanizations (shi/si, ou/o, g/k, eo/o, …).
 * - Korean: initial consonants (ㅋㅋㅇㅌ → 카카오톡) and Hangul typed on a Latin keyboard
 *   (zkzkdhxhr → 카카오톡).
 */
object SearchTransliteration {
    /** Preference value: no transliteration */
    const val DISABLED = "disabled"

    /** Preference value: choose a transliterator from the system languages */
    const val AUTO = ""

    private const val BASE_ID = "Any-Latin;Latin-ASCII"
    private val WORD_SEPARATOR = Regex("[^\\p{L}\\p{N}]+")

    /** Maximum number of reading combinations per name and language */
    private const val MAX_VARIANTS = 16

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    private fun isEnabled(): Boolean =
        LauncherPreferences.functionality().searchTransliteration() != DISABLED

    /** @return the romanized text, or null if transliteration is disabled or unsupported */
    fun transliterate(text: String): String? {
        if (!isSupported()) return null
        return Impl.transliterate(text)
    }

    /**
     * Search forms of a name or a query.
     * [lenient] forms are only compared with each other, so that e.g. the query "chi"
     * (lenient "ti") doesn't match the pinyin "ti".
     */
    class Forms(val exact: List<String>, val lenient: List<String>) {
        companion object {
            val EMPTY = Forms(emptyList(), emptyList())
        }
    }

    /** Additional search keys for a name, e.g. 微信 → "wei xin", "wx". */
    fun searchKeys(text: String): Forms {
        if (!isEnabled()) return Forms.EMPTY
        if (!isSupported()) {
            return if (Hangul.containsSyllables(text)) {
                Forms(listOf(Hangul.initialConsonants(text)), emptyList())
            } else Forms.EMPTY
        }
        return Impl.searchKeys(text)
    }

    /** Additional forms of the query to search for. */
    fun queryVariants(query: String): Forms {
        if (!isEnabled()) return Forms.EMPTY
        val exact = mutableListOf<String>()
        val lenient = mutableListOf<String>()
        if (isSupported()) {
            Impl.transliterate(query)?.let {
                exact.add(it)
                lenient.add(lenientJapanese(it.lowercase()))
                lenient.add(lenientKorean(it.lowercase()))
            }
        }
        Hangul.fromDubeolsikKeys(query.trim())?.let { exact.add(it) }
        return Forms(exact.distinct(), lenient.distinct())
    }

    /** (value, label) pairs for the settings: disabled, automatic and those of the system languages */
    fun options(context: Context): List<Pair<String, String>> {
        val options = mutableListOf(
            DISABLED to context.getString(R.string.settings_functionality_search_transliteration_disabled),
            AUTO to context.getString(R.string.settings_functionality_search_transliteration_auto),
        )
        if (isSupported()) {
            options.addAll(Impl.localeTransliterators())
        }
        return options
    }

    private fun initials(romanized: String): String =
        romanized.split(WORD_SEPARATOR).filter { it.isNotEmpty() }.joinToString("") { it.take(1) }

    /** Hepburn and Kunrei-shiki spellings and long vowels are treated alike. */
    private fun lenientJapanese(s: String): String = s
        .replace("shi", "si").replace("sh", "sy")
        .replace("chi", "ti").replace("ch", "ty")
        .replace("tsu", "tu").replace("fu", "hu")
        .replace("ji", "zi").replace("j", "zy")
        .replace("ou", "o").replace("oo", "o").replace("uu", "u")
        .replace(DOUBLE_CONSONANT, "$1").replace("'", "")

    private val DOUBLE_CONSONANT = Regex("([kstpgzdbhfmrcy])\\1")

    /** Common variants of Korean romanizations are treated alike. */
    private fun lenientKorean(s: String): String = s
        .replace("eo", "o").replace("eu", "u").replace("ae", "e")
        .replace('g', 'k').replace('d', 't').replace('b', 'p').replace('r', 'l')

    private fun isHan(cp: Int): Boolean =
        Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN

    private fun isKana(cp: Int): Boolean = Character.UnicodeScript.of(cp).let {
        it == Character.UnicodeScript.HIRAGANA || it == Character.UnicodeScript.KATAKANA
    } || cp == 'ー'.code

    /** All combinations of the alternatives of each part, at most [MAX_VARIANTS]. */
    private fun combinations(parts: List<List<String>>, separator: String): List<String> {
        var result = listOf("")
        for (alternatives in parts) {
            val next = ArrayList<String>()
            loop@ for (prefix in result) {
                for (alternative in alternatives) {
                    if (next.size >= MAX_VARIANTS) break@loop
                    next.add(if (prefix.isEmpty()) alternative else prefix + separator + alternative)
                }
            }
            result = next
        }
        return result
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private object Impl {
        private var currentId: String? = null
        private var currentGeneration = -1
        private var transliterator: Transliterator? = null
        private val cache = LruCache<String, String>(4000)
        private val keyCache = LruCache<String, Forms>(2000)

        private var autoIdLocales: String? = null
        private var autoId: String = BASE_ID

        /** Resets the caches if the settings or the dictionary packs changed. */
        private fun update(): Transliterator? {
            val id = transliteratorId()
            if (id != currentId || DictionaryPacks.generation != currentGeneration) {
                currentId = id
                currentGeneration = DictionaryPacks.generation
                cache.evictAll()
                keyCache.evictAll()
                transliterator = id?.let {
                    try {
                        Transliterator.getInstance(it)
                    } catch (_: IllegalArgumentException) {
                        Transliterator.getInstance(BASE_ID)
                    }
                }
            }
            return transliterator
        }

        @Synchronized
        fun transliterate(text: String): String? {
            val t = update() ?: return null
            return cache.get(text) ?: t.transliterate(text).also { cache.put(text, it) }
        }

        @Synchronized
        fun searchKeys(text: String): Forms {
            if (update() == null) return Forms.EMPTY
            keyCache.get(text)?.let { return it }

            val romanized = transliterate(text) ?: return Forms.EMPTY
            val exact = LinkedHashSet<String>()
            val lenient = LinkedHashSet<String>()

            val codePoints = text.codePoints().toArray()
            val hasHan = codePoints.any(::isHan)
            val hasKana = codePoints.any(::isKana)

            // words separated by spaces (e.g. pinyin): also match the initials
            val spaced = LinkedHashSet<String>()
            if (romanized != text) spaced.add(romanized)
            if (hasHan) {
                DictionaryPacks.get(Language.CHINESE)?.let { spaced.addAll(chineseVariants(text, it)) }
            }
            for (key in spaced) {
                exact.add(key)
                exact.add(initials(key))
            }

            val japanese = ArrayList<String>()
            if (hasKana) japanese.add(romanized)
            DictionaryPacks.get(Language.JAPANESE)?.let { dict ->
                if (hasKana || (hasHan && looksJapanese(text, dict))) {
                    japanese.addAll(japaneseVariants(text, dict))
                }
            }
            for (key in japanese) {
                exact.add(key)
                lenient.add(lenientJapanese(key.lowercase()))
            }

            if (Hangul.containsSyllables(text)) {
                lenient.add(lenientKorean(romanized.lowercase()))
                exact.add(Hangul.initialConsonants(text))
            }
            exact.remove(text)
            exact.remove("")
            lenient.remove("")
            return Forms(exact.toList(), lenient.toList()).also { keyCache.put(text, it) }
        }

        /**
         * A name without kana is only read as Japanese if at least half of it consists of
         * Japanese words (e.g. 設定, 天気), otherwise Chinese names (微信) would get lots of
         * meaningless readings.
         */
        private fun looksJapanese(text: String, dict: DictionaryPacks.Dictionary): Boolean {
            var covered = 0
            var i = 0
            while (i < text.length) {
                val len = (minOf(dict.maxWordLength, text.length - i) downTo 2)
                    .firstOrNull { dict.words.containsKey(text.substring(i, i + it)) }
                if (len != null) {
                    covered += len; i += len
                } else {
                    i++
                }
            }
            return covered * 2 >= text.count { isHan(it.code) }
        }

        /** Every reading of characters with several readings: 音乐 → "yin le", "yin yue" */
        private fun chineseVariants(text: String, dict: DictionaryPacks.Dictionary): List<String> {
            val parts = ArrayList<List<String>>()
            val other = StringBuilder()
            fun flushOther() {
                if (other.isNotEmpty()) {
                    transliterate(other.toString())?.trim()?.let { if (it.isNotEmpty()) parts.add(listOf(it)) }
                    other.clear()
                }
            }
            text.codePoints().forEach { cp ->
                if (isHan(cp)) {
                    flushOther()
                    val s = String(Character.toChars(cp))
                    val readings = dict.characters[cp] ?: listOfNotNull(transliterate(s)?.trim())
                    if (readings.isNotEmpty()) parts.add(readings)
                } else {
                    other.appendCodePoint(cp)
                }
            }
            flushOther()
            return combinations(parts, " ")
        }

        /**
         * Japanese readings: longest match against the word list, otherwise the readings of the
         * single kanji. 設定 → "settei"
         */
        private fun japaneseVariants(text: String, dict: DictionaryPacks.Dictionary): List<String> {
            val parts = ArrayList<List<String>>()
            val other = StringBuilder()
            fun flushOther() {
                if (other.isNotEmpty()) {
                    parts.add(listOf(other.toString()))
                    other.clear()
                }
            }
            var i = 0
            while (i < text.length) {
                var matched = false
                for (len in minOf(dict.maxWordLength, text.length - i) downTo 1) {
                    val readings = dict.words[text.substring(i, i + len)] ?: continue
                    if (len == 1 && !isHan(text[i].code)) break
                    flushOther()
                    val kanjiReadings = if (len == 1) dict.characters[text.codePointAt(i)].orEmpty() else emptyList()
                    parts.add((readings + kanjiReadings).distinct())
                    i += len
                    matched = true
                    break
                }
                if (matched) continue
                val cp = text.codePointAt(i)
                val readings = dict.characters[cp]
                if (readings != null) {
                    flushOther()
                    parts.add(readings)
                } else {
                    other.appendCodePoint(cp)
                }
                i += Character.charCount(cp)
            }
            flushOther()
            return combinations(parts, "").mapNotNull { transliterate(it) }
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
            val tags = Resources.getSystem().configuration.locales.toLanguageTags()
            if (tags != autoIdLocales) {
                autoIdLocales = tags
                autoId = localeTransliterators().firstOrNull()?.first?.let { "$it;$BASE_ID" } ?: BASE_ID
            }
            return autoId
        }

        /** (transliterator id, label) for the languages and scripts of the system languages */
        fun localeTransliterators(): List<Pair<String, String>> {
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
