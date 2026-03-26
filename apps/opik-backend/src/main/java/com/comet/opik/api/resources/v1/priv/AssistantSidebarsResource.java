package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.AssistantSidebarConfigResponse;
import com.comet.opik.infrastructure.AssistantSidebarConfig;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
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
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

@Path("/v1/private/assistant-sidebars/config")
@Produces(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Assistant Sidebars", description = "Assistant Sidebar configuration")
public class AssistantSidebarsResource {

    private final @NonNull @Config("assistantSidebar") AssistantSidebarConfig config;
    private final @NonNull @Config("serviceToggles") ServiceTogglesConfig toggles;

    @GET
    @Operation(operationId = "getAssistantSidebarConfig", summary = "Get Assistant Sidebar configuration", description = "Get CDN configuration for loading the assistant sidebar at runtime", responses = {
            @ApiResponse(responseCode = "200", description = "Assistant Sidebar config", content = @Content(schema = @Schema(implementation = AssistantSidebarConfigResponse.class)))
    })
    public Response getConfig() {
        return Response.ok()
                .entity(AssistantSidebarConfigResponse.builder()
                        .enabled(toggles.isAssistantSidebarEnabled())
                        .manifestUrl(config.getManifestUrl())
                        .build())
                .build();
    }
}
