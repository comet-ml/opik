package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.OllamaConnectionTestRequest;
import com.comet.opik.api.OllamaConnectionTestResponse;
import com.comet.opik.api.OllamaModel;
import com.comet.opik.domain.OllamaService;
import com.comet.opik.infrastructure.auth.RequestContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
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

import java.util.List;

@Path("/v1/private/ollama")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Ollama", description = "Ollama provider configuration endpoints")
public class OllamaResource {

    private final @NonNull OllamaService ollamaService;
    private final @NonNull Provider<RequestContext> requestContext;

    @POST
    @Path("/test-connection")
    @Operation(summary = "Test connection to Ollama instance", description = "Validates that the provided Ollama URL is reachable and returns server information", responses = {
            @ApiResponse(responseCode = "200", description = "Connection test successful", content = @Content(schema = @Schema(implementation = OllamaConnectionTestResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request"),
            @ApiResponse(responseCode = "500", description = "Connection test failed")
    })
    public Response testConnection(
            @NotNull @Valid OllamaConnectionTestRequest request) {
        log.info("Testing Ollama connection for workspace '{}' to URL: {}",
                requestContext.get().getWorkspaceName(), request.baseUrl());

        OllamaConnectionTestResponse response = ollamaService.testConnection(request.baseUrl());

        return Response.ok(response).build();
    }

    @POST
    @Path("/models")
    @Operation(summary = "List available Ollama models", description = "Fetches the list of models available from the Ollama instance", responses = {
            @ApiResponse(responseCode = "200", description = "Models retrieved successfully", content = @Content(schema = @Schema(implementation = OllamaModel.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request"),
            @ApiResponse(responseCode = "500", description = "Failed to fetch models")
    })
    public Response listModels(
            @NotNull @Valid OllamaConnectionTestRequest request) {
        log.info("Fetching Ollama models for workspace '{}' from URL: {}",
                requestContext.get().getWorkspaceName(), request.baseUrl());

        List<OllamaModel> models = ollamaService.listModels(request.baseUrl());

        return Response.ok(models).build();
    }
}
