package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.OllamaConnectionTestResponse;
import com.comet.opik.api.OllamaInstanceBaseUrlRequest;
import com.comet.opik.api.OllamaModel;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.domain.OllamaService;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
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
@Tag(name = "Ollama", description = "Ollama provider configuration endpoints with OpenAI-compatible API support.")
public class OllamaResource {

    private final @NonNull OllamaService ollamaService;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull OpikConfiguration config;

    private boolean isOllamaEnabled() {
        return config.getServiceToggles() != null
                && config.getServiceToggles().isOllamaProviderEnabled();
    }

    @POST
    @Path("/test-connection")
    @Operation(summary = "Test connection to Ollama instance", description = "Validates that the provided Ollama URL is reachable. "
            + "URL may be provided with or without /v1 suffix (e.g., http://localhost:11434 or http://localhost:11434/v1). "
            + "The /v1 suffix will be automatically removed for connection testing. "
            + "For inference, use the URL with /v1 suffix.", responses = {
                    @ApiResponse(responseCode = "200", description = "Connection test successful", content = @Content(schema = @Schema(implementation = OllamaConnectionTestResponse.class))),
                    @ApiResponse(responseCode = "422", description = "Unprocessable Content - Invalid URL format", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
                    @ApiResponse(responseCode = "502", description = "Connection test failed - Ollama instance unreachable", content = @Content(schema = @Schema(implementation = OllamaConnectionTestResponse.class))),
                    @ApiResponse(responseCode = "503", description = "Ollama provider is disabled", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
            })
    public Response testConnection(
            @NotNull @Valid OllamaInstanceBaseUrlRequest request) {
        if (!isOllamaEnabled()) {
            log.warn("Ollama provider is disabled, returning 503");
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(new io.dropwizard.jersey.errors.ErrorMessage(
                            Response.Status.SERVICE_UNAVAILABLE.getStatusCode(),
                            "Ollama provider is disabled"))
                    .build();
        }

        log.info("Testing Ollama connection for workspace '{}'",
                requestContext.get().getWorkspaceName());

        OllamaConnectionTestResponse response = ollamaService.testConnection(request.baseUrl(), request.apiKey())
                .block();

        if (response != null && !response.connected()) {
            return Response.status(Response.Status.BAD_GATEWAY).entity(response).build();
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/models")
    @Operation(summary = "List available Ollama models", description = "Fetches the list of models available from the Ollama instance. "
            + "URL may be provided with or without /v1 suffix (e.g., http://localhost:11434 or http://localhost:11434/v1). "
            + "The /v1 suffix will be automatically removed for model discovery. "
            + "For actual LLM inference, use the URL with /v1 suffix for OpenAI-compatible endpoints.", responses = {
                    @ApiResponse(responseCode = "200", description = "Models retrieved successfully", content = @Content(array = @ArraySchema(schema = @Schema(implementation = OllamaModel.class)))),
                    @ApiResponse(responseCode = "422", description = "Unprocessable Content - Invalid URL format", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
                    @ApiResponse(responseCode = "500", description = "Failed to fetch models"),
                    @ApiResponse(responseCode = "503", description = "Ollama provider is disabled", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
            })
    public Response listModels(
            @NotNull @Valid OllamaInstanceBaseUrlRequest request) {
        if (!isOllamaEnabled()) {
            log.warn("Ollama provider is disabled, returning 503");
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(new io.dropwizard.jersey.errors.ErrorMessage(
                            Response.Status.SERVICE_UNAVAILABLE.getStatusCode(),
                            "Ollama provider is disabled"))
                    .build();
        }

        log.info("Fetching Ollama models for workspace '{}'",
                requestContext.get().getWorkspaceName());

        List<OllamaModel> models = ollamaService.listModels(request.baseUrl(), request.apiKey())
                .block();
        return Response.ok(models).build();
    }
}
