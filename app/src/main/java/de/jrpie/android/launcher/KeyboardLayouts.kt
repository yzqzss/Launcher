package de.jrpie.android.launcher

/**
 * Text typed with the wrong keyboard layout, e.g. "ntktuhfv" (Latin layout) meant "телеграм",
 * or "еудупкфь" (Russian layout) meant "telegram". See [SearchTransliteration.queryVariants].
 * Korean is handled by [Hangul.fromDubeolsikKeys], since its letters are composed.
 */
object KeyboardLayouts {
    private const val QWERTY = "qwertyuiop[]asdfghjkl;'zxcvbnm,.`"

    /** Characters on the same keys as [QWERTY]; ' ' = no letter on this key */
    private val LAYOUTS = listOf(
        // Russian (ЙЦУКЕН)
        "йцукенгшщзхъфывапролджэячсмитьбюё",
        // Ukrainian
        "йцукенгшщзхїфівапролджєячсмитьбю'",
        // Greek
        " ςερτυθιοπ  ασδφγηξκλ  ζχψωβνμ   ",
        // Hebrew
        "/'קראטוןםפ  שדגכעיחלךף,זסבהנמצתץ ",
        // Arabic
        "ضصثقفغعهخحجدشسيبلاتنمكطئءؤرلاىةوزذ",
    )

    /** the Arabic layout has "لا" on the B key */
    private const val ARABIC_B = "لا"

    private val toNative: List<Map<Char, String>> = LAYOUTS.map { layout ->
        val map = HashMap<Char, String>()
        var i = 0
        var k = 0
        while (k < QWERTY.length && i < layout.length) {
            val key = QWERTY[k]
            if (layout.startsWith(ARABIC_B, i) && key == 'b') {
                map[key] = ARABIC_B; i += 2
            } else {
                if (layout[i] != ' ') map[key] = layout[i].toString()
                i++
            }
            k++
        }
        map
    }

    private val toLatin: List<Map<Char, Char>> = toNative.map { map ->
        map.entries.filter { it.value.length == 1 }.associate { it.value[0] to it.key }
    }

    /** All readings of [text] in other layouts; only those where every character could be converted. */
    fun alternatives(text: String): List<String> {
        val input = text.lowercase()
        if (input.isBlank()) return emptyList()
        val result = LinkedHashSet<String>()
        for (map in toNative) {
            convert(input) { map[it] }?.let { result.add(it) }
        }
        for (map in toLatin) {
            convert(input) { map[it]?.toString() }?.let { result.add(it) }
        }
        result.remove(input)
        return result.toList()
    }

    private inline fun convert(input: String, lookup: (Char) -> String?): String? {
        val sb = StringBuilder()
        var converted = 0
        for (c in input) {
            if (c == ' ') {
                sb.append(c); continue
            }
            val s = lookup(c) ?: return null
            sb.append(s)
            converted++
        }
        return if (converted > 0) sb.toString() else null
    }
}
