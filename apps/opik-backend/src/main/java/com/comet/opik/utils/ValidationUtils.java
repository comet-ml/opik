package com.comet.opik.utils;

import jakarta.ws.rs.BadRequestException;
import org.apache.commons.lang3.StringUtils;

import java.util.UUID;

public class ValidationUtils {

    public static final String NULL_OR_NOT_BLANK = "(?s)^\\s*(\\S.*\\S|\\S)\\s*$";

    /**
     * Canonical String representation to ensure precision over float or double.
     */
    public static final String MIN_FEEDBACK_SCORE_VALUE = "-999999999.999999999";
    public static final String MAX_FEEDBACK_SCORE_VALUE = "999999999.999999999";
    public static final int SCALE = 9;

    /**
     * We're using FixedString(36) to store UUIDs in Clickhouse. This isn't a nullable field, but it can be null under
     * certain circumstances, mostly during LEFT JOIN statements when there are no matching records from the table on
     * the right.
     * In those cases, ClickHouse returns the null character ('\u0000') as many times as the characters in the field
     * length (36).
     */
    public static final String CLICKHOUSE_FIXED_STRING_UUID_FIELD_NULL_VALUE = StringUtils.repeat('\u0000', 36);

    public static void validateProjectNameAndProjectId(String projectName, UUID projectId) {
        if (StringUtils.isBlank(projectName) && projectId == null) {
            throw new BadRequestException("Either 'project_name' or 'project_id' query params must be provided");
        }
    }
}
