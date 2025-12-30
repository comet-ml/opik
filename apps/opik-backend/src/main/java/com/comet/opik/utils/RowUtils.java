package com.comet.opik.utils;

import io.r2dbc.spi.Row;
import lombok.experimental.UtilityClass;

/**
 * Utility class for safely accessing database row values.
 * Contains shared methods for handling optional columns that may not exist in query results.
 */
@UtilityClass
public final class RowUtils {

    /**
     * Safely retrieves a value from a database row that might not have the specified column.
     * <p>
     * This method handles cases where columns may not exist in the result set.
     * Some queries (e.g., aggregated queries with empty CTEs) may not include all possible columns
     * to optimize performance. When a column is absent, this method returns null instead of throwing an exception.
     * </p>
     *
     * @param row the database row to read from
     * @param columnName the name of the column to retrieve
     * @param type the expected class type of the value
     * @param <T> the type of the value to retrieve
     * @return the column value, or null if the column doesn't exist or the value is null
     */
    public static <T> T getOptionalValue(Row row, String columnName, Class<T> type) {
        try {
            return row.getMetadata().contains(columnName) ? row.get(columnName, type) : null;
        } catch (Exception e) {
            // Column doesn't exist in this query result - return null
            return null;
        }
    }
}
