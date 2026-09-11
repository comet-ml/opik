package com.comet.opik.api.resources.v1.events.tools;

import lombok.experimental.UtilityClass;

/**
 * Single-string counterpart to {@link PathAwareTruncator} — caps an individual
 * string at {@code maxLength} characters and appends the canonical
 * {@code [TRUNCATED N chars …]} marker. Strings within the cap are returned
 * unchanged.
 *
 * <p>Single source of truth for the marker suffix shape across the codebase
 * (path-aware tree truncation, prompt-variable capping, span-tree overviews,
 * dataset summaries, future call sites).
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
public final class StringTruncator {

    /**
     * Returns {@code value} unchanged if it is null or no longer than
     * {@code maxLength}; otherwise returns the first {@code maxLength}
     * characters followed directly by a {@code [TRUNCATED N chars …]} suffix.
     *
     * @param hint optional drill-down text appended after an em-dash; pass
     *             {@code null} for the bare {@code [TRUNCATED N chars]} form
     */
    public static String truncate(String value, int maxLength, String hint) {
        return truncate(value, maxLength, hint, "");
    }

    /**
     * Variant that inserts {@code separator} between the kept head and the
     * marker suffix — used by line-oriented tool output where the marker
     * should sit on its own line ({@code separator = "\n"}).
     */
    public static String truncate(String value, int maxLength, String hint, String separator) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        int dropped = value.length() - maxLength;
        String head = value.substring(0, maxLength);
        String marker = hint == null
                ? "[TRUNCATED %,d chars]".formatted(dropped)
                : "[TRUNCATED %,d chars — %s]".formatted(dropped, hint);
        return head + separator + marker;
    }
}