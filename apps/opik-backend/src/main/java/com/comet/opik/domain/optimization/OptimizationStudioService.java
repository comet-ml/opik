package com.comet.opik.domain.optimization;

import com.comet.opik.api.OptimizationStudioConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.RetriableHttpClient;
import com.comet.opik.utils.RetryUtils;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class OptimizationStudioService {

    private static final String URL_TEMPLATE = "%s/v1/private/studio/code";

    private final @NonNull RetriableHttpClient client;
    private final @NonNull OpikConfiguration config;

    public Mono<String> generateCode(@NonNull OptimizationStudioConfig studioConfig) {
        String url = URL_TEMPLATE.formatted(config.getPythonEvaluator().getUrl());
        log.info("Generating code via Python backend at URL: {}", url);
        return Mono.fromCallable(() -> executeWithRetry(Entity.json(studioConfig)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Executes the HTTP request with retry logic.
     * Note: This method runs on the boundedElastic scheduler when called from generateCode.
     *
     * @param request The request entity to send
     * @return The response body as a String
     */
    private String executeWithRetry(Entity<?> request) {
        String url = URL_TEMPLATE.formatted(config.getPythonEvaluator().getUrl());
        log.debug("Calling Python backend at URL: {}", url);
        return RetriableHttpClient.newPost(c -> c.target(url))
                .withRetryPolicy(RetryUtils.handleHttpErrors(
                        config.getPythonEvaluator().getMaxRetryAttempts(),
                        config.getPythonEvaluator().getMinRetryDelay().toJavaDuration(),
                        config.getPythonEvaluator().getMaxRetryDelay().toJavaDuration()))
                .withRequestBody(request)
                .withResponse(this::processResponse)
                .execute(client);
    }

    /**
     * Processes the HTTP response and extracts the response body or throws appropriate exceptions.
     * Note: This method runs on the boundedElastic scheduler when called from executeWithRetry.
     *
     * @param response The HTTP response to process
     * @return The response body as a String
     * @throws BadRequestException if the response status is 400
     * @throws InternalServerErrorException if the response status indicates a server error
     */
    private String processResponse(Response response) {
        int statusCode = response.getStatus();
        Response.StatusType statusInfo = response.getStatusInfo();

        if (statusInfo.getFamily() == Response.Status.Family.SUCCESSFUL) {
            return response.readEntity(String.class);
        }

        String errorMessage = extractErrorMessage(response);
        log.error("Python code generation failed with HTTP {}: {}", statusCode, errorMessage);

        if (statusCode == 400) {
            throw new BadRequestException(errorMessage);
        }

        throw new InternalServerErrorException(
                "Python code generation failed (HTTP " + statusCode + "): " + errorMessage);
    }

    private String extractErrorMessage(Response response) {
        String errorMessage = "Unknown error during code generation";

        if (response.hasEntity() && response.bufferEntity()) {
            try {
                String body = response.readEntity(String.class);
                if (body != null && !body.isBlank()) {
                    errorMessage = body;
                }
            } catch (jakarta.ws.rs.ProcessingException | IllegalStateException parseErrorResponse) {
                log.warn("Failed to parse error response", parseErrorResponse);
            }
        }

        return errorMessage;
    }
}
