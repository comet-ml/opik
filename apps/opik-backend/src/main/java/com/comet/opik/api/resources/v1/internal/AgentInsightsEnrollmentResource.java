package com.comet.opik.api.resources.v1.internal;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.AgentInsightsEnrollment;
import com.comet.opik.domain.AgentInsightsJobService;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

/**
 * Enrols projects in the auto-first-run rollout. Internal and cross-workspace, so it is reachable only from
 * inside the cluster — like the other {@code /v1/internal} resources it is not authenticated by
 * {@code AuthFilter}. Temporary: delete it once the rollout is decided either way.
 */
@Path("/v1/internal/agent-insights/enrollment")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Agent Insights enrollment", description = "Internal auto-first-run rollout enrollment")
public class AgentInsightsEnrollmentResource {

    private final @NonNull AgentInsightsJobService service;
    private final @NonNull @Config("serviceToggles") ServiceTogglesConfig serviceToggles;

    @POST
    @Operation(operationId = "enrolProjectsInAutoFirstRun", summary = "Enrol projects in the auto-first-run rollout", description = "Enrols the given projects, creating their job row if needed, or clears their enrolment. Idempotent.", responses = {
            @ApiResponse(responseCode = "200", description = "Enrollment result", content = @Content(schema = @Schema(implementation = AgentInsightsEnrollment.Response.class)))})
    public Response enrolInAutoFirstRun(@Valid @NotNull AgentInsightsEnrollment.Request request) {
        // Without Ollie the sweep is never scheduled, so an enrolment could never be honoured.
        if (!serviceToggles.isOllieEnabled()) {
            return Response.status(Response.Status.NOT_IMPLEMENTED).build();
        }
        return Response.ok(service.enrolInAutoFirstRun(request.enrolled(), request.projectIds())).build();
    }
}
