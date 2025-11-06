package com.comet.opik.domain;

import com.comet.opik.api.Trace;
import com.comet.opik.infrastructure.pagination.Cursor;
import com.comet.opik.infrastructure.pagination.CursorPaginationResponse;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.Statement;
import lombok.extern.slf4j.Slf4j;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Cursor-based pagination implementation for TraceDAO.
 *
 * This class contains the cursor pagination methods to be integrated into TraceDAO.
 *
 * Integration steps:
 * 1. Add this method to the TraceDAO interface (line 78-127)
 * 2. Add the implementation to TraceDAOImpl class
 * 3. Add the SQL template as a constant
 *
 * @since 1.9.0
 */
@Slf4j
public class TraceDAOCursorPagination {

    /**
     * SQL template for cursor-based trace retrieval.
     *
     * Key improvements over offset-based:
     * - Uses WHERE clause instead of OFFSET (O(1) vs O(n) performance)
     * - Stable results when data changes
     * - Works with indexes efficiently
     */
    private static final String FIND_WITH_CURSOR = """
            WITH filtered_traces AS (
                SELECT *
                FROM traces
                WHERE workspace_id = :workspace_id
                <if(project_id)>AND project_id = :project_id<endif>
                <if(cursor_timestamp)>
                -- Cursor filtering for pagination
                AND (last_updated_at, id) < (:cursor_timestamp, :cursor_id)
                <endif>
                <if(filters)><filters><endif>
                <if(sort_fields)>
                ORDER BY <sort_fields>, id DESC, last_updated_at DESC
                <else>
                ORDER BY last_updated_at DESC, id DESC
                <endif>
                -- Fetch one extra to check if there's a next page
                LIMIT :limit + 1
            )
            SELECT *
            FROM filtered_traces
            ORDER BY last_updated_at DESC, id DESC
            LIMIT 1 BY id;
            """;

    /**
     * Find traces using cursor-based pagination.
     *
     * ADD THIS METHOD TO TraceDAO INTERFACE:
     *
     * <pre>
     * Mono<CursorPaginationResponse<Trace>> findWithCursor(
     *     int limit,
     *     String cursorString,
     *     TraceSearchCriteria criteria
     * );
     * </pre>
     *
     * ADD THIS IMPLEMENTATION TO TraceDAOImpl CLASS:
     *
     * @param limit maximum number of traces to return
     * @param cursorString encoded cursor from previous response (null for first page)
     * @param criteria search/filter criteria
     * @return paginated response with traces and next cursor
     */
    public Mono<CursorPaginationResponse<Trace>> findWithCursor(
            int limit,
            String cursorString,
            TraceSearchCriteria criteria,
            Connection connection) {

        return Mono.defer(() -> {
            // Decode cursor if provided
            Cursor cursor = cursorString != null && !cursorString.isEmpty()
                    ? Cursor.decode(cursorString)
                    : null;

            log.debug("Finding traces with cursor pagination: limit={}, cursor={}, criteria={}",
                    limit, cursor != null ? cursor.toString() : "null", criteria);

            // Build query
            ST template = new ST(FIND_WITH_CURSOR);
            template.add("workspace_id", criteria.workspaceId());
            template.add("project_id", criteria.projectId());
            template.add("limit", limit);

            // Add cursor parameters if present
            if (cursor != null) {
                template.add("cursor_timestamp", cursor.getTimestamp());
                template.add("cursor_id", cursor.getId());
            }

            // Add filters (reuse existing filter logic)
            // String filterQuery = buildFilterQuery(criteria);
            // template.add("filters", filterQuery);

            // Add sorting (reuse existing sort logic)
            // String sortFields = buildSortFields(criteria);
            // template.add("sort_fields", sortFields);

            String query = template.render();
            log.debug("Cursor pagination query: {}", query);

            Statement statement = connection.createStatement(query);

            // Bind parameters
            // ... bind logic similar to existing find() method

            // Execute query
            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, metadata) -> mapTrace(row)))
                    .collectList()
                    .map(traces -> buildPaginationResponse(traces, limit));
        });
    }

    /**
     * Build pagination response from query results
     */
    private CursorPaginationResponse<Trace> buildPaginationResponse(List<Trace> traces, int limit) {
        // Check if there are more results
        boolean hasMore = traces.size() > limit;

        // Get actual content (exclude the extra item used for hasMore check)
        List<Trace> content = hasMore ? traces.subList(0, limit) : traces;

        // Generate next cursor from last item
        String nextCursor = null;
        if (hasMore && !content.isEmpty()) {
            Trace lastTrace = content.get(content.size() - 1);
            nextCursor = Cursor.of(lastTrace.lastUpdatedAt(), lastTrace.id()).encode();
        }

        log.debug("Built pagination response: size={}, hasMore={}, nextCursor={}",
                content.size(), hasMore, nextCursor != null ? "present" : "null");

        return CursorPaginationResponse.<Trace>builder()
                .content(content)
                .nextCursor(nextCursor)
                .hasMore(hasMore)
                .size(content.size())
                .build();
    }

    /**
     * Placeholder for trace mapping
     * Replace with actual mapTrace implementation from TraceDAOImpl
     */
    private Trace mapTrace(io.r2dbc.spi.Row row) {
        // This method already exists in TraceDAOImpl
        // Just reference it when integrating
        throw new UnsupportedOperationException("Use existing mapTrace from TraceDAOImpl");
    }

    /**
     * Example usage in service layer:
     *
     * <pre>
     * public Mono<CursorPaginationResponse<Trace>> getTraces(
     *         UUID projectId,
     *         String cursor,
     *         int limit) {
     *
     *     return transactionTemplate.nonTransaction(connection -> {
     *         TraceSearchCriteria criteria = TraceSearchCriteria.builder()
     *                 .projectId(projectId)
     *                 .workspaceId(getWorkspaceId())
     *                 .build();
     *
     *         return traceDAO.findWithCursor(limit, cursor, criteria, connection);
     *     });
     * }
     * </pre>
     */
}
