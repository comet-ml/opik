package com.comet.opik.domain.evaluators;

import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

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
     * Comparison is case-insensitive to match MySQL's default (case-insensitive) collation, and each
     * candidate is checked as the actual (possibly truncated) string that would be stored, so names near
     * the 150-char column limit still resolve to distinct values.
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
                taken.add(name.toLowerCase(Locale.ROOT));
            }
        }

        if (!taken.contains(requestedName.toLowerCase(Locale.ROOT))) {
            return requestedName;
        }

        // Probe successive suffixes and return the first candidate that is actually free. Checking the
        // final string (after any truncation) guarantees we never regenerate an existing name.
        for (int suffix = 1;; suffix++) {
            String candidate = truncateToFit(requestedName, suffix);
            if (!taken.contains(candidate.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
        }
    }

    private static String truncateToFit(String baseName, int suffix) {
        String suffixStr = "-" + suffix;
        if (baseName.length() + suffixStr.length() <= MAX_NAME_LENGTH) {
            return baseName + suffixStr;
        }
        int maxBaseLength = MAX_NAME_LENGTH - suffixStr.length();
        return baseName.substring(0, maxBaseLength) + suffixStr;
    }
}
