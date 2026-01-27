package com.comet.opik.domain;

import com.comet.opik.api.OllamaConnectionTestResponse;
import com.comet.opik.api.OllamaModel;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Service for interacting with Ollama instances.
 * Provides functionality to test connections and discover available models.
 */
@Singleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class OllamaService {

    private final @NonNull Client httpClient;
    private final @NonNull ObjectMapper objectMapper;

    /**
     * Tests connection to an Ollama instance and retrieves server version.
     *
     * @param baseUrl Base URL of the Ollama instance
     * @return Connection test response with status and version
     */
    public OllamaConnectionTestResponse testConnection(@NonNull String baseUrl) {
        String normalizedUrl = normalizeUrl(baseUrl);
        String versionUrl = normalizedUrl + "/api/version";

        log.debug("Testing connection to Ollama at: {}", versionUrl);

        try {
            Response response = httpClient.target(versionUrl)
                    .request(MediaType.APPLICATION_JSON)
                    .get();

            if (response.getStatus() == 200 && response.hasEntity()) {
                String responseBody = response.readEntity(String.class);
                OllamaVersionResponse versionResponse = objectMapper.readValue(responseBody,
                        OllamaVersionResponse.class);
                log.info("Successfully connected to Ollama version: {}", versionResponse.version());

                return OllamaConnectionTestResponse.builder()
                        .connected(true)
                        .version(versionResponse.version())
                        .build();
            } else {
                String errorMsg = "Failed to connect to Ollama: HTTP " + response.getStatus();
                log.warn(errorMsg);
                return OllamaConnectionTestResponse.builder()
                        .connected(false)
                        .errorMessage(errorMsg)
                        .build();
            }
        } catch (Exception e) {
            String errorMsg = "Failed to connect to Ollama at " + normalizedUrl + ": " + e.getMessage();
            log.error(errorMsg, e);
            return OllamaConnectionTestResponse.builder()
                    .connected(false)
                    .errorMessage(errorMsg)
                    .build();
        }
    }

    /**
     * Lists all models available on an Ollama instance.
     *
     * @param baseUrl Base URL of the Ollama instance
     * @return List of available models
     */
    public List<OllamaModel> listModels(@NonNull String baseUrl) {
        String normalizedUrl = normalizeUrl(baseUrl);
        String tagsUrl = normalizedUrl + "/api/tags";

        log.debug("Fetching models from Ollama at: {}", tagsUrl);

        try {
            Response response = httpClient.target(tagsUrl)
                    .request(MediaType.APPLICATION_JSON)
                    .get();

            if (response.getStatus() == 200 && response.hasEntity()) {
                String responseBody = response.readEntity(String.class);
                OllamaTagsResponse tagsResponse = objectMapper.readValue(responseBody, OllamaTagsResponse.class);

                List<OllamaModel> models = tagsResponse.models().stream()
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
            log.error("Failed to fetch models from Ollama at {}: {}", normalizedUrl, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Normalizes Ollama URL by removing trailing slashes and ensuring proper format.
     *
     * @param url Raw URL input
     * @return Normalized URL
     */
    private String normalizeUrl(String url) {
        String normalized = url.trim();
        // Remove trailing slash
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    // Internal DTOs for Ollama API responses
    private record OllamaVersionResponse(@JsonProperty("version") String version) {
    }

    private record OllamaTagsResponse(@JsonProperty("models") List<OllamaModelResponse> models) {
    }

    private record OllamaModelResponse(
            @JsonProperty("name") String name,
            @JsonProperty("size") Long size,
            @JsonProperty("digest") String digest,
            @JsonProperty("modified_at") Instant modifiedAt) {
    }
}
