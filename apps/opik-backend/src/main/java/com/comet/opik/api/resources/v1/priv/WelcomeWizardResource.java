package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.welcomewizard.WelcomeWizardSubmission;
import com.comet.opik.api.welcomewizard.WelcomeWizardTracking;
import com.comet.opik.domain.WelcomeWizardTrackingService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Path("/v1/private/welcome-wizard")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Welcome Wizard", description = "Welcome wizard tracking resources")
public class WelcomeWizardResource {

    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull WelcomeWizardTrackingService service;

    @GET
    @Operation(operationId = "getWelcomeWizardStatus", summary = "Get welcome wizard tracking status", description = "Get welcome wizard tracking status for the current workspace", responses = {
            @ApiResponse(responseCode = "200", description = "Welcome wizard tracking status", content = @Content(schema = @Schema(implementation = WelcomeWizardTracking.class)))})
    public Response getStatus() {
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Getting welcome wizard status for workspace_id '{}'", workspaceId);

        var tracking = service.getTrackingStatus(workspaceId);

        log.info("Welcome wizard status for workspace_id '{}': completed={}", workspaceId,
                tracking.completed());

        return Response.ok().entity(tracking).build();
    }

    @POST
    @Operation(operationId = "submitWelcomeWizard", summary = "Submit welcome wizard", description = "Submit welcome wizard with user information", responses = {
            @ApiResponse(responseCode = "204", description = "Welcome wizard submitted successfully")})
    @RateLimited
    public Response submitWizard(
            @RequestBody(content = @Content(schema = @Schema(implementation = WelcomeWizardSubmission.class))) @Valid WelcomeWizardSubmission submission) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Submitting welcome wizard for workspace_id '{}'", workspaceId);

        service.submitWizard(workspaceId, submission);

        log.info("Welcome wizard submitted for workspace_id '{}'", workspaceId);

        return Response.noContent().build();
    }
}
