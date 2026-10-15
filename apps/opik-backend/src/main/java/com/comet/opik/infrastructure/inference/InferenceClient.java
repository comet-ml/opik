package com.comet.opik.infrastructure.inference;

import com.comet.opik.infrastructure.AiLabelsConfig;
import com.google.common.base.Preconditions;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.InvocationCallback;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.glassfish.jersey.client.ClientProperties;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Function;

/**
 * Client for the opik-inference v1 batch API. Its retries mirror the Python client's: up to 2, backing off 0.1 s then
 * 0.2 s or honoring {@code Retry-After}, except that a submit answered with {@code quota_exceeded} fails at once, so
 * the caller can back that one workspace off.
 * <p>
 * Constructed whether or not ai-labels is enabled: the url and credential are only required when a call is made.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class InferenceClient {

    static final int MAX_RETRIES = 2;
    static final Duration BASE_BACKOFF = Duration.ofMillis(100);
    public static final int MAX_BATCH_ITEMS = 10_000;

    /**
     * The service reads and enqueues the whole body before it answers a submit, so the read timeout grows by a second
     * per MiB sent.
     */
    private static final long SUBMIT_BYTES_PER_SECOND = 1024L * 1024;

    private static final String BATCH_DECIDE_PATH = "v1/batch/decide";
    private static final String BATCH_GENERATE_PATH = "v1/batch/generate";
    private static final String JOB_PATH = "v1/batch/jobs/{job_id}";

    private final @NonNull Client client;
    private final @NonNull @Config("aiLabels") AiLabelsConfig config;

    public Mono<BatchSubmitResponse> submitDecide(@NonNull BatchDecideRequest request) {
        return Mono.defer(() -> {
            var withId = request.requestId() == null ? request.toBuilder().requestId(newRequestId()).build() : request;
            return submit(BATCH_DECIDE_PATH, withId, withId.items().size());
        });
    }

    public Mono<BatchSubmitResponse> submitGenerate(@NonNull BatchGenerateRequest request) {
        return Mono.defer(() -> {
            var withId = request.requestId() == null ? request.toBuilder().requestId(newRequestId()).build() : request;
            return submit(BATCH_GENERATE_PATH, withId, withId.items().size());
        });
    }

    /** {@code results=false} returns status and counts only; a service that predates it ignores it. */
    public Mono<JobView> getJob(@NonNull String jobId, int cursor, boolean results) {
        Preconditions.checkArgument(cursor >= 0, "Argument 'cursor' must not be negative");
        return execute(Call.<JobView>builder()
                .method(HttpMethod.GET)
                .path(JOB_PATH)
                .target(base -> {
                    var target = base.path(JOB_PATH).resolveTemplate("job_id", jobId).queryParam("cursor", cursor);
                    return results ? target : target.queryParam("results", false);
                })
                .readTimeout(config.getInference().getReadTimeout().toJavaDuration())
                .type(JobView.class)
                .build());
    }

    public Mono<JobView> cancelJob(@NonNull String jobId) {
        return execute(Call.<JobView>builder()
                .method(HttpMethod.DELETE)
                .path(JOB_PATH)
                .target(base -> base.path(JOB_PATH).resolveTemplate("job_id", jobId))
                .readTimeout(config.getInference().getReadTimeout().toJavaDuration())
                .type(JobView.class)
                .build());
    }

    private Mono<BatchSubmitResponse> submit(String path, Object request, int items) {
        Preconditions.checkArgument(items >= 1 && items <= MAX_BATCH_ITEMS,
                "a batch must have 1 to '%s' items, items '%s'", MAX_BATCH_ITEMS, items);
        // Serialized once, so every retry sends the same bytes and the same request_id.
        var body = InferenceJson.writeBytes(request);
        if (body.length > config.getMaxJobBytes()) {
            return Mono.error(new IllegalArgumentException(
                    "batch body exceeds aiLabels.maxJobBytes, bytes '%d', max '%d'".formatted(body.length,
                            config.getMaxJobBytes())));
        }
        var readTimeout = config.getInference().getReadTimeout().toJavaDuration()
                .plusSeconds(body.length / SUBMIT_BYTES_PER_SECOND);
        return execute(Call.<BatchSubmitResponse>builder()
                .method(HttpMethod.POST)
                .path(path)
                .target(base -> base.path(path))
                .body(body)
                .readTimeout(readTimeout)
                .type(BatchSubmitResponse.class)
                .submit(true)
                .build());
    }

    private <T> Mono<T> execute(Call<T> call) {
        return Mono.defer(() -> attempt(call, 0));
    }

    private <T> Mono<T> attempt(Call<T> call, int attempt) {
        return send(call).onErrorResume(InferenceException.class, exception -> {
            if (attempt >= MAX_RETRIES || !exception.isRetryable() || (call.submit() && exception.isQuotaExceeded())) {
                return Mono.error(exception);
            }
            var backoff = exception.getRetryAfter() != null
                    ? exception.getRetryAfter()
                    : BASE_BACKOFF.multipliedBy(1L << attempt);
            log.warn("Retrying opik-inference call, method '{}', path '{}', code '{}', status '{}', attempt '{}'",
                    call.method(), call.path(), exception.getCode(), exception.getHttpStatus(), attempt + 1);
            return Mono.delay(backoff).then(Mono.defer(() -> attempt(call, attempt + 1)));
        });
    }

    private <T> Mono<T> send(Call<T> call) {
        var inference = config.getInference();
        Preconditions.checkState(StringUtils.isNotBlank(inference.getUrl()),
                "aiLabels.inference.url must be set to call opik-inference");
        Preconditions.checkState(StringUtils.isNotBlank(inference.getCredential()),
                "aiLabels.inference.credential must be set to call opik-inference");
        return Mono.<Response>create(sink -> {
            var builder = call.target().apply(client.target(inference.getUrl()))
                    .request(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + inference.getCredential())
                    .property(ClientProperties.CONNECT_TIMEOUT,
                            (int) inference.getConnectTimeout().toMilliseconds())
                    .property(ClientProperties.READ_TIMEOUT, (int) call.readTimeout().toMillis());
            var callback = new InvocationCallback<Response>() {
                @Override
                public void completed(Response response) {
                    sink.success(response);
                }

                @Override
                public void failed(Throwable throwable) {
                    sink.error(InferenceException.transport(throwable));
                }
            };
            if (call.body() != null) {
                builder.async().method(call.method(), Entity.entity(call.body(), MediaType.APPLICATION_JSON_TYPE),
                        callback);
            } else {
                builder.async().method(call.method(), callback);
            }
        })
                // A response that arrives after a cancel would otherwise hold its pooled connection.
                .doOnDiscard(Response.class, Response::close)
                .map(response -> read(call, response));
    }

    private <T> T read(Call<T> call, Response response) {
        try (response) {
            var status = response.getStatus();
            var body = response.hasEntity() ? response.readEntity(byte[].class) : new byte[0];
            if (response.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
                throw InferenceException.fromResponse(status, response.getHeaderString(HttpHeaders.RETRY_AFTER),
                        body);
            }
            try {
                return InferenceJson.MAPPER.readValue(body, call.type());
            } catch (IOException | RuntimeException exception) {
                throw InferenceException.upstreamError(status, null, exception);
            }
        }
    }

    private static String newRequestId() {
        return UUID.randomUUID().toString();
    }

    @Builder
    private record Call<T>(
            @NonNull String method,
            @NonNull String path,
            @NonNull Function<WebTarget, WebTarget> target,
            byte[] body,
            @NonNull Duration readTimeout,
            @NonNull Class<T> type,
            boolean submit) {
    }
}
