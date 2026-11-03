package com.comet.opik.infrastructure.db;

import lombok.experimental.UtilityClass;

import java.util.Set;

/** ClickHouse error codes of a query stopped at one of its limits: rows to read, time or memory. */
@UtilityClass
public class ClickHouseQueryLimits {

    public static final int TOO_MANY_ROWS = 158;
    public static final int TIMEOUT_EXCEEDED = 159;
    public static final int MEMORY_LIMIT_EXCEEDED = 241;
    public static final int TOO_MANY_ROWS_OR_BYTES = 396;

    public static final Set<Integer> CODES = Set.of(
            TOO_MANY_ROWS, TIMEOUT_EXCEEDED, MEMORY_LIMIT_EXCEEDED, TOO_MANY_ROWS_OR_BYTES);
}
