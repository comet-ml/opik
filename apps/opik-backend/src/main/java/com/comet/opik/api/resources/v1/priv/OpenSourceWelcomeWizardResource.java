package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.opensourcewelcomewizard.OpenSourceWelcomeWizardSubmission;
import com.comet.opik.api.opensourcewelcomewizard.OpenSourceWelcomeWizardTracking;
import com.comet.opik.domain.OpenSourceWelcomeWizardTrackingService;
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

@Path("/v1/private/open-source-welcome-wizard")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Open Source Welcome Wizard", description = "Open source welcome wizard tracking resources")
public class OpenSourceWelcomeWizardResource {

    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull OpenSourceWelcomeWizardTrackingService service;

    @GET
    @Operation(operationId = "getOpenSourceWelcomeWizardStatus", summary = "Get OSS welcome wizard tracking status", description = "Get open source welcome wizard tracking status for the current workspace", responses = {
            @ApiResponse(responseCode = "200", description = "OSS welcome wizard tracking status", content = @Content(schema = @Schema(implementation = OpenSourceWelcomeWizardTracking.class)))})
    public Response getStatus() {
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Getting OSS welcome wizard status for workspace_id '{}'", workspaceId);

        var tracking = service.getTrackingStatus(workspaceId);

        // Return default object with completed=false if no tracking entry exists
        var response = tracking.orElseGet(() -> OpenSourceWelcomeWizardTracking.builder()
                .workspaceId(workspaceId)
                .completed(false)
                .build());

        log.info("OSS welcome wizard status for workspace_id '{}': completed={}", workspaceId,
                response.completed());

        return Response.ok().entity(response).build();
    }

    @POST
    @Operation(operationId = "submitOpenSourceWelcomeWizard", summary = "Submit OSS welcome wizard", description = "Submit open source welcome wizard with user information", responses = {
            @ApiResponse(responseCode = "200", description = "OSS welcome wizard submitted successfully", content = @Content(schema = @Schema(implementation = OpenSourceWelcomeWizardTracking.class)))})
    @RateLimited
    public Response submitWizard(
            @RequestBody(content = @Content(schema = @Schema(implementation = OpenSourceWelcomeWizardSubmission.class))) @Valid OpenSourceWelcomeWizardSubmission submission) {

        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        log.info("Submitting OSS welcome wizard for workspace_id '{}'", workspaceId);

        var tracking = service.submitWizard(workspaceId, userName, submission);

        log.info("OSS welcome wizard submitted for workspace_id '{}'", workspaceId);

        return Response.ok().entity(tracking).build();
    }
}
