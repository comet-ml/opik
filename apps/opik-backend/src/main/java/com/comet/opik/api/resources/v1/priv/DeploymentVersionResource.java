package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.DeploymentVersion.DeploymentVersionHistory;
import com.comet.opik.domain.DeploymentVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Path("/v1/private/deployment/versions")
@Produces(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Deployment", description = "Deployment-level metadata")
public class DeploymentVersionResource {

    private final @NonNull DeploymentVersionService service;

    @GET
    @Operation(operationId = "getDeploymentVersionHistory", summary = "Get deployment version history", description = "Get the Opik version this deployment is running, and the versions it has run before, newest first", responses = {
            @ApiResponse(responseCode = "200", description = "Deployment version history", content = @Content(schema = @Schema(implementation = DeploymentVersionHistory.class)))})
    public Response getVersionHistory() {
        log.info("Getting deployment version history");

        var history = service.getHistory();

        log.info("Got deployment version history: current_version '{}', {} recorded version(s)",
                history.currentVersion(), history.versions().size());

        return Response.ok().entity(history).build();
    }
}
