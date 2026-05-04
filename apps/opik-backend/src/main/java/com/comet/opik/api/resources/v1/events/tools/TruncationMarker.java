package com.comet.opik.api.resources.v1.events.tools;

import lombok.experimental.UtilityClass;

/**
 * Single source of truth for the {@code [TRUNCATED N chars …]} suffix used to
 * mark over-length string values across the codebase (path-aware tree
 * truncation, prompt-variable capping, future call sites).
 *
 * <p>Output shape:
 * <ul>
 *   <li>With hint: <pre>&lt;first maxLength chars&gt;[TRUNCATED N chars — &lt;hint&gt;]</pre></li>
 *   <li>Without hint (hint == null): <pre>&lt;first maxLength chars&gt;[TRUNCATED N chars]</pre></li>
 * </ul>
 *
 * <p>Concrete hint strings are built by callers — e.g. {@code PathAwareTruncator}
 * builds {@code "use jq('<path>') to see full"} per node, and the test-suite
 * scoring path passes a {@code read} tool drill-down hint.
 */
@UtilityClass
public final class TruncationMarker {

    /**
     * Returns {@code value} unchanged if it is null or no longer than
     * {@code maxLength}; otherwise returns the first {@code maxLength}
     * characters followed by a {@code [TRUNCATED N chars …]} suffix.
     *
     * @param hint optional drill-down text appended after an em-dash; pass
     *             {@code null} for the bare {@code [TRUNCATED N chars]} form
     */
    public static String apply(String value, int maxLength, String hint) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        int dropped = value.length() - maxLength;
        String head = value.substring(0, maxLength);
        return hint == null
                ? head + "[TRUNCATED %,d chars]".formatted(dropped)
                : head + "[TRUNCATED %,d chars — %s]".formatted(dropped, hint);
    }
}