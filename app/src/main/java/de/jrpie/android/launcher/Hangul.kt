package de.jrpie.android.launcher

/** Korean helpers for search, see [SearchTransliteration]. Pure algorithms, no data. */
object Hangul {
    private const val SYLLABLE_FIRST = 0xAC00
    private const val SYLLABLE_LAST = 0xD7A3

    private const val CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    private const val JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
    private const val JONG = "\u0000ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"

    private val COMPOUND_VOWELS = mapOf(
        "ㅗㅏ" to 'ㅘ', "ㅗㅐ" to 'ㅙ', "ㅗㅣ" to 'ㅚ',
        "ㅜㅓ" to 'ㅝ', "ㅜㅔ" to 'ㅞ', "ㅜㅣ" to 'ㅟ', "ㅡㅣ" to 'ㅢ',
    )
    private val COMPOUND_FINALS = mapOf(
        "ㄱㅅ" to 'ㄳ', "ㄴㅈ" to 'ㄵ', "ㄴㅎ" to 'ㄶ', "ㄹㄱ" to 'ㄺ', "ㄹㅁ" to 'ㄻ',
        "ㄹㅂ" to 'ㄼ', "ㄹㅅ" to 'ㄽ', "ㄹㅌ" to 'ㄾ', "ㄹㅍ" to 'ㄿ', "ㄹㅎ" to 'ㅀ', "ㅂㅅ" to 'ㅄ',
    )
    private val SPLIT_FINALS = COMPOUND_FINALS.entries.associate { (k, v) -> v to (k[0] to k[1]) }

    /** Standard Korean (Dubeolsik) keyboard layout on a QWERTY keyboard */
    private val DUBEOLSIK = mapOf(
        'q' to 'ㅂ', 'w' to 'ㅈ', 'e' to 'ㄷ', 'r' to 'ㄱ', 't' to 'ㅅ',
        'y' to 'ㅛ', 'u' to 'ㅕ', 'i' to 'ㅑ', 'o' to 'ㅐ', 'p' to 'ㅔ',
        'a' to 'ㅁ', 's' to 'ㄴ', 'd' to 'ㅇ', 'f' to 'ㄹ', 'g' to 'ㅎ',
        'h' to 'ㅗ', 'j' to 'ㅓ', 'k' to 'ㅏ', 'l' to 'ㅣ',
        'z' to 'ㅋ', 'x' to 'ㅌ', 'c' to 'ㅊ', 'v' to 'ㅍ', 'b' to 'ㅠ', 'n' to 'ㅜ', 'm' to 'ㅡ',
        'Q' to 'ㅃ', 'W' to 'ㅉ', 'E' to 'ㄸ', 'R' to 'ㄲ', 'T' to 'ㅆ', 'O' to 'ㅒ', 'P' to 'ㅖ',
    )

    fun isSyllable(c: Char): Boolean = c.code in SYLLABLE_FIRST..SYLLABLE_LAST

    fun containsSyllables(text: String): Boolean = text.any(::isSyllable)

    private val CHO_LATIN = listOf(
        "g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h"
    )
    private val JUNG_LATIN = listOf(
        "a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae",
        "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i"
    )
    private val JONG_LATIN = listOf(
        "", "g", "kk", "gs", "n", "nj", "nh", "d", "l", "lg", "lm", "lb", "ls", "lt", "lp", "lh",
        "m", "b", "bs", "s", "ss", "ng", "j", "ch", "k", "t", "p", "h"
    )

    /** Revised Romanization of one syllable, letter by letter like ICU (톡 → "tog"). */
    fun romanize(c: Char): String {
        if (!isSyllable(c)) return c.toString()
        val index = c.code - SYLLABLE_FIRST
        return CHO_LATIN[index / (21 * 28)] + JUNG_LATIN[index % (21 * 28) / 28] + JONG_LATIN[index % 28]
    }

    /** Initial consonants (초성), e.g. 카카오톡 → ㅋㅋㅇㅌ. Other characters are kept. */
    fun initialConsonants(text: String): String = text.map { c ->
        if (isSyllable(c)) CHO[(c.code - SYLLABLE_FIRST) / (21 * 28)] else c
    }.joinToString("")

    /**
     * Hangul typed with the Korean layout while the keyboard was set to Latin,
     * e.g. "zkzkdhxhr" → 카카오톡. Null if [text] isn't made of such keys only.
     */
    fun fromDubeolsikKeys(text: String): String? {
        if (text.isEmpty()) return null
        val jamo = text.map { c -> DUBEOLSIK[c] ?: DUBEOLSIK[c.lowercaseChar()] ?: return null }
        val result = compose(jamo)
        return result.takeIf { containsSyllables(it) }
    }

    private fun isVowel(c: Char) = JUNG.indexOf(c) >= 0

    /** Composes a sequence of jamo into syllables, like a Korean keyboard does. */
    private fun compose(jamo: List<Char>): String {
        val out = StringBuilder()
        var cho: Char? = null
        var jung: Char? = null
        var jong: Char? = null

        fun flush() {
            if (cho != null && jung != null) {
                val ci = CHO.indexOf(cho!!)
                val ji = JUNG.indexOf(jung!!)
                val ti = jong?.let { JONG.indexOf(it) } ?: 0
                out.append((SYLLABLE_FIRST + (ci * 21 + ji) * 28 + ti).toChar())
            } else {
                cho?.let { out.append(it) }
                jung?.let { out.append(it) }
                jong?.let { out.append(it) }
            }
            cho = null; jung = null; jong = null
        }

        for (j in jamo) {
            if (!isVowel(j)) {
                when {
                    cho == null && jung == null -> cho = j
                    jung == null -> { flush(); cho = j }
                    cho == null -> { flush(); cho = j } // a vowel without initial consonant
                    jong == null ->
                        if (JONG.indexOf(j) > 0) jong = j else { flush(); cho = j }
                    else -> {
                        val compound = COMPOUND_FINALS["$jong$j"]
                        if (compound != null) jong = compound else { flush(); cho = j }
                    }
                }
            } else {
                when {
                    cho != null && jung == null -> jung = j
                    jung != null && jong == null -> {
                        val compound = COMPOUND_VOWELS["$jung$j"]
                        if (compound != null) jung = compound else { flush(); jung = j }
                    }
                    jong != null -> {
                        // the final consonant moves to the next syllable
                        val split = SPLIT_FINALS[jong!!]
                        val next: Char
                        if (split != null) {
                            jong = split.first; next = split.second
                        } else {
                            next = jong!!; jong = null
                        }
                        flush()
                        cho = next; jung = j
                    }
                    else -> { flush(); jung = j }
                }
            }
        }
        flush()
        return out.toString()
    }
}
