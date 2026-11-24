package com.comet.opik.infrastructure.db;

import lombok.experimental.UtilityClass;

/** ClickHouse server error codes of the query guards the backend sets ({@code max_rows_to_read} and the like). */
@UtilityClass
public class ClickHouseErrorCodes {

    public static final int TOO_MANY_ROWS = 158;
    public static final int TIMEOUT_EXCEEDED = 159;
    public static final int MEMORY_LIMIT_EXCEEDED = 241;
    public static final int TOO_MANY_BYTES = 307;
    public static final int TOO_MANY_ROWS_OR_BYTES = 396;
}
