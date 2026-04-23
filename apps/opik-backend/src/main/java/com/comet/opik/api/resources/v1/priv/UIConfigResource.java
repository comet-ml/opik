package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.UIConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Path("/v1/private/ui-config/")
@Produces(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "UI Config", description = "Deployment-level UI configuration")
public class UIConfigResource {

    private final @NonNull OpikConfiguration config;

    @GET
    @Operation(operationId = "getUIConfig", summary = "Get UI Config", description = "Get deployment-level UI configuration values consumed by the frontend", responses = {
            @ApiResponse(responseCode = "200", description = "UI Config", content = @Content(schema = @Schema(implementation = UIConfig.class)))})
    public Response getUIConfig() {
        return Response.ok()
                .entity(config.getUiConfig())
                .build();
    }
}
