package com.comet.opik.domain.evaluators;

import lombok.experimental.UtilityClass;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.text.Normalizer;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Resolves a unique automation rule name by appending a numeric suffix on collision (OPIK-7371).
 * <p>
 * Rule names are not enforced to be unique at the DB layer (existing installs already contain
 * collisions). Instead, when a requested name already exists within the same scope we auto-append
 * {@code -1}, {@code -2}, ... so rules remain distinguishable in the UI without breaking existing data.
 */
@UtilityClass
class AutomationRuleNames {

    // Matches automation_rules.name VARCHAR(150).
    private static final int MAX_NAME_LENGTH = 150;

    // Combining marks left behind by NFD decomposition. Compiled once: canonicalKey runs per candidate on
    // every save, so an inline replaceAll would recompile the pattern on each comparison.
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}");

    // Longest suffix truncateToFit can append: "-" + Integer.MAX_VALUE (2147483647) = 11 chars, plus one
    // more for its surrogate-pair back-off, which can shorten a candidate's base by another char. The LIKE
    // search prefix must be capped at MAX_NAME_LENGTH minus this reserve, or a suffixed candidate whose
    // base was truncated to make room would no longer share the prefix and re-runs would regenerate its
    // name (storing a duplicate).
    private static final int MAX_PREFIX_LENGTH = MAX_NAME_LENGTH - 12;

    /**
     * Returns {@code requestedName} if it is free within {@code existingNames}, otherwise the same name
     * with the smallest free {@code -N} suffix (starting at 1). The base name counts as index 0, so the
     * first collision yields {@code name-1}.
     * <p>
     * Comparison is case- and accent-insensitive to approximate MySQL's {@code utf8mb4_unicode_ci}
     * collation (so {@code Hallucination}/{@code hallucination} and {@code Café}/{@code Cafe} collide),
     * and each candidate is checked as the actual (possibly truncated) string that would be stored, so
     * names near the 150-char column limit still resolve to distinct values.
     *
     * @param requestedName the name the user asked for
     * @param existingNames names already present in the target scope
     */
    static String generateUniqueName(String requestedName, Collection<String> existingNames) {
        if (StringUtils.isBlank(requestedName) || CollectionUtils.isEmpty(existingNames)) {
            return requestedName;
        }

        Set<String> taken = new HashSet<>();
        for (String name : existingNames) {
            if (name != null) {
                taken.add(canonicalKey(name));
            }
        }

        if (!taken.contains(canonicalKey(requestedName))) {
            return requestedName;
        }

        // Probe successive suffixes and return the first candidate that is actually free. Checking the
        // final string (after any truncation) guarantees we never regenerate an existing name.
        for (int suffix = 1;; suffix++) {
            String candidate = truncateToFit(requestedName, suffix);
            if (!taken.contains(canonicalKey(candidate))) {
                return candidate;
            }
        }
    }

    /**
     * Builds the escaped prefix to feed to the {@code name LIKE concat(?, '%') ESCAPE '!'} lookup in
     * {@link AutomationRuleDAO#findCandidateNames}. Trailing spaces are stripped (MySQL PAD SPACE
     * comparison ignores them) and LIKE metacharacters are escaped so they match literally rather than as
     * wildcards - otherwise a rule named {@code 50%} would match every name in the project.
     * <p>
     * The escape character is {@code !} rather than the conventional {@code \}: the query is a Java text
     * block rendered through StringTemplate before it reaches MySQL, and each of those three layers treats
     * a backslash as an escape, so declaring {@code ESCAPE '\'} requires eight backslashes in source and
     * silently degrades to a syntax error if any layer changes. {@code !} is inert in all three.
     * <p>
     * Because the prefix is passed as a bound parameter (never interpolated into the statement), a
     * backslash in a rule name needs no handling here - it reaches MySQL as a literal character.
     * Case/accent folding is left to the column collation. Final precise matching happens in
     * {@link #generateUniqueName} over the returned candidate set.
     * <p>
     * For names longer than {@link #MAX_PREFIX_LENGTH} the prefix is cut to that length: suffixing such a
     * name truncates its base to fit the column, so a previously stored {@code name-1} no longer starts
     * with the full requested name and a full-length prefix would miss it - regenerating the same name on
     * the next run. The shorter prefix fetches a slightly broader candidate set; exact matching in
     * {@link #generateUniqueName} discards the extras.
     */
    static String likePrefix(String name) {
        if (name == null) {
            return null;
        }
        String stripped = StringUtils.stripEnd(name, " ");
        if (stripped.length() > MAX_PREFIX_LENGTH) {
            int cut = MAX_PREFIX_LENGTH;
            // Avoid splitting a surrogate pair, which would put a lone surrogate in the LIKE pattern.
            if (Character.isHighSurrogate(stripped.charAt(cut - 1)) && Character.isLowSurrogate(stripped.charAt(cut))) {
                cut--;
            }
            stripped = stripped.substring(0, cut);
        }
        // The escape character itself must be escaped first, or it would double-escape what follows.
        return stripped
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
    }

    /**
     * Normalizes a name for collision comparison, approximating the folding of MySQL's
     * {@code utf8mb4_unicode_ci} collation: strips trailing spaces (PAD SPACE comparison ignores them),
     * strips diacritics, and lower-cases. Mirrors the normalization used by
     * {@link com.comet.opik.domain.SlugUtils}. Note: exotic expansions ({@code ß}->{@code ss}) and
     * install-specific collations (e.g. {@code utf8mb4_0900_ai_ci}) are not replicated exactly.
     */
    private static String canonicalKey(String name) {
        // MySQL PAD SPACE collation treats trailing spaces as insignificant.
        String trimmed = StringUtils.stripEnd(name, " ");
        String normalized = Normalizer.normalize(trimmed, Normalizer.Form.NFD);
        return DIACRITICS.matcher(normalized).replaceAll("").toLowerCase(Locale.ROOT);
    }

    private static String truncateToFit(String baseName, int suffix) {
        String suffixStr = "-" + suffix;
        if (baseName.length() + suffixStr.length() <= MAX_NAME_LENGTH) {
            return baseName + suffixStr;
        }
        int cut = MAX_NAME_LENGTH - suffixStr.length();
        // Avoid splitting a surrogate pair when the cut falls in the middle of a non-BMP character.
        if (Character.isHighSurrogate(baseName.charAt(cut - 1)) && Character.isLowSurrogate(baseName.charAt(cut))) {
            cut--;
        }
        return baseName.substring(0, cut) + suffixStr;
    }
}
