package com.comet.opik.api.resources.v1.internal;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.TraceCountResponse;
import com.comet.opik.domain.TraceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Path("/v1/internal/usage")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @jakarta.inject.Inject)
@Tag(name = "System usage", description = "System usage related resource")
public class UsageResource {
    private final @NonNull TraceService traceService;

    @GET
    @Path("/workspace-trace-counts")
    @Operation(operationId = "getTracesCountForWorkspaces", summary = "Get traces count on previous day for all available workspaces", description = "Get traces count on previous day for all available workspaces", responses = {
            @ApiResponse(responseCode = "200", description = "TraceCountResponse resource", content = @Content(schema = @Schema(implementation = TraceCountResponse.class)))})
    public Response getTracesCountForWorkspaces() {
        return traceService.countTracesPerWorkspace()
                .map(tracesCountResponse -> Response.ok(tracesCountResponse).build())
                .block();
    }
}
