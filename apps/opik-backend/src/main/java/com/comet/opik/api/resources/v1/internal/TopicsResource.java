package com.comet.opik.api.resources.v1.internal;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.domain.topics.TopicsNightlyService;
import com.comet.opik.infrastructure.TopicsConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.Map;
import java.util.UUID;

/**
 * Starts a project's topics run by hand, instead of waiting for the night. Internal and cross-workspace, so it is
 * reachable only from inside the cluster: like the other {@code /v1/internal} resources it is not authenticated by
 * {@code AuthFilter}. For the runbook.
 */
@Path("/v1/internal/topics")
@Produces(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Topics", description = "Internal topics operations")
public class TopicsResource {

    private final @NonNull TopicsNightlyService nightlyService;
    private final @NonNull @Config("topics") TopicsConfig config;

    @POST
    @Path("/projects/{projectId}/rebuild")
    @Operation(operationId = "rebuildProjectTopics", summary = "Build a project's topic list now", description = "Starts the project's first build in the background and returns its run id; the run's outcome is its topic_runs row. 409 when a run of the project is in progress or it already has a list.", responses = {
            @ApiResponse(responseCode = "202", description = "The run started"),
            @ApiResponse(responseCode = "409", description = "A run is in progress, or the project has a list"),
            @ApiResponse(responseCode = "501", description = "Topics are off")})
    public Response rebuild(@PathParam("projectId") UUID projectId,
            @QueryParam("workspace_id") @NotBlank String workspaceId) {
        if (!config.isEnabled()) {
            return Response.status(Response.Status.NOT_IMPLEMENTED).build();
        }
        var runId = nightlyService.rebuildNow(workspaceId, projectId);
        return Response.accepted(Map.of("run_id", runId)).build();
    }
}
