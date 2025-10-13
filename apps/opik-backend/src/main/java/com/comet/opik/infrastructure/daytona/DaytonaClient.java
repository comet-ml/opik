package com.comet.opik.infrastructure.daytona;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.RetriableHttpClient;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.RetryUtils;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class DaytonaClient {

    private static final String SANDBOX_ENDPOINT = "%s/sandbox";
    private static final String CREATE_SESSION_ENDPOINT = "%s/toolbox/%s/toolbox/process/session";
    private static final String EXECUTE_IN_SESSION_ENDPOINT = "%s/toolbox/%s/toolbox/process/session/%s/exec";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String SCRIPT_PATH_IN_SNAPSHOT = "/app/optimization_script.py";

    private final @NonNull RetriableHttpClient client;
    private final @NonNull OpikConfiguration config;

    /**
     * Creates a new Daytona sandbox with environment variables and labels.
     *
     * @param runId         The optimization run ID
     * @param userId        The user ID creating the run
     * @param workspaceId   The workspace ID
     * @param apiKey        The user's API key for authentication
     * @param opikUrl       The Opik backend URL override
     * @return The created sandbox ID
     */
    public String createSandbox(
            @NonNull UUID runId,
            @NonNull String userId,
            @NonNull String workspaceId,
            @NonNull String apiKey,
            String opikUrl) {

        log.info("Creating Daytona sandbox for optimization run '{}', user '{}', workspace '{}'",
                runId, userId, workspaceId);

        DaytonaConfig daytonaConfig = config.getDaytona();
        String endpoint = SANDBOX_ENDPOINT.formatted(daytonaConfig.getUrl());

        // Build environment variables
        Map<String, String> envVars = Map.of(
                "OPIK_API_KEY", apiKey,
                "OPIK_WORKSPACE", workspaceId,
                "OPIK_URL_OVERRIDE", opikUrl != null ? opikUrl : "");

        // Build labels
        Map<String, String> labels = Map.of(
                "optimization_run_id", runId.toString(),
                "user_id", userId,
                "workspace_id", workspaceId);

        DaytonaSandboxRequest request = DaytonaSandboxRequest.builder()
                .snapshot(daytonaConfig.getSnapshotName())
                .env(envVars)
                .labels(labels)
                .build();

        // Build headers
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        if (daytonaConfig.getApiToken() != null && !daytonaConfig.getApiToken().isEmpty()) {
            headers.put(AUTHORIZATION_HEADER, "Bearer " + daytonaConfig.getApiToken());
        }

        // Log request details
        log.info("Daytona API Request Details:");
        log.info("  URL: {}", endpoint);
        log.info("  Headers: {}", headers);
        log.info("  Payload: {}", JsonUtils.writeValueAsString(request));
        log.info("  Environment Variables: {}", envVars);
        log.info("  Labels: {}", labels);

        log.info("Sending POST request to Daytona API...");

        return RetriableHttpClient.newPost(c -> c.target(endpoint))
                .withRetryPolicy(RetryUtils.handleHttpErrors(
                        daytonaConfig.getMaxRetryAttempts(),
                        daytonaConfig.getMinRetryDelay().toJavaDuration(),
                        daytonaConfig.getMaxRetryDelay().toJavaDuration()))
                .withHeaders(headers)
                .withRequestBody(Entity.json(request))
                .withResponse(this::processResponse)
                .execute(client);
    }

    private String processResponse(Response response) {
        int statusCode = response.getStatus();
        Response.StatusType statusInfo = response.getStatusInfo();

        log.info("Received response from Daytona API:");
        log.info("  Status Code: {}", statusCode);
        log.info("  Status Info: {}", statusInfo);

        if (statusInfo.getFamily() == Response.Status.Family.SUCCESSFUL) {
            String rawResponse = response.readEntity(String.class);
            log.info("  Response Body: {}", rawResponse);

            DaytonaSandboxResponse sandboxResponse = JsonUtils.readValue(rawResponse, DaytonaSandboxResponse.class);
            log.info("Successfully created Daytona sandbox with ID: '{}'", sandboxResponse.id());
            return sandboxResponse.id();
        }

        String errorMessage = extractErrorMessage(response);
        log.info("  Error Response Body: {}", errorMessage);
        log.error("Failed to create Daytona sandbox (HTTP {}): {}", statusCode, errorMessage);
        throw new InternalServerErrorException(
                "Failed to create Daytona sandbox (HTTP " + statusCode + "): " + errorMessage);
    }

    private String extractErrorMessage(Response response) {
        String errorMessage = "Unknown error creating Daytona sandbox";

        if (response.hasEntity() && response.bufferEntity()) {
            try {
                errorMessage = response.readEntity(String.class);
            } catch (RuntimeException e) {
                log.warn("Failed to parse error response", e);
            }
        }

        return errorMessage;
    }

    /**
     * Creates a session in the Daytona sandbox.
     * Retries up to 10 times with 200ms delay between attempts.
     *
     * @param sandboxId The sandbox ID
     * @param sessionId The session ID to create
     * @return The session ID
     */
    public String createSession(@NonNull String sandboxId, @NonNull String sessionId) {
        log.info("Creating session '{}' in sandbox '{}'", sessionId, sandboxId);

        DaytonaConfig daytonaConfig = config.getDaytona();
        String endpoint = CREATE_SESSION_ENDPOINT.formatted(daytonaConfig.getUrl(), sandboxId);

        DaytonaCreateSessionRequest request = DaytonaCreateSessionRequest.builder()
                .sessionId(sessionId)
                .build();

        // Build headers
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        if (daytonaConfig.getApiToken() != null && !daytonaConfig.getApiToken().isEmpty()) {
            headers.put(AUTHORIZATION_HEADER, "Bearer " + daytonaConfig.getApiToken());
        }

        // Log request details
        log.info("Daytona Create Session Request Details:");
        log.info("  URL: {}", endpoint);
        log.info("  Headers: {}", headers);
        log.info("  Session ID: {}", sessionId);

        log.info("Sending POST request to Daytona API to create session (will retry up to 10 times with 200ms delay)...");

        return RetriableHttpClient.newPost(c -> c.target(endpoint))
                .withRetryPolicy(RetryUtils.handleHttpErrors(
                        10,  // Retry up to 10 times for sandbox readiness
                        java.time.Duration.ofMillis(200),  // 200ms delay between retries
                        java.time.Duration.ofMillis(200)))  // Keep delay constant at 200ms
                .withHeaders(headers)
                .withRequestBody(Entity.json(request))
                .withResponse(this::processCreateSessionResponse)
                .execute(client);
    }

    private String processCreateSessionResponse(Response response) {
        int statusCode = response.getStatus();
        Response.StatusType statusInfo = response.getStatusInfo();

        log.info("Received create session response from Daytona API:");
        log.info("  Status Code: {}", statusCode);
        log.info("  Status Info: {}", statusInfo);

        // Treat 404 as retriable error (sandbox may not be ready yet)
        if (statusCode == 404) {
            if (response.hasEntity()) {
                response.bufferEntity();
            }
            String errorMessage = extractErrorMessage(response);
            log.info("  Error Response Body: {}", errorMessage);
            log.info("Sandbox not ready for session creation (404), will retry...");
            throw new RetryUtils.RetryableHttpException(
                    "Sandbox not ready for session (HTTP 404): " + errorMessage, statusCode);
        }

        if (statusInfo.getFamily() == Response.Status.Family.SUCCESSFUL) {
            String rawResponse = response.readEntity(String.class);
            log.info("  Response Body: {}", rawResponse);

            // Try to parse response, but session creation might return empty response
            try {
                DaytonaCreateSessionResponse sessionResponse = JsonUtils.readValue(rawResponse, DaytonaCreateSessionResponse.class);
                log.info("Successfully created session: '{}'", sessionResponse.sessionId());
                return sessionResponse.sessionId();
            } catch (Exception e) {
                // If parsing fails, assume success and return the session ID we sent
                log.info("Session created successfully (empty response)");
                return null; // Caller will use the sessionId they provided
            }
        }

        String errorMessage = extractErrorMessage(response);
        log.info("  Error Response Body: {}", errorMessage);
        log.error("Failed to create session (HTTP {}): {}", statusCode, errorMessage);
        throw new InternalServerErrorException(
                "Failed to create session (HTTP " + statusCode + "): " + errorMessage);
    }

    /**
     * Executes the optimization script in the Daytona sandbox.
     * The script is pre-installed in the snapshot at /app/optimization_script.py
     *
     * @param sandboxId The sandbox ID to execute the command in
     * @param runId The optimization studio run ID (used for session ID and logs)
     * @param optimizationId The optimization ID for internal optimizer tracking
     * @param datasetName The dataset name to run optimization on
     * @param algorithm The optimization algorithm to use
     * @param metric The metric to optimize for
     * @param promptJson The prompt configuration as JSON string
     * @param apiKey The API key for authentication
     * @param workspaceId The workspace ID
     * @return The execution response
     */
    public DaytonaExecuteCommandResponse executeOptimization(
            @NonNull String sandboxId,
            @NonNull UUID runId,
            @NonNull UUID optimizationId,
            @NonNull String datasetName,
            @NonNull String algorithm,
            @NonNull String metric,
            String promptJson,
            @NonNull String apiKey,
            @NonNull String workspaceId) {
        log.info("Executing optimization script in sandbox '{}'", sandboxId);

        // Step 1: Create session
        String sessionId = "optimization-" + runId.toString();
        createSession(sandboxId, sessionId);

        // Step 2: Execute the pre-installed optimization script
        DaytonaConfig daytonaConfig = config.getDaytona();
        String endpoint = EXECUTE_IN_SESSION_ENDPOINT.formatted(daytonaConfig.getUrl(), sandboxId, sessionId);

        // Build command with arguments
        // Escape single quotes in JSON for shell safety
        String escapedPrompt = promptJson != null ? promptJson.replace("'", "'\\''") : "[]";

        // Build environment variable exports (base64 encoded like Daytona Python SDK)
        String envExports = String.format(
                "export OPIK_API_KEY=$(echo '%s' | base64 -d);" +
                "export OPIK_WORKSPACE=$(echo '%s' | base64 -d);" +
                "export OPIK_URL_OVERRIDE=$(echo '%s' | base64 -d);",
                Base64.getEncoder().encodeToString(apiKey.getBytes(StandardCharsets.UTF_8)),
                Base64.getEncoder().encodeToString(workspaceId.getBytes(StandardCharsets.UTF_8)),
                Base64.getEncoder().encodeToString((config.getDaytona().getOpikUrl() != null ? config.getDaytona().getOpikUrl() : "").getBytes(StandardCharsets.UTF_8))
        );

        // Build the base python command
        String pythonCommand = String.format(
                "python %s --dataset_name %s --optimization_studio_run_id %s --optimization_id %s --algorithm %s --metric %s --prompt '%s'",
                SCRIPT_PATH_IN_SNAPSHOT,
                datasetName,
                runId.toString(),
                optimizationId.toString(),
                algorithm.toLowerCase(),
                metric,
                escapedPrompt);

        // Prepend env exports to command and wrap in sh -c (similar to Daytona Python SDK)
        String fullCommand = envExports + " " + pythonCommand;
        String command = String.format("sh -c \"%s\"", fullCommand.replace("\"", "\\\""));

        DaytonaExecuteCommandRequest request = DaytonaExecuteCommandRequest.builder()
                .command(command)
                .runAsync(true)
                .build();

        // Build headers
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        if (daytonaConfig.getApiToken() != null && !daytonaConfig.getApiToken().isEmpty()) {
            headers.put(AUTHORIZATION_HEADER, "Bearer " + daytonaConfig.getApiToken());
        }

        // Log request details
        log.info("Daytona Execute Command Request Details:");
        log.info("  URL: {}", endpoint);
        log.info("  Headers: {}", headers);
        log.info("  Session ID: {}", sessionId);
        log.info("  Full command: {}", command);
        log.info("  Full request body: {}", JsonUtils.writeValueAsString(request));

        log.info("Sending POST request to Daytona API to execute optimization (will retry up to 10 times with 200ms delay)...");

        try {
            DaytonaExecuteCommandResponse executeResponse = RetriableHttpClient.newPost(c -> c.target(endpoint))
                    .withRetryPolicy(RetryUtils.handleHttpErrors(
                            10,  // Retry up to 10 times for sandbox readiness
                            java.time.Duration.ofMillis(200),  // 200ms delay between retries
                            java.time.Duration.ofMillis(200)))  // Keep delay constant at 200ms
                    .withHeaders(headers)
                    .withRequestBody(Entity.json(request))
                    .withResponse(this::processExecuteCommandResponse)
                    .execute(client);
            log.info("Execute command request completed successfully");
            return executeResponse;
        } catch (Exception e) {
            log.error("Execute command request failed with exception", e);
            throw e;
        }
    }

    private DaytonaExecuteCommandResponse processExecuteCommandResponse(Response response) {
        log.info("processExecuteCommandResponse called - processing response...");
        int statusCode = response.getStatus();
        Response.StatusType statusInfo = response.getStatusInfo();

        log.info("Received execute command response from Daytona API:");
        log.info("  Status Code: {}", statusCode);
        log.info("  Status Info: {}", statusInfo);

        // Treat 404 as retriable error (sandbox may not be ready yet)
        // Check this BEFORE reading entity to allow retry logic to work
        if (statusCode == 404) {
            // Buffer the response body for retry
            if (response.hasEntity()) {
                response.bufferEntity();
            }
            String errorMessage = extractErrorMessage(response);
            log.info("  Error Response Body: {}", errorMessage);
            log.info("Sandbox not ready (404), will retry...");
            throw new RetryUtils.RetryableHttpException(
                    "Sandbox not ready (HTTP 404): " + errorMessage, statusCode);
        }

        if (statusInfo.getFamily() == Response.Status.Family.SUCCESSFUL) {
            String rawResponse = response.readEntity(String.class);
            log.info("  Response Body: {}", rawResponse);

            DaytonaExecuteCommandResponse commandResponse = JsonUtils.readValue(rawResponse, DaytonaExecuteCommandResponse.class);
            log.info("Successfully executed command in sandbox. Command ID: {}, Exit code: {}",
                    commandResponse.cmdId(), commandResponse.exitCode());
            return commandResponse;
        }

        String errorMessage = extractErrorMessage(response);
        log.info("  Error Response Body: {}", errorMessage);
        log.error("Failed to execute command in sandbox (HTTP {}): {}", statusCode, errorMessage);
        throw new InternalServerErrorException(
                "Failed to execute command in sandbox (HTTP " + statusCode + "): " + errorMessage);
    }

}
