package com.comet.opik.infrastructure.pagination;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.function.Function;

/**
 * Response object for cursor-based pagination.
 *
 * Example response:
 * <pre>
 * {
 *   "content": [...],
 *   "nextCursor": "ABC123",
 *   "previousCursor": "XYZ789",
 *   "hasMore": true,
 *   "size": 50
 * }
 * </pre>
 *
 * @param <T> the type of items in the response
 * @since 1.9.0
 */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CursorPaginationResponse<T> {

    /**
     * The list of items for this page
     */
    @JsonProperty("content")
    List<T> content;

    /**
     * Cursor to fetch the next page.
     * Null if there are no more pages.
     */
    @JsonProperty("nextCursor")
    String nextCursor;

    /**
     * Cursor to fetch the previous page.
     * Null if this is the first page.
     */
    @JsonProperty("previousCursor")
    String previousCursor;

    /**
     * Whether there are more items after this page
     */
    @JsonProperty("hasMore")
    boolean hasMore;

    /**
     * Number of items in this response
     */
    @JsonProperty("size")
    int size;

    /**
     * Optional: Total count of items (if available and not too expensive to compute)
     * Most cursor-based pagination APIs don't include this for performance reasons.
     */
    @JsonProperty("totalCount")
    Long totalCount;

    /**
     * Create a response from a list of items and pagination info
     *
     * @param items the items for this page
     * @param nextCursor cursor for the next page
     * @param hasMore whether there are more pages
     * @param <T> the type of items
     * @return paginated response
     */
    public static <T> CursorPaginationResponse<T> of(
            List<T> items,
            String nextCursor,
            boolean hasMore) {

        return CursorPaginationResponse.<T>builder()
                .content(items)
                .nextCursor(nextCursor)
                .hasMore(hasMore)
                .size(items.size())
                .build();
    }

    /**
     * Create a response with both next and previous cursors
     */
    public static <T> CursorPaginationResponse<T> of(
            List<T> items,
            String nextCursor,
            String previousCursor,
            boolean hasMore) {

        return CursorPaginationResponse.<T>builder()
                .content(items)
                .nextCursor(nextCursor)
                .previousCursor(previousCursor)
                .hasMore(hasMore)
                .size(items.size())
                .build();
    }

    /**
     * Create an empty first page response
     */
    public static <T> CursorPaginationResponse<T> empty() {
        return CursorPaginationResponse.<T>builder()
                .content(List.of())
                .hasMore(false)
                .size(0)
                .build();
    }

    /**
     * Create a cursor pagination response from a list of items.
     * This method automatically determines if there are more items and creates appropriate cursors.
     *
     * @param items the items retrieved (may be limit + 1 items)
     * @param limit the requested page size
     * @param cursorExtractor function to extract cursor from an item
     * @param <T> the type of items
     * @return paginated response
     */
    public static <T> CursorPaginationResponse<T> from(
            List<T> items,
            int limit,
            Function<T, Cursor> cursorExtractor) {

        if (items == null || items.isEmpty()) {
            return empty();
        }

        // Check if we fetched more than the limit (indicates more pages exist)
        boolean hasMore = items.size() > limit;

        // Get actual items (remove the extra item if we fetched limit + 1)
        List<T> content = hasMore ? items.subList(0, limit) : items;

        // Generate next cursor from the last item in the page
        String nextCursor = null;
        if (hasMore && !content.isEmpty()) {
            T lastItem = content.get(content.size() - 1);
            Cursor cursor = cursorExtractor.apply(lastItem);
            nextCursor = cursor.encode();
        }

        return CursorPaginationResponse.<T>builder()
                .content(content)
                .nextCursor(nextCursor)
                .hasMore(hasMore)
                .size(content.size())
                .build();
    }

    /**
     * Check if this response has any items
     */
    public boolean isEmpty() {
        return content == null || content.isEmpty();
    }

    /**
     * Check if this is the last page
     */
    public boolean isLastPage() {
        return !hasMore;
    }

    /**
     * Check if this is the first page (no previous cursor)
     */
    public boolean isFirstPage() {
        return previousCursor == null || previousCursor.isEmpty();
    }

    /**
     * Get the number of items in this page
     */
    public int getPageSize() {
        return size;
    }

    /**
     * Create a response builder from this response
     * Useful for creating modified responses
     */
    public static <T> CursorPaginationResponseBuilder<T> from(CursorPaginationResponse<T> response) {
        return CursorPaginationResponse.<T>builder()
                .content(response.content)
                .nextCursor(response.nextCursor)
                .previousCursor(response.previousCursor)
                .hasMore(response.hasMore)
                .size(response.size)
                .totalCount(response.totalCount);
    }
}
