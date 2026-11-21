package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.LlmModelDefinition;
import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.ModelParams;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmModelRegistryService;
import com.comet.opik.infrastructure.llm.OpenAiClientConfig;
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
import org.apache.commons.lang3.StringUtils;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Path("/v1/private/llm/models")
@Produces(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "LLM Models", description = "LLM model registry resources")
public class LlmModelsResource {

    private final @NonNull LlmModelRegistryService registryService;
    private final @NonNull @Config("llmProviderClient") LlmProviderClientConfig llmProviderClientConfig;

    @GET
    @Operation(operationId = "getLlmModels", summary = "Get LLM models", description = "Get the list of supported LLM models grouped by provider", responses = {
            @ApiResponse(responseCode = "200", description = "LLM models by provider", content = @Content(schema = @Schema(implementation = Map.class)))})
    public Response getModels() {
        var registry = registryService.getRegistry();
        return Response.ok()
                .entity(hasServerWideOpenAiUrl() ? clearOpenAiOnlyRoutes(registry) : registry)
                .build();
    }

    @GET
    @Path("/profiles")
    @Operation(operationId = "getLlmModelProfiles", summary = "Get LLM model profiles", description = "Get the params each provider's unlisted models take, keyed by provider", responses = {
            @ApiResponse(responseCode = "200", description = "Params by provider", content = @Content(schema = @Schema(type = "object", additionalPropertiesSchema = ModelParams.class)))})
    public Response getProfiles() {
        return Response.ok()
                .entity(registryService.getProfiles())
                .build();
    }

    private boolean hasServerWideOpenAiUrl() {
        return Optional.ofNullable(llmProviderClientConfig.getOpenAiClient())
                .map(OpenAiClientConfig::url)
                .filter(StringUtils::isNotBlank)
                .isPresent();
    }

    // Behind a server-wide URL the backend skips the route check, since a gateway may serve the model on either API,
    // so the picker must not hide it either.
    private static Map<String, List<LlmModelDefinition>> clearOpenAiOnlyRoutes(
            Map<String, List<LlmModelDefinition>> registry) {
        var served = new LinkedHashMap<>(registry);
        served.computeIfPresent(LlmProvider.OPEN_AI.getValue(), (provider, models) -> models.stream()
                .map(model -> model.params() == null || model.params().onlyRoute() == null
                        ? model
                        : model.toBuilder().params(model.params().toBuilder().onlyRoute(null).build()).build())
                .toList());
        return served;
    }
}
