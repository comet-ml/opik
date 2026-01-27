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
 * Service for interacting with Ollama API v1 instances.
 * Provides functionality to test connections and discover available models.
 *
 * <p>Ollama API v1 provides two types of endpoints:
 * <ul>
 *   <li>Native Ollama API: /api/version, /api/tags (used for management)</li>
 *   <li>OpenAI-compatible API: /v1/chat/completions (used for LLM inference)</li>
 * </ul>
 *
 * <p>This service uses the native Ollama API endpoints for connection testing
 * and model discovery. For actual LLM inference, the base URL with /v1 suffix
 * should be used with OpenAI-compatible clients.
 */
@Singleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class OllamaService {

    private final @NonNull Client httpClient;

    /**
     * Tests connection to an Ollama API v1 instance and retrieves server version.
     * Uses the native Ollama API endpoint /api/version.
     *
     * <p>Validates that the Ollama instance is API v1-compatible (version >= 0.1.0).
     *
     * @param baseUrl Base URL of the Ollama instance (without /v1 suffix, e.g., http://localhost:11434)
     * @return Connection test response with status and version
     */
    public OllamaConnectionTestResponse testConnection(@NonNull String baseUrl) {
        String normalizedUrl = normalizeUrl(baseUrl);
        VersionCheckResult versionCheck = checkVersion(normalizedUrl);

        if (!versionCheck.success()) {
            return OllamaConnectionTestResponse.builder()
                    .connected(false)
                    .version(versionCheck.version())
                    .errorMessage(versionCheck.errorMessage())
                    .build();
        }

        String version = versionCheck.version();
        log.info("Successfully connected to Ollama API v1-compatible instance, version: {}", version);

        return OllamaConnectionTestResponse.builder()
                .connected(true)
                .version(version)
                .build();
    }

    /**
     * Checks Ollama version and validates v1 compatibility.
     *
     * @param normalizedUrl Normalized base URL (without trailing slash)
     * @return Version check result with success status, version, and error message if any
     */
    private VersionCheckResult checkVersion(String normalizedUrl) {
        String versionUrl = normalizedUrl + "/api/version";
        log.debug("Checking Ollama version at: {}", versionUrl);

        try {
            Response response = httpClient.target(versionUrl)
                    .request(MediaType.APPLICATION_JSON)
                    .get();

            if (response.getStatus() == 200 && response.hasEntity()) {
                String responseBody = response.readEntity(String.class);
                OllamaVersionResponse versionResponse = JsonUtils.readValue(responseBody,
                        OllamaVersionResponse.class);
                String version = versionResponse.version();

                // Validate that the Ollama instance supports API v1 (OpenAI-compatible API)
                if (!isV1Compatible(version)) {
                    String errorMsg = String.format(
                            "Ollama version %s is not compatible with API v1. "
                                    + "Ollama API v1 (OpenAI-compatible) requires version 0.1.0 or higher. "
                                    + "Please upgrade your Ollama instance.",
                            version);
                    log.warn(errorMsg);
                    return new VersionCheckResult(false, version, errorMsg);
                }

                return new VersionCheckResult(true, version, null);
            } else {
                String errorMsg = "Failed to connect to Ollama: HTTP " + response.getStatus();
                log.warn(errorMsg);
                return new VersionCheckResult(false, null, errorMsg);
            }
        } catch (Exception e) {
            String errorMsg = "Failed to connect to Ollama at " + normalizedUrl + ": " + e.getMessage();
            log.error(errorMsg, e);
            return new VersionCheckResult(false, null, errorMsg);
        }
    }

    /**
     * Result of version compatibility check.
     */
    private record VersionCheckResult(boolean success, String version, String errorMessage) {
    }

    /**
     * Lists all models available on an Ollama API v1 instance.
     * Uses the native Ollama API endpoint /api/tags.
     *
     * <p><strong>Note:</strong> This method requires Ollama API v1-compatible version (>= 0.1.0).
     * Version compatibility is validated before listing models.
     *
     * @param baseUrl Base URL of the Ollama instance (without /v1 suffix, e.g., http://localhost:11434)
     * @return List of available models (empty list if version is not v1-compatible or on error)
     */
    public List<OllamaModel> listModels(@NonNull String baseUrl) {
        String normalizedUrl = normalizeUrl(baseUrl);

        // Validate version compatibility first
        VersionCheckResult versionCheck = checkVersion(normalizedUrl);
        if (!versionCheck.success()) {
            if (versionCheck.errorMessage() != null
                    && versionCheck.errorMessage().contains("not compatible with API v1")) {
                log.error("Cannot list models from Ollama API v1-incompatible instance: {}",
                        versionCheck.errorMessage());
            } else {
                log.warn("Cannot list models: {}", versionCheck.errorMessage());
            }
            return Collections.emptyList();
        }

        String tagsUrl = normalizedUrl + "/api/tags";
        log.debug("Fetching models from Ollama API v1 instance at: {}", tagsUrl);

        try {
            Response response = httpClient.target(tagsUrl)
                    .request(MediaType.APPLICATION_JSON)
                    .get();

            if (response.getStatus() == 200 && response.hasEntity()) {
                String responseBody = response.readEntity(String.class);
                OllamaTagsResponse tagsResponse = JsonUtils.readValue(responseBody, OllamaTagsResponse.class);

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

    /**
     * Validates if the Ollama version is compatible with API v1.
     * Ollama API v1 (OpenAI-compatible API) was introduced in version 0.1.0.
     *
     * @param version Version string from Ollama (e.g., "0.1.27", "1.0.0")
     * @return true if version is >= 0.1.0, false otherwise
     */
    private boolean isV1Compatible(String version) {
        if (version == null || version.trim().isEmpty()) {
            return false;
        }

        try {
            // Parse semantic version (major.minor.patch)
            String[] parts = version.trim().split("\\.");
            if (parts.length < 2) {
                log.warn("Invalid version format: {}", version);
                return false;
            }

            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);

            // Version >= 0.1.0 is required for API v1
            return major > 0 || (major == 0 && minor >= 1);
        } catch (NumberFormatException e) {
            log.warn("Failed to parse Ollama version '{}': {}", version, e.getMessage());
            return false;
        }
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
