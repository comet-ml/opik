package com.comet.opik.domain.evaluators;

import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import java.text.Normalizer;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

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
        if (StringUtils.isBlank(requestedName) || existingNames == null || existingNames.isEmpty()) {
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
     * Builds the escaped prefix to feed to a {@code name LIKE concat(?, '%')} lookup for {@code name}.
     * Trailing spaces are stripped (MySQL PAD SPACE comparison ignores them) and LIKE metacharacters
     * ({@code \}, {@code %}, {@code _}) are escaped so they match literally rather than as wildcards -
     * otherwise a name such as {@code a\b} or {@code 50%} would silently miss its own collisions.
     * Case/accent folding is left to the column collation. Final precise matching happens in
     * {@link #generateUniqueName} over the returned candidate set.
     */
    static String likePrefix(String name) {
        if (name == null) {
            return null;
        }
        return StringUtils.stripEnd(name, " ")
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
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
        String withoutDiacritics = Normalizer.normalize(trimmed, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return withoutDiacritics.toLowerCase(Locale.ROOT);
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
