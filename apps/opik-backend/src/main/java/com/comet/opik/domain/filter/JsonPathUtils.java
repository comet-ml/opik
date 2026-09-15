package com.comet.opik.domain.filter;

import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.comet.opik.domain.filter.FilterQueryBuilder.JSONPATH_ROOT;

/**
 * Builds the JSONPath expressions used to address dynamic dictionary fields (typically
 * {@code metadata}) when querying the analytics DB.
 * <p>
 * ClickHouse parses the JSONPath argument of {@code JSON_VALUE} while it analyses the query, before
 * execution starts. An expression it cannot parse therefore aborts the whole statement with
 * {@code BAD_ARGUMENTS: Unable to parse JSONPath} rather than yielding no rows, and there is no
 * runtime option that can soften it. The {@code RETURNING ... NULL ON ERROR} clause used elsewhere in
 * {@link FilterQueryBuilder} for the state DB is MySQL-only syntax and ClickHouse rejects it outright.
 * The path therefore has to be settled while it is being built.
 * <p>
 * Unquoted dot notation only accepts {@code [A-Za-z0-9_]} in a key, so a key holding any other
 * character — a hyphen being by far the most common — cannot be expressed that way. Bracket notation
 * accepts arbitrary characters once quoted and is used for those keys.
 */
@UtilityClass
public class JsonPathUtils {

    private static final Pattern DOT_NOTATION_SEGMENT = Pattern.compile("[A-Za-z0-9_]+");

    /**
     * A subscript that carries path meaning rather than being part of a key: an array index, a
     * wildcard, or a quoted key. ClickHouse accepts either quote style, so both are recognised —
     * treating {@code a["version"].b} as a literal key would resolve a different value than it does
     * today.
     */
    private static final Pattern PATH_SUBSCRIPT = Pattern
            .compile("\\[(?:\\d+|\\*|'(?:[^'\\\\]|\\\\.)*'|\"(?:[^\"\\\\]|\\\\.)*\")]");

    private static final String PATH_SEPARATOR = ".";

    /**
     * Sentinel for "not inside a quoted segment" while scanning; otherwise the delimiter that opened
     * the segment, so a bracket quoted with the other style is not miscounted.
     */
    private static final char NOT_QUOTED = 0;

    /**
     * Resolves a dictionary filter key into a JSONPath for the analytics DB.
     * <p>
     * A key that already carries JSONPath syntax was authored by the caller, so it is assembled
     * exactly as before and only screened for damage. It is deliberately not matched against an
     * allowlist of accepted shapes: ClickHouse accepts constructs such as wildcard indices
     * ({@code version[*]}) that no such list here has enumerated, and rejecting them would silently
     * break filters that work today.
     * <p>
     * Everything else is quoted segment by segment into bracket notation, which can express any
     * character and therefore always parses. Two kinds of key land there:
     * <ul>
     * <li>a plain key using characters dot notation does not support, such as a hyphen. Quoting lets
     * it resolve normally.</li>
     * <li>an authored expression that is malformed. Quoting turns it into a literal key no document
     * carries, so it matches nothing.</li>
     * </ul>
     * In both cases the query runs instead of aborting.
     * <p>
     * A plain key whose every segment is expressible in dot notation keeps producing the exact path it
     * produced before, and the dot keeps its meaning as the segment separator throughout.
     *
     * @param key dictionary key, e.g. {@code environment} or {@code hidden_params.retry-count}
     * @return the JSONPath, e.g. {@code $.environment} or {@code $['hidden_params']['retry-count']}
     */
    public static String toAnalyticsDbJsonPath(@NonNull String key) {
        var segments = key.split("\\.", -1);

        if (isPathExpression(key)) {
            var path = toRootedJsonPath(key);

            return isStructurallySound(path) ? path : toBracketNotation(segments);
        }

        return isExpressibleInDotNotation(segments)
                ? toRootedJsonPath(key)
                : toBracketNotation(segments);
    }

    private static boolean isPathExpression(String key) {
        if (isRooted(key) || key.startsWith("[") || key.startsWith(PATH_SEPARATOR)) {
            return true;
        }

        return hasSubscript(key) && everySubscriptCarriesPathMeaning(key);
    }

    /**
     * A leading {@code $} only roots an expression when what follows continues the path: nothing at
     * all, a separator, or a subscript. A key such as {@code $schema} or {@code $ref} merely begins
     * with the character and is an ordinary key, so it is quoted rather than handed to ClickHouse as
     * an expression it cannot parse.
     */
    private static boolean isRooted(String key) {
        if (!key.startsWith(JSONPATH_ROOT)) {
            return false;
        }

        var afterRoot = key.substring(JSONPATH_ROOT.length());

        return afterRoot.isEmpty() || afterRoot.startsWith(PATH_SEPARATOR) || afterRoot.startsWith("[");
    }

    private static boolean hasSubscript(String key) {
        return key.indexOf('[') >= 0 || key.indexOf(']') >= 0;
    }

    /**
     * Distinguishes {@code version[*]}, where the brackets subscript the key, from
     * {@code feature[beta]}, where they are part of the key itself. A bracket only means subscript if
     * its content is an index, a wildcard or a quoted key; anything else leaves the whole thing a
     * literal key, which is then quoted rather than handed to ClickHouse as path syntax.
     */
    private static boolean everySubscriptCarriesPathMeaning(String key) {
        return !hasSubscript(PATH_SUBSCRIPT.matcher(key).replaceAll(""));
    }

    /**
     * Screens an authored expression for damage that no JSONPath can survive: unbalanced brackets, an
     * unterminated quote, or a trailing separator with nothing after it.
     * <p>
     * This only ever rejects expressions that cannot parse under any grammar, so a working filter
     * cannot be turned into an empty one. It is not a completeness check — an expression that is
     * well-formed here may still be rejected by ClickHouse.
     */
    private static boolean isStructurallySound(String path) {
        if (path.endsWith(PATH_SEPARATOR)) {
            return false;
        }

        var depth = 0;
        var openQuote = NOT_QUOTED;

        for (var i = 0; i < path.length(); i++) {
            var current = path.charAt(i);

            if (openQuote != NOT_QUOTED) {
                if (current == '\\') {
                    i++;
                } else if (current == openQuote) {
                    openQuote = NOT_QUOTED;
                }
                continue;
            }

            switch (current) {
                case '\'', '"' -> openQuote = current;
                case '[' -> depth++;
                case ']' -> depth--;
                default -> {
                }
            }

            if (depth < 0) {
                return false;
            }
        }

        return depth == 0 && openQuote == NOT_QUOTED;
    }

    private static boolean isExpressibleInDotNotation(String[] segments) {
        return Arrays.stream(segments).allMatch(segment -> DOT_NOTATION_SEGMENT.matcher(segment).matches());
    }

    /**
     * Prefixes a key with the root so it reads as a path, e.g. {@code .a} and {@code a} both become
     * {@code $.a}. Whatever syntax the key already carries — subscripts, wildcards, quoted segments —
     * is left exactly as written; only the root is supplied. Shared with
     * {@link com.comet.opik.domain.GroupingQueryBuilder} so the two agree on what a rooted path is.
     */
    public static String toRootedJsonPath(@NonNull String key) {
        if (key.startsWith(JSONPATH_ROOT)) {
            return key;
        }

        if (key.startsWith("[") || key.startsWith(PATH_SEPARATOR)) {
            return "%s%s".formatted(JSONPATH_ROOT, key);
        }

        return "%s%s%s".formatted(JSONPATH_ROOT, PATH_SEPARATOR, key);
    }

    /**
     * Renders {@code a.b-c} as {@code $['a']['b-c']}.
     */
    private static String toBracketNotation(String[] segments) {
        return Arrays.stream(segments)
                .map(JsonPathUtils::quoteSegment)
                .collect(Collectors.joining("", JSONPATH_ROOT, ""));
    }

    private static String quoteSegment(String segment) {
        return "['%s']".formatted(segment.replace("\\", "\\\\").replace("'", "\\'"));
    }
}
