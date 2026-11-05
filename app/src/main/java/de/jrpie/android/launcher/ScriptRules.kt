package de.jrpie.android.launcher

/**
 * Script detection and spelling rules used by [SearchTransliteration] and [FilterHelper].
 * Pure functions on code point ranges (Character.UnicodeScript needs API 24).
 */
object ScriptRules {

    fun isHan(cp: Int): Boolean = cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF ||
            cp in 0xF900..0xFAFF || cp in 0x20000..0x3134F || cp == 0x3007

    fun isKana(cp: Int): Boolean = cp in 0x3041..0x30FF || cp in 0x31F0..0x31FF || cp in 0xFF66..0xFF9F

    fun isCyrillic(cp: Int): Boolean = cp in 0x0400..0x052F || cp in 0x1C80..0x1C8F ||
            cp in 0x2DE0..0x2DFF || cp in 0xA640..0xA69F

    /** Scripts that usually don't write short vowels: Hebrew, Arabic (incl. Persian, Urdu), Syriac, Thaana */
    fun isAbjad(cp: Int): Boolean = cp in 0x0590..0x07BF || cp in 0x0860..0x08FF ||
            cp in 0xFB1D..0xFDFF || cp in 0xFE70..0xFEFF

    /** Brahmic scripts of India and Sri Lanka (Devanagari … Sinhala) */
    fun isIndic(cp: Int): Boolean = cp in 0x0900..0x0DFF

    fun any(text: String, predicate: (Int) -> Boolean): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (predicate(cp)) return true
            i += Character.charCount(cp)
        }
        return false
    }

    /**
     * Folds letters that are written differently but should be found alike.
     * Applied to names and queries before comparing (see [FilterHelper]).
     */
    fun fold(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                // Latin letters NFKD doesn't decompose
                'ß' -> sb.append("ss")
                'æ' -> sb.append("ae")
                'œ' -> sb.append("oe")
                'þ' -> sb.append("th")
                'ø' -> sb.append('o')
                'ł' -> sb.append('l')
                'đ', 'ð' -> sb.append('d')
                'ı' -> sb.append('i')
                'ħ' -> sb.append('h')
                'ŧ' -> sb.append('t')
                // Arabic and Persian variants
                'ى', 'ی', 'ې', 'ے' -> sb.append('ي')
                'ک' -> sb.append('ك')
                'ة', 'ۀ', 'ە' -> sb.append('ه')
                'ٱ' -> sb.append('ا')
                'ـ' -> {} // tatweel
                // Hebrew final letters
                'ך' -> sb.append('כ')
                'ם' -> sb.append('מ')
                'ן' -> sb.append('נ')
                'ף' -> sb.append('פ')
                'ץ' -> sb.append('צ')
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** German and Scandinavian alternative spellings: Müller → Mueller, Ålesund → Aalesund */
    fun latinAlternatives(text: String): String? {
        if (text.none { it in "äöüÄÖÜåÅøØ" }) return null
        return text.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
            .replace("Ä", "Ae").replace("Ö", "Oe").replace("Ü", "Ue")
            .replace("å", "aa").replace("Å", "Aa").replace("ø", "oe").replace("Ø", "Oe")
    }

    private val DOUBLE_CONSONANT = Regex("([bcdfghjklmnpqrstvwxyz])\\1")
    private val VOWELS = Regex("[aeiouy]")
    private val NON_LETTERS = Regex("[^a-z ]")

    /** Hepburn and Kunrei-shiki spellings, long vowels and double consonants are treated alike. */
    fun lenientJapanese(s: String): String = s.lowercase()
        .replace("shi", "si").replace("sh", "sy")
        .replace("chi", "ti").replace("ch", "ty")
        .replace("tsu", "tu").replace("fu", "hu")
        .replace("ji", "zi").replace("j", "zy")
        .replace("ou", "o").replace("oo", "o").replace("uu", "u")
        .replace(DOUBLE_CONSONANT, "$1").replace("'", "")

    /** Common variants of Korean romanizations are treated alike. */
    fun lenientKorean(s: String): String = s.lowercase()
        .replace("eo", "o").replace("eu", "u").replace("ae", "e")
        .replace('g', 'k').replace('d', 't').replace('b', 'p').replace('r', 'l')

    /**
     * Romanizations of Cyrillic differ a lot (ж zh/j, х kh/h/x, ц ts/c, щ shch/sch, ю yu/ju/iu …),
     * reduce them to a common form.
     */
    fun lenientCyrillic(s: String): String = s.lowercase()
        .replace(NON_LETTERS, "")
        .replace("shch", "s").replace("sch", "s").replace("sh", "s")
        .replace("tch", "c").replace("ch", "c").replace("ts", "c").replace("tz", "c")
        .replace("zh", "z").replace("kh", "h").replace("x", "ks").replace('w', 'v')
        .replace('j', 'y').replace("ye", "e").replace("yo", "e").replace('y', 'i')
        .replace(DOUBLE_CONSONANT, "$1")

    /**
     * Abjads don't write short vowels (تلغرام → "tlghram") and use w / y (و ي) for long vowels,
     * so only the other consonants are compared, after merging common digraphs and letters
     * missing in Arabic: "telegram" and "tlghram" both become "tlgrm", "whatsapp" and واتساب "tsb".
     */
    fun lenientAbjad(s: String): String = s.lowercase()
        .replace(NON_LETTERS, "")
        .replace("gh", "g").replace("kh", "k").replace("sh", "s").replace("th", "t")
        .replace("dh", "d").replace("ph", "f").replace("wh", "w")
        .replace('q', 'k').replace('c', 'k').replace('p', 'b').replace('v', 'f')
        .replace(ABJAD_VOWELS, "")
        .replace(DOUBLE_CONSONANT, "$1")

    private val ABJAD_VOWELS = Regex("[aeiouwy]")

    /** Indic romanizations keep the inherent vowel (राम → "rama"), which is usually not typed. */
    fun lenientIndic(s: String): String = s.lowercase()
        .replace(NON_LETTERS, " ")
        .replace("aa", "a").replace("ee", "i").replace("oo", "u").replace('w', 'v')
        .split(' ').filter { it.isNotEmpty() }
        .joinToString("") { if (it.length > 2 && it.endsWith('a')) it.dropLast(1) else it }
}
