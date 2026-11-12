package com.comet.opik.domain.topics;

import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;

/**
 * The string rules the stand (Python) applies and the Java port must reproduce byte for byte: Python's
 * {@code str.strip()}, numpy's text order (Unicode code points, not UTF-16 units as {@link String#compareTo}), and
 * Python's {@code f"{x:.Nf}"} (correct rounding of the exact binary value, ties to even).
 */
@UtilityClass
public class TopicText {

    /** Code point order, as {@code np.unique} sorts a text array. */
    public static final Comparator<String> CODE_POINT_ORDER = TopicText::compareCodePoints;

    public static int compareCodePoints(@NonNull String a, @NonNull String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Boolean.compare(i < a.length(), j < b.length());
    }

    /**
     * Python's {@code str.strip()}: removes leading and trailing characters Python counts as whitespace, which includes
     * the no-break spaces that {@link String#strip()} keeps.
     */
    public static String pythonStrip(@NonNull String text) {
        int start = 0;
        int end = text.length();
        while (start < end) {
            int cp = text.codePointAt(start);
            if (!isPythonSpace(cp)) {
                break;
            }
            start += Character.charCount(cp);
        }
        while (end > start) {
            int cp = text.codePointBefore(end);
            if (!isPythonSpace(cp)) {
                break;
            }
            end -= Character.charCount(cp);
        }
        return text.substring(start, end);
    }

    /** The most characters a topic name keeps: {@code topics.name} is {@code VARCHAR(255)}. */
    public static final int MAX_NAME_LENGTH = 255;

    /**
     * A topic name as stored: {@link #pythonStrip(String)}, then cut to {@value #MAX_NAME_LENGTH} code points so an
     * over-long answer can't fail the save. Names that short never differ from the stand's.
     */
    public static String name(@NonNull String text) {
        var stripped = pythonStrip(text);
        if (stripped.codePointCount(0, stripped.length()) <= MAX_NAME_LENGTH) {
            return stripped;
        }
        return pythonStrip(stripped.substring(0, stripped.offsetByCodePoints(0, MAX_NAME_LENGTH)));
    }

    /** {@code str.isspace()} for one code point: bidi class WS, B or S, or category Zs. */
    static boolean isPythonSpace(int cp) {
        return Character.isWhitespace(cp) || Character.getType(cp) == Character.SPACE_SEPARATOR || cp == 0x85;
    }

    /** Python's {@code f"{value:.{places}f}"}. */
    public static String fixed(double value, int places) {
        var rounded = new BigDecimal(value).setScale(places, RoundingMode.HALF_EVEN);
        var text = rounded.toPlainString();
        boolean negative = value < 0 || (value == 0 && 1 / value < 0);
        return negative && rounded.signum() == 0 ? "-" + text : text;
    }
}
