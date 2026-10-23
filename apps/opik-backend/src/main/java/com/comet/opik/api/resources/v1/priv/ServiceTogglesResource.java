package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Path("/v1/private/toggles/")
@Produces(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Service Toggles", description = "Service Toggles resources")
public class ServiceTogglesResource {

    private final @NonNull OpikConfiguration config;
    private final @NonNull Provider<RequestContext> requestContext;

    @GET
    @Operation(operationId = "getServiceToggles", summary = "Get Service Toggles", description = "Get Service Toggles", responses = {
            @ApiResponse(responseCode = "200", description = "Service Toggles", content = @Content(schema = @Schema(implementation = ServiceTogglesConfig.class)))})
    public Response getToggles() {
        ServiceTogglesConfig toggles = config.getServiceToggles();
        boolean customChartsEnabledForWorkspace = config.getCustomCharts()
                .isEnabledFor(toggles, requestContext.get().getWorkspaceId());
        boolean guardrailsEnabledForWorkspace = toggles.isGuardrailsEnabled()
                && requestContext.get().isGuardrailsEnabled();
        return Response.ok()
                .entity(toggles.withCustomChartsEnabledForWorkspace(customChartsEnabledForWorkspace)
                        .withGuardrailsEnabledForWorkspace(guardrailsEnabledForWorkspace))
                .build();
    }
}
