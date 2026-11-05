import com.ibm.icu.text.Transliterator;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generates dict-base/src/main/assets/dict.txt: the romanization table used on devices without
 * ICU transliterators (Android < 10), see SearchTransliteration.TableRomanizer.
 * The table is computed with ICU4J, using the same transliterator as the launcher on Android 10+.
 *
 * Usage (icu4j from https://repo1.maven.org/maven2/com/ibm/icu/icu4j/):
 *   javac -cp icu4j.jar scripts/dict/BuildBaseTable.java -d /tmp/table
 *   java -cp icu4j.jar:/tmp/table BuildBaseTable dict-base/src/main/assets/dict.txt
 *
 * Format: "T <TAB> text of 1–3 characters <TAB> romanization"; Hangul is computed by the launcher.
 */
public class BuildBaseTable {
    static final Transliterator T = Transliterator.getInstance(
            "ru-ru_Latn/BGN;el-el_Latn/BGN;Any-Latin;Latin-ASCII");
    static final Map<String, String> table = new LinkedHashMap<>();

    static String t(String s) {
        return T.transliterate(s);
    }

    static void put(String key, String value) {
        value = value.trim();
        if (!value.isEmpty() && !value.equals(key)) table.put(key, value);
    }

    static String str(int cp) {
        return new String(Character.toChars(cp));
    }

    static boolean assigned(int cp) {
        return Character.isDefined(cp) && Character.getType(cp) != Character.UNASSIGNED;
    }

    /** single characters */
    static void range(int from, int to) {
        for (int cp = from; cp <= to; cp++) if (assigned(cp)) put(str(cp), t(str(cp)));
    }

    /** characters romanized inside a word (BGN rules romanize some letters differently at the start) */
    static void rangeInWord(int from, int to, String prefix) {
        String p = t(prefix);
        for (int cp = from; cp <= to; cp++) {
            if (!assigned(cp)) continue;
            String r = t(prefix + str(cp));
            put(str(cp), r.startsWith(p) ? r.substring(p.length()) : t(str(cp)));
        }
    }

    /** pairs whose romanization differs from that of the single characters (きゃ, क्, कि …) */
    static void pairs(int from, int to, String seconds) {
        for (int cp = from; cp <= to; cp++) {
            if (!assigned(cp) || !Character.isLetter(cp)) continue;
            for (int i = 0; i < seconds.length(); ) {
                int s = seconds.codePointAt(i);
                i += Character.charCount(s);
                String pair = str(cp) + str(s);
                String r = t(pair).trim();
                if (!r.equals((t(str(cp)) + t(str(s))).trim())) put(pair, r);
            }
        }
    }

    static String marks(int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int cp = from; cp <= to; cp++) {
            int type = Character.getType(cp);
            if (assigned(cp) && (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK)) {
                sb.appendCodePoint(cp);
            }
        }
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        // Latin letters with diacritics (Vietnamese, Polish, …)
        range(0x00C0, 0x024F);
        range(0x1E00, 0x1EFF);
        // Greek, Cyrillic
        rangeInWord(0x0370, 0x03FF, "β");
        rangeInWord(0x1F00, 0x1FFF, "β");
        rangeInWord(0x0400, 0x052F, "б");
        // Armenian, Hebrew, Arabic, Syriac, Thaana
        range(0x0530, 0x07BF);
        range(0x08A0, 0x08FF);
        range(0xFB1D, 0xFDFF);
        range(0xFE70, 0xFEFF);
        // Indic scripts: single characters and consonant + vowel sign / virama
        for (int block = 0x0900; block < 0x0E00; block += 0x80) {
            range(block, block + 0x7F);
            pairs(block, block + 0x7F, marks(block, block + 0x7F));
        }
        // Thai, Lao, Tibetan, Myanmar, Georgian, Ethiopic, Khmer
        range(0x0E00, 0x0FFF);
        range(0x1000, 0x10FF);
        range(0x1200, 0x139F);
        range(0x1780, 0x17FF);
        // Kana, with small kana combinations
        range(0x3041, 0x30FF);
        String small = "ゃゅょぁぃぅぇぉゎャュョァィゥェォヮ";
        pairs(0x3041, 0x30FF, small);
        // Chinese characters (most common reading; dict-zh has the others)
        range(0x3400, 0x4DBF);
        range(0x4E00, 0x9FFF);
        range(0xF900, 0xFAFF);

        try (PrintWriter w = new PrintWriter(args[0], StandardCharsets.UTF_8)) {
            w.print("ulauncher-dict\t1\tbase\n");
            for (Map.Entry<String, String> e : table.entrySet()) {
                if (e.getValue().contains("\t") || e.getValue().contains("\n")) continue;
                w.print("T\t" + e.getKey() + "\t" + e.getValue() + "\n");
            }
        }
        System.out.println(args[0] + ": " + table.size() + " entries");
    }
}
