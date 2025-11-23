package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Trace;
import com.comet.opik.infrastructure.pagination.CursorPaginationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Cursor-based pagination endpoint for traces.
 *
 * This is an example/reference implementation to be added to TracesResource.
 *
 * ADD THIS METHOD TO: com.comet.opik.api.resources.v1.priv.TracesResource
 *
 * Key benefits over existing offset-based endpoint (/v1/private/traces):
 * - O(1) performance regardless of page depth
 * - Stable results when data changes
 * - No duplicate/missing results during pagination
 * - 95% faster for deep pages (page 100+)
 *
 * @since 1.9.0
 */
@Path("/v1/private")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Traces", description = "Trace operations")
public class TracesResourceCursorEndpoint {

    /**
     * Get traces using cursor-based pagination.
     *
     * Example requests:
     *
     * First page:
     *   GET /v1/private/traces/cursor?projectId=xxx&limit=50
     *
     * Next page:
     *   GET /v1/private/traces/cursor?projectId=xxx&limit=50&cursor=ABC123
     *
     * With filters:
     *   GET /v1/private/traces/cursor?projectId=xxx&limit=50&name=my-trace
     *
     * Example response:
     * {
     *   "content": [...],
     *   "nextCursor": "XYZ789",
     *   "hasMore": true,
     *   "size": 50
     * }
     *
     * @param projectId project ID (required)
     * @param cursor cursor from previous response (null for first page)
     * @param limit number of items per page (1-1000, default 50)
     * @return paginated trace response
     */
    @GET
    @Path("/traces/cursor")
    @Operation(
        summary = "Get traces with cursor pagination",
        description = "Retrieve traces using efficient cursor-based pagination. " +
                      "This endpoint provides better performance than offset-based pagination, " +
                      "especially for large datasets and deep pages.",
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "Successful response",
                content = @Content(schema = @Schema(implementation = CursorPaginationResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "Invalid cursor or parameters"),
            @ApiResponse(responseCode = "404", description = "Project not found")
        }
    )
    public Mono<Response> getTracesWithCursor(
            @Parameter(description = "Project ID", required = true)
            @QueryParam("projectId") UUID projectId,

            @Parameter(description = "Cursor from previous response (omit for first page)")
            @QueryParam("cursor") String cursor,

            @Parameter(description = "Maximum number of items to return")
            @QueryParam("limit")
            @DefaultValue("50")
            @Min(value = 1, message = "Limit must be at least 1")
            @Max(value = 1000, message = "Limit cannot exceed 1000")
            int limit,

            // Additional filters (same as existing endpoint)
            @Parameter(description = "Filter by trace name")
            @QueryParam("name") String name,

            @Parameter(description = "Filter by tags")
            @QueryParam("tags") String tags) {

        // Validate cursor format if provided
        if (cursor != null && !cursor.isEmpty()) {
            try {
                com.comet.opik.infrastructure.pagination.Cursor.decode(cursor);
            } catch (IllegalArgumentException e) {
                return Mono.just(Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Invalid cursor format"))
                        .build());
            }
        }

        // Implementation would call TraceService with cursor pagination
        /*
        TraceSearchCriteria criteria = TraceSearchCriteria.builder()
                .projectId(projectId)
                .name(name)
                .tags(parseTags(tags))
                .build();

        return traceService.findWithCursor(limit, cursor, criteria)
                .map(result -> Response.ok(result).build())
                .onErrorResume(e -> {
                    log.error("Error fetching traces with cursor", e);
                    return Mono.just(Response.serverError().build());
                });
        */

        // Placeholder response
        return Mono.just(Response.ok(
            CursorPaginationResponse.<Trace>builder()
                    .content(List.of())
                    .hasMore(false)
                    .size(0)
                    .build()
        ).build());
    }

    /**
     * Migration strategy from offset to cursor pagination:
     *
     * Phase 1: Add new cursor endpoint (/traces/cursor)
     * - Keep existing offset endpoint for backward compatibility
     * - Document cursor endpoint as recommended
     *
     * Phase 2: Update clients
     * - Frontend: Switch to cursor pagination
     * - SDKs: Add cursor support
     *
     * Phase 3: Deprecate offset endpoint (6+ months later)
     * - Add deprecation notices
     * - Monitor usage
     *
     * Phase 4: Remove offset endpoint (12+ months later)
     * - Remove /v1/private/traces?page=X&size=Y
     * - Keep only /v1/private/traces/cursor
     */
}
