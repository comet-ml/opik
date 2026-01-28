package com.comet.opik.domain;

import com.comet.opik.api.OllamaConnectionTestResponse;
import com.comet.opik.api.OllamaModel;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.ValidationUtils;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.InvocationCallback;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * Service for interacting with Ollama instances via OpenAI-compatible API.
 * Provides functionality to test connections and discover available models.
 *
 * <p>For LLM inference, the base URL with /v1 suffix should be used with
 * OpenAI-compatible clients (e.g., LangChain4j's OpenAiClient).
 */
@Singleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class OllamaService {

    private final @NonNull Client httpClient;

    /**
     * Tests connection to an Ollama instance and retrieves a server version.
     *
     * @param baseUrl Base URL of the Ollama instance (without /v1 suffix, e.g., http://localhost:11434)
     * @param apiKey  Optional API key for authenticated Ollama instances
     * @return Mono emitting connection test response with status and version
     */
    public Mono<OllamaConnectionTestResponse> testConnection(@NonNull String baseUrl, String apiKey) {
        String normalizedUrl = normalizeUrl(baseUrl);
        String versionUrl = normalizedUrl + "/api/version";
        log.debug("Testing Ollama connection at: '{}'", ValidationUtils.redactCredentialsFromUrl(versionUrl));

        return performAsyncGet(versionUrl, apiKey)
                .subscribeOn(Schedulers.boundedElastic())
                .map(response -> {
                    if (response.getStatus() == 200 && response.hasEntity()) {
                        String responseBody = response.readEntity(String.class);
                        OllamaVersionResponse versionResponse = JsonUtils.readValue(responseBody,
                                OllamaVersionResponse.class);
                        String version = versionResponse.version();
                        log.info("Successfully connected to Ollama instance, version: '{}'", version);

                        return createSuccessResponse(version);
                    } else {
                        String redactedUrl = ValidationUtils.redactCredentialsFromUrl(normalizedUrl);
                        String errorMsg = "Failed to connect to Ollama at " + redactedUrl + ": HTTP "
                                + response.getStatus();
                        log.warn("Failed to connect to Ollama at '{}': HTTP '{}'", redactedUrl, response.getStatus());
                        return createErrorResponse(errorMsg);
                    }
                })
                .onErrorResume(e -> {
                    log.error("Failed to connect to Ollama", e);
                    String errorMsg = "Failed to connect to Ollama due to an internal error";
                    return Mono.just(createErrorResponse(errorMsg));
                });
    }

    /**
     * Lists all models available on an Ollama instance.
     *
     * @param baseUrl Base URL of the Ollama instance (without /v1 suffix, e.g., http://localhost:11434)
     * @param apiKey  Optional API key for authenticated Ollama instances
     * @return Mono emitting list of available models (empty list on error)
     */
    public Mono<List<OllamaModel>> listModels(@NonNull String baseUrl, String apiKey) {
        String normalizedUrl = normalizeUrl(baseUrl);
        String tagsUrl = normalizedUrl + "/api/tags";
        log.debug("Fetching models from Ollama instance at: '{}'", ValidationUtils.redactCredentialsFromUrl(tagsUrl));

        return performAsyncGet(tagsUrl, apiKey)
                .subscribeOn(Schedulers.boundedElastic())
                .map(response -> {
                    if (response.getStatus() == 200 && response.hasEntity()) {
                        String responseBody = response.readEntity(String.class);
                        OllamaTagsResponse tagsResponse = JsonUtils.readValue(responseBody, OllamaTagsResponse.class);

                        List<OllamaModelResponse> tagModels = tagsResponse.models() != null
                                ? tagsResponse.models()
                                : Collections.emptyList();

                        List<OllamaModel> models = tagModels.stream()
                                .map(model -> OllamaModel.builder()
                                        .name(model.name())
                                        .size(model.size())
                                        .digest(model.digest())
                                        .modifiedAt(model.modifiedAt())
                                        .build())
                                .toList();

                        log.info("Found '{}' models on Ollama instance", models.size());
                        return models;
                    } else {
                        String redactedUrl = ValidationUtils.redactCredentialsFromUrl(normalizedUrl);
                        log.warn("Failed to fetch models from Ollama at '{}': HTTP '{}'", redactedUrl,
                                response.getStatus());
                        return Collections.<OllamaModel>emptyList();
                    }
                })
                .onErrorResume(e -> {
                    log.error("Failed to fetch models from Ollama", e);
                    return Mono.just(Collections.emptyList());
                });
    }

    /**
     * Performs an asynchronous HTTP GET request and wraps the result in a Mono.
     *
     * @param url    The URL to request
     * @param apiKey Optional API key for authorization
     * @return Mono emitting the HTTP response
     */
    private Mono<Response> performAsyncGet(String url, String apiKey) {
        return Mono.create(sink -> {
            var requestBuilder = httpClient.target(url)
                    .request(MediaType.APPLICATION_JSON);

            if (apiKey != null && !apiKey.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + apiKey);
            }

            requestBuilder.async().get(new InvocationCallback<Response>() {
                @Override
                public void completed(Response response) {
                    sink.success(response);
                }

                @Override
                public void failed(Throwable throwable) {
                    sink.error(throwable);
                }
            });
        });
    }

    /**
     * Normalizes Ollama URL by removing trailing slashes and /v1 suffix if present.
     * The frontend sends URLs with /v1 suffix (for OpenAI-compatible API), but
     * connection testing uses native endpoints (/api/version, /api/tags) which don't need /v1.
     *
     * @param url Raw URL input (may include /v1 suffix)
     * @return Normalized URL without /v1 suffix and trailing slashes
     */
    private String normalizeUrl(String url) {
        String normalized = url.trim();
        // Remove trailing slash
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        // Remove /v1 suffix if present (frontend sends URLs with /v1 for OpenAI compatibility)
        if (normalized.endsWith("/v1")) {
            normalized = normalized.substring(0, normalized.length() - 3);
        }
        return normalized;
    }

    private OllamaConnectionTestResponse createSuccessResponse(String version) {
        return OllamaConnectionTestResponse.builder()
                .connected(true)
                .version(version)
                .build();
    }

    private OllamaConnectionTestResponse createErrorResponse(String errorMessage) {
        return OllamaConnectionTestResponse.builder()
                .connected(false)
                .version(null)
                .errorMessage(errorMessage)
                .build();
    }

    // Internal DTOs for Ollama API responses
    private record OllamaVersionResponse(@JsonProperty("version") String version) {
    }

    private record OllamaTagsResponse(@JsonProperty("models") List<OllamaModelResponse> models) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OllamaModelResponse(
            @JsonProperty("name") String name,
            @JsonProperty("size") Long size,
            @JsonProperty("digest") String digest,
            @JsonProperty("modified_at") Instant modifiedAt) {
    }
}
