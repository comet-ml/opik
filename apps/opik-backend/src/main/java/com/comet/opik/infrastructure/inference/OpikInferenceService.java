package com.comet.opik.infrastructure.inference;

import com.comet.opik.infrastructure.FeatureFlags;
import com.comet.opik.infrastructure.OpikInferenceConfig;
import com.comet.opik.infrastructure.RetriableHttpClient;
import com.comet.opik.utils.RetryUtils;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.ServerErrorException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The one component that talks to opik-inference: it sends {@code POST /v1/decide} with the service token and the
 * configured deadline, and hands the answers back. It holds no guardrails logic; the questions and the thresholds
 * live in {@link com.comet.opik.domain.GuardrailsValidationService}.
 *
 * <p>Errors keep the upstream status: 4xx as {@link ClientErrorException}, 5xx as {@link ServerErrorException}.
 * What the service marks retryable, plus 429 and 502, is retried in-process; 503 and 504 are retried by
 * {@link RetriableHttpClient} itself.
 *
 * <p>Provided by {@link OpikInferenceModule} rather than injected directly: the {@code @Config} qualifier has to
 * sit on a hand-written parameter, since the Docker build compiles without {@code lombok.config} and Lombok then
 * drops it from generated constructors.
 */
@Slf4j
@RequiredArgsConstructor
public class OpikInferenceService {

    static final String DECIDE_PATH = "/v1/decide";
    static final String REALTIME_LANE = "realtime";
    private static final Duration MIN_BACKOFF = Duration.ofMillis(100);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(2);
    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(429, 502);
    private static final int MAX_ERROR_MESSAGE_LENGTH = 512;

    private final @NonNull RetriableHttpClient client;
    private final @NonNull OpikInferenceConfig config;
    private final @NonNull FeatureFlags featureFlags;

    /**
     * One decide call: every question is answered about the same {@code state}. Cold; the feature toggle is
     * checked on subscription, so a disabled service fails the pipeline with a 403 rather than at wiring time.
     *
     * @param orgId the Opik workspace id, sent as the service's tenant key {@code org_id}; see
     *        {@link DecideRequest#orgId()}
     */
    public Mono<DecideResponse> decide(@NonNull String orgId, @NonNull String state,
            @NonNull Map<String, DecideQuestion> questions) {
        return Mono.defer(() -> {
            featureFlags.checkOpikInferenceEnabled();
            var request = DecideRequest.builder()
                    .orgId(orgId)
                    .state(state)
                    .questions(questions)
                    .lane(REALTIME_LANE)
                    .deadlineMs(config.getDeadline().toMilliseconds())
                    .cache(true)
                    .build();
            var httpRequest = RetriableHttpClient.Request.<DecideResponse>builder()
                    .requestFunction(httpClient -> httpClient.target(config.getUrl()).path(DECIDE_PATH))
                    .requestCustomizer(
                            builder -> builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + config.getApiKey()))
                    .retryPolicy(RetryUtils.handleHttpErrors(config.getMaxRetryAttempts(), MIN_BACKOFF, MAX_BACKOFF))
                    .body(Entity.json(request))
                    .connectTimeout(config.getConnectTimeout().toJavaDuration())
                    .readTimeout(config.getReadTimeout().toJavaDuration())
                    .responseFunction(this::processResponse)
                    .build();
            return client.executePostWithRetry(httpRequest);
        });
    }

    private DecideResponse processResponse(Response response) {
        int status = response.getStatus();
        if (response.getStatusInfo().getFamily() == Response.Status.Family.SUCCESSFUL) {
            var body = response.hasEntity() && response.bufferEntity()
                    ? response.readEntity(DecideResponse.class)
                    : null;
            if (body == null || body.result() == null || body.result().answers() == null) {
                throw new InternalServerErrorException(
                        "opik-inference returned an empty decide response, status '%s'".formatted(status));
            }
            return body;
        }
        var error = extractError(response);
        var message = "opik-inference decide request failed, status '%s', code '%s', message '%s'".formatted(
                status,
                error.map(DecideErrorResponse.Error::code).orElse("unknown"),
                error.map(DecideErrorResponse.Error::message)
                        .filter(StringUtils::isNotBlank)
                        .map(text -> StringUtils.truncate(text, MAX_ERROR_MESSAGE_LENGTH))
                        .orElseGet(() -> rawBody(response)));
        boolean retryable = error.map(DecideErrorResponse.Error::retryable).orElse(false)
                || RETRYABLE_STATUSES.contains(status);
        if (retryable) {
            throw new RetryUtils.RetryableHttpException(message, status);
        }
        switch (response.getStatusInfo().getFamily()) {
            case CLIENT_ERROR -> throw new ClientErrorException(message, status);
            // ServerErrorException rejects any non-5xx status with an IllegalArgumentException.
            case SERVER_ERROR -> throw new ServerErrorException(message, status);
            default -> throw new WebApplicationException(message, status);
        }
    }

    private Optional<DecideErrorResponse.Error> extractError(Response response) {
        if (!response.hasEntity() || !response.bufferEntity()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(response.readEntity(DecideErrorResponse.class))
                    .map(DecideErrorResponse::error);
        } catch (RuntimeException parseError) {
            // Expected when the body is not the structured envelope; the raw body is reported instead.
            log.debug("Failed to parse opik-inference error response, falling back to raw body", parseError);
            return Optional.empty();
        }
    }

    private String rawBody(Response response) {
        if (!response.hasEntity() || !response.bufferEntity()) {
            return "<no body>";
        }
        try {
            return StringUtils.truncate(response.readEntity(String.class), MAX_ERROR_MESSAGE_LENGTH);
        } catch (RuntimeException unreadable) {
            return "<unreadable body>";
        }
    }
}
