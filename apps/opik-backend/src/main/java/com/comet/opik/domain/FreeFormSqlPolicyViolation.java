package com.comet.opik.domain;

import lombok.Builder;
import lombok.NonNull;

/**
 * A table read that the post-run check could not show to be under a row policy, and why ({@link FreeFormSqlPolicyCheck}).
 *
 * @param table the {@code <database>.<table>} read, or empty when no query log entry of the account was found at all
 * @param reason why the read could not be shown to be covered
 */
@Builder(toBuilder = true)
record FreeFormSqlPolicyViolation(@NonNull String table, @NonNull String reason) {

    FreeFormSqlPolicyViolation {
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }

    /** No entry of the query as its account was found, so no read could be checked. */
    boolean missingLog() {
        return table.isEmpty();
    }
}
