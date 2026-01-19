package com.comet.opik.domain.optimization;

import com.comet.opik.api.OptimizationStudioConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.RetriableHttpClient;
import com.comet.opik.utils.RetryUtils;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class OptimizationStudioService {

    private static final String URL_TEMPLATE = "%s/v1/private/studio/code";

    private final @NonNull RetriableHttpClient client;
    private final @NonNull OpikConfiguration config;

    public String generateCode(@NonNull OptimizationStudioConfig studioConfig) {
        String url = URL_TEMPLATE.formatted(config.getPythonEvaluator().getUrl());
        log.info("Generating code via Python backend at URL: {}", url);
        return executeWithRetry(Entity.json(studioConfig));
    }

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
                errorMessage = response.readEntity(ErrorMessage.class).getMessage();
            } catch (RuntimeException parseErrorResponse) {
                log.warn("Failed to parse error response, falling back to parsing string", parseErrorResponse);
                try {
                    errorMessage = response.readEntity(String.class);
                } catch (RuntimeException parseStringResponse) {
                    log.warn("Failed to parse error string response", parseStringResponse);
                }
            }
        }

        return errorMessage;
    }
}
