package com.comet.opik.infrastructure.pagination;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * Request object for cursor-based pagination.
 *
 * Example usage:
 * <pre>
 *   GET /api/v1/traces?cursor=ABC123&limit=50
 * </pre>
 *
 * @since 1.9.0
 */
@Value
@Builder
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
public class CursorPaginationRequest {

    /**
     * The cursor from the previous response's nextCursor field.
     * If null, starts from the beginning.
     */
    String cursor;

    /**
     * Maximum number of items to return.
     * Default: 50, Min: 1, Max: 1000
     */
    @Builder.Default
    @Min(value = 1, message = "Limit must be at least 1")
    @Max(value = 1000, message = "Limit cannot exceed 1000")
    Integer limit = 50;

    /**
     * Direction for pagination.
     * FORWARD: Get next page (default)
     * BACKWARD: Get previous page
     */
    @Builder.Default
    Direction direction = Direction.FORWARD;

    /**
     * Get the decoded cursor, or null if no cursor provided
     */
    public Cursor getDecodedCursor() {
        if (cursor == null || cursor.isEmpty()) {
            return null;
        }
        return Cursor.decode(cursor);
    }

    /**
     * Check if this is the first page request (no cursor)
     */
    public boolean isFirstPage() {
        return cursor == null || cursor.isEmpty();
    }

    /**
     * Pagination direction
     */
    public enum Direction {
        /**
         * Navigate forward (next page)
         */
        FORWARD,

        /**
         * Navigate backward (previous page)
         */
        BACKWARD
    }

    /**
     * Create a default first-page request
     */
    public static CursorPaginationRequest firstPage() {
        return CursorPaginationRequest.builder().build();
    }

    /**
     * Create a request with a specific limit
     */
    public static CursorPaginationRequest withLimit(int limit) {
        return CursorPaginationRequest.builder()
                .limit(limit)
                .build();
    }

    /**
     * Create a request for the next page
     */
    public static CursorPaginationRequest nextPage(String cursor, int limit) {
        return CursorPaginationRequest.builder()
                .cursor(cursor)
                .limit(limit)
                .direction(Direction.FORWARD)
                .build();
    }

    /**
     * Create a request for the previous page
     */
    public static CursorPaginationRequest previousPage(String cursor, int limit) {
        return CursorPaginationRequest.builder()
                .cursor(cursor)
                .limit(limit)
                .direction(Direction.BACKWARD)
                .build();
    }
}
