package com.comet.opik.domain;

import com.google.common.base.Preconditions;
import lombok.Builder;
import org.apache.commons.lang3.StringUtils;

/**
 * A table read that the post-run check could not show to be under a row policy, and why ({@link FreeFormSqlPolicyCheck}).
 *
 * @param table the {@code <database>.<table>} read, or empty when no query log entry of the account was found at all
 * @param reason why the read could not be shown to be covered
 */
@Builder(toBuilder = true)
record FreeFormSqlPolicyViolation(String table, String reason) {

    FreeFormSqlPolicyViolation {
        Preconditions.checkArgument(table != null, "table must not be null; it is empty when no log entry was found");
        Preconditions.checkArgument(StringUtils.isNotBlank(reason), "reason must not be blank");
    }

    /** No log entry for the query, run as its account, was found, so no read could be checked. */
    boolean missingLog() {
        return table.isEmpty();
    }
}
