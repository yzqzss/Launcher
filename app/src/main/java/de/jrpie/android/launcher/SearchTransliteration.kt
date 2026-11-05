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
import java.util.EnumMap

/**
 * Romanization of app and widget names for search (similar to Kvaesitso's IcuStringNormalizer),
 * so that e.g. "weixin" or "wx" finds 微信, "telegram" finds Телеграм and "kamera" finds カメラ.
 *
 * - Romanization: the table of the dict-base pack if it is installed (so it can be updated
 *   independently of the system), otherwise ICU transliterators on Android 10+ (chosen from the
 *   system languages by default, functionality.search_transliteration).
 * - Optional [DictionaryPacks]: all readings of Chinese characters with several readings
 *   (音乐 → "yinyue" and "yinle"), readings of Japanese words and kanji (設定 → "settei").
 * - Lenient forms of romanizations per script, see [ScriptRules] (shi/si, g/k, zh/j, tlgrm, rama/ram …).
 * - Korean initial consonants (ㅋㅋㅇㅌ → 카카오톡), text typed with the wrong keyboard layout
 *   (zkzkdhxhr → 카카오톡, ntktuhfv → телеграм), German / Scandinavian alternative spellings.
 */
object SearchTransliteration {
    /** Preference value: no transliteration */
    const val DISABLED = "disabled"

    /** Preference value: choose a transliterator from the system languages */
    const val AUTO = ""

    private const val BASE_ID = "Any-Latin;Latin-ASCII"

    /** BGN rules give the usual romanizations of Russian and Greek (Яндекс → Yandeks, not Andeks) */
    private const val PREFERRED_BASE_ID = "ru-ru_Latn/BGN;el-el_Latn/BGN;$BASE_ID"

    private val WORD_SEPARATOR = Regex("[^\\p{L}\\p{N}]+")
    private val SPACES = Regex(" +")

    private const val MIN_LAYOUT_ROMANIZATION_LENGTH = 4

    /** Maximum number of reading combinations per name and language */
    private const val MAX_VARIANTS = 16

    /** Lenient forms are only compared with lenient forms of the same kind. */
    enum class Lenient(val apply: (String) -> String) {
        JAPANESE(ScriptRules::lenientJapanese),
        KOREAN(ScriptRules::lenientKorean),
        CYRILLIC(ScriptRules::lenientCyrillic),
        ABJAD(ScriptRules::lenientAbjad),
        INDIC(ScriptRules::lenientIndic),
    }

    /** Search forms of a name or a query. */
    class Forms(val exact: List<String>, val lenient: Map<Lenient, List<String>>) {
        companion object {
            val EMPTY = Forms(emptyList(), emptyMap())
        }
    }

    /** Whether the system provides ICU transliterators (otherwise the dict-base pack is needed). */
    fun isIcuAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    private fun preference(): String = LauncherPreferences.functionality().searchTransliteration() ?: AUTO

    private fun isEnabled(): Boolean = preference() != DISABLED

    private interface Romanizer {
        fun romanize(text: String): String
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private class IcuRomanizer(id: String) : Romanizer {
        private val transliterator = listOf(id, PREFERRED_BASE_ID, BASE_ID).firstNotNullOf {
            try {
                Transliterator.getInstance(it)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        override fun romanize(text: String): String = transliterator.transliterate(text)
    }

    /**
     * Romanization with the table of the dict-base pack (generated from ICU, so the results are
     * mostly the same): longest match of up to 3 characters, Hangul is computed.
     */
    private class TableRomanizer(private val table: DictionaryPacks.Dictionary) : Romanizer {
        override fun romanize(text: String): String {
            val sb = StringBuilder()
            var doubleNext = false
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                if (cp == 'っ'.code || cp == 'ッ'.code) {
                    doubleNext = true; i++; continue
                }
                if (cp == 'ー'.code) {
                    sb.lastOrNull { it in "aeiou" }?.let { sb.append(it) }
                    i++; continue
                }
                var romanized: String? = null
                var length = Character.charCount(cp)
                if (cp <= 0xFFFF && Hangul.isSyllable(cp.toChar())) {
                    romanized = Hangul.romanize(cp.toChar())
                } else {
                    for (len in minOf(3, text.length - i) downTo 1) {
                        val r = table.words[text.substring(i, i + len)] ?: continue
                        romanized = r[0]; length = len
                        break
                    }
                }
                val r = romanized ?: text.substring(i, i + length)
                if (doubleNext && r.isNotEmpty() && r[0] !in "aeiou") sb.append(r[0])
                doubleNext = false
                if (ScriptRules.isHan(cp)) sb.append(' ').append(r).append(' ') else sb.append(r)
                i += length
            }
            return sb.toString().replace(SPACES, " ").trim()
        }
    }

    private var state: String? = null
    private var romanizer: Romanizer? = null
    private val cache = LruCache<String, String>(4000)
    private val keyCache = LruCache<String, Forms>(2000)

    /** The current romanizer; resets the caches if the settings or the dictionary packs changed. */
    private fun romanizer(): Romanizer? {
        val pref = preference()
        val table = DictionaryPacks.get(Language.BASE)
        val id = when {
            pref == DISABLED -> null
            // an installed table is preferred: it can be updated independently of the system
            table != null -> "table"
            isIcuAvailable() -> icuId(pref)
            else -> null
        }
        val newState = "$id/${DictionaryPacks.generation}"
        if (newState != state) {
            state = newState
            cache.evictAll()
            keyCache.evictAll()
            romanizer = when {
                id == null -> null
                table != null -> TableRomanizer(table)
                isIcuAvailable() -> IcuRomanizer(id)
                else -> null
            }
        }
        return romanizer
    }

    /** @return the romanized text, or null if transliteration is disabled or unavailable */
    @Synchronized
    fun transliterate(text: String): String? {
        val r = romanizer() ?: return null
        return cache.get(text) ?: r.romanize(text).also { cache.put(text, it) }
    }

    /** Additional search keys for a name, e.g. 微信 → "wei xin", "wx". */
    @Synchronized
    fun searchKeys(text: String): Forms {
        if (!isEnabled()) return Forms.EMPTY
        romanizer() // resets the caches if needed
        keyCache.get(text)?.let { return it }

        val romanized = transliterate(text)
        val exact = LinkedHashSet<String>()
        val lenient = EnumMap<Lenient, MutableSet<String>>(Lenient::class.java)
        fun addLenient(kind: Lenient, s: String) {
            lenient.getOrPut(kind) { LinkedHashSet() }.add(kind.apply(s))
        }

        val hasHan = ScriptRules.any(text, ScriptRules::isHan)
        val hasKana = ScriptRules.any(text, ScriptRules::isKana)

        // words separated by spaces (e.g. pinyin): also match the initials
        val spaced = LinkedHashSet<String>()
        if (romanized != null && romanized != text) spaced.add(romanized)
        if (hasHan && romanized != null) {
            DictionaryPacks.get(Language.CHINESE)?.let { spaced.addAll(chineseVariants(text, it)) }
        }
        for (key in spaced) {
            exact.add(key)
            exact.add(initials(key))
        }

        ScriptRules.latinAlternatives(text)?.let { exact.add(it) }

        if (romanized != null) {
            val japanese = ArrayList<String>()
            if (hasKana) japanese.add(romanized)
            DictionaryPacks.get(Language.JAPANESE)?.let { dict ->
                if (hasKana || (hasHan && looksJapanese(text, dict))) {
                    japanese.addAll(japaneseVariants(text, dict))
                }
            }
            for (key in japanese) {
                exact.add(key)
                addLenient(Lenient.JAPANESE, key)
            }
            if (ScriptRules.any(text, ScriptRules::isCyrillic)) addLenient(Lenient.CYRILLIC, romanized)
            if (ScriptRules.any(text, ScriptRules::isAbjad)) addLenient(Lenient.ABJAD, romanized)
            if (ScriptRules.any(text, ScriptRules::isIndic)) addLenient(Lenient.INDIC, romanized)
        }

        if (Hangul.containsSyllables(text)) {
            exact.add(Hangul.initialConsonants(text))
            romanized?.let { addLenient(Lenient.KOREAN, it) }
        }

        exact.remove(text)
        exact.remove("")
        val forms = Forms(
            exact.toList(),
            lenient.mapValues { (_, v) -> v.filter { it.isNotEmpty() } }
        )
        keyCache.put(text, forms)
        return forms
    }

    /** Additional forms of the query to search for. */
    @Synchronized
    fun queryVariants(query: String): Forms {
        if (!isEnabled()) return Forms.EMPTY
        val exact = LinkedHashSet<String>()
        val romanized = transliterate(query)
        romanized?.let { exact.add(it) }

        val latin = (romanized ?: query).lowercase()
        val lenient = Lenient.entries.associateWith { listOf(it.apply(latin)) }

        val trimmed = query.trim()
        Hangul.fromDubeolsikKeys(trimmed)?.let { exact.add(it) }
        for (alternative in KeyboardLayouts.alternatives(trimmed)) {
            exact.add(alternative)
            // e.g. "ntktuhfv" → телеграм → "telegram". Only for longer input: short input
            // can be read in many layouts, and the romanizations of some (e.g. Arabic) are much
            // shorter than what was typed, which would match far too much.
            if (trimmed.length >= MIN_LAYOUT_ROMANIZATION_LENGTH) {
                transliterate(alternative)
                    ?.takeIf { r -> r.count { it.isLetter() } >= trimmed.count { it.isLetter() } }
                    ?.let { exact.add(it) }
            }
        }
        exact.remove(query)
        return Forms(exact.toList(), lenient)
    }

    /** (value, label) pairs for the settings: disabled, automatic and those of the system languages */
    fun options(context: Context): List<Pair<String, String>> {
        val options = mutableListOf(
            DISABLED to context.getString(R.string.settings_functionality_search_transliteration_disabled),
            AUTO to context.getString(R.string.settings_functionality_search_transliteration_auto),
        )
        // the system's transliterators are only used if there is no dict-base table
        if (isIcuAvailable() && DictionaryPacks.get(Language.BASE) == null) {
            options.addAll(localeTransliterators())
        }
        // keep a value chosen earlier selectable
        val current = preference()
        if (options.none { it.first == current }) options.add(current to current)
        return options
    }

    private fun initials(romanized: String): String =
        romanized.split(WORD_SEPARATOR).filter { it.isNotEmpty() }.joinToString("") { it.take(1) }

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
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val length = Character.charCount(cp)
            if (ScriptRules.isHan(cp)) {
                flushOther()
                val readings = dict.characters[cp]
                    ?: listOfNotNull(transliterate(text.substring(i, i + length))?.trim())
                if (readings.isNotEmpty()) parts.add(readings)
            } else {
                other.append(text, i, i + length)
            }
            i += length
        }
        flushOther()
        return combinations(parts, " ")
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
        return covered * 2 >= text.count { ScriptRules.isHan(it.code) }
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
                if (len == 1 && !ScriptRules.isHan(text.codePointAt(i))) break
                flushOther()
                val kanjiReadings =
                    if (len == 1) dict.characters[text.codePointAt(i)].orEmpty() else emptyList()
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

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun icuId(pref: String): String =
        if (pref.isNotBlank()) "$pref;$PREFERRED_BASE_ID" else automaticId()

    private var autoIdLocales: String? = null
    private var autoId: String = PREFERRED_BASE_ID

    /** Like Kvaesitso: the first language or script specific transliterator of the system languages. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun automaticId(): String {
        val tags = Resources.getSystem().configuration.locales.toLanguageTags()
        if (tags != autoIdLocales) {
            autoIdLocales = tags
            autoId = localeTransliterators().firstOrNull()?.first
                ?.let { "$it;$PREFERRED_BASE_ID" } ?: PREFERRED_BASE_ID
        }
        return autoId
    }

    /** (transliterator id, label) for the languages and scripts of the system languages */
    @RequiresApi(Build.VERSION_CODES.Q)
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
