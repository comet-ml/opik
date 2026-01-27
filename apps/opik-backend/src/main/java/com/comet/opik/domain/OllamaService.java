package com.comet.opik.domain;

import com.comet.opik.api.OllamaConnectionTestResponse;
import com.comet.opik.api.OllamaModel;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
     * Tests connection to an Ollama instance and retrieves server version.
     *
     * @param baseUrl Base URL of the Ollama instance (without /v1 suffix, e.g., http://localhost:11434)
     * @return Connection test response with status and version
     */
    public OllamaConnectionTestResponse testConnection(@NonNull String baseUrl) {
        String normalizedUrl = normalizeUrl(baseUrl);
        String versionUrl = normalizedUrl + "/api/version";
        log.debug("Testing Ollama connection at: {}", versionUrl);

        try {
            Response response = httpClient.target(versionUrl)
                    .request(MediaType.APPLICATION_JSON)
                    .get();

            if (response.getStatus() == 200 && response.hasEntity()) {
                String responseBody = response.readEntity(String.class);
                OllamaVersionResponse versionResponse = JsonUtils.readValue(responseBody,
                        OllamaVersionResponse.class);
                String version = versionResponse.version();
                log.info("Successfully connected to Ollama instance, version: {}", version);

                return createSuccessResponse(version);
            } else {
                String errorMsg = "Failed to connect to Ollama: HTTP " + response.getStatus();
                log.warn(errorMsg);
                return createErrorResponse(errorMsg);
            }
        } catch (Exception e) {
            log.error("Failed to connect to Ollama at {}", normalizedUrl, e);
            String errorMsg = "Failed to connect to Ollama: " + e.getMessage();
            return createErrorResponse(errorMsg);
        }
    }

    /**
     * Lists all models available on an Ollama instance.
     *
     * @param baseUrl Base URL of the Ollama instance (without /v1 suffix, e.g., http://localhost:11434)
     * @return List of available models (empty list on error)
     */
    public List<OllamaModel> listModels(@NonNull String baseUrl) {
        String normalizedUrl = normalizeUrl(baseUrl);
        String tagsUrl = normalizedUrl + "/api/tags";
        log.debug("Fetching models from Ollama instance at: {}", tagsUrl);

        try {
            Response response = httpClient.target(tagsUrl)
                    .request(MediaType.APPLICATION_JSON)
                    .get();

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

                log.info("Found {} models on Ollama instance", models.size());
                return models;
            } else {
                log.warn("Failed to fetch models from Ollama: HTTP {}", response.getStatus());
                return Collections.emptyList();
            }
        } catch (Exception e) {
            log.error("Failed to fetch models from Ollama at {}", normalizedUrl, e);
            return Collections.emptyList();
        }
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
