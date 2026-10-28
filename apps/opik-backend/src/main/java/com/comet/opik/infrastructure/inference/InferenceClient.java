package com.comet.opik.infrastructure.inference;

import com.comet.opik.infrastructure.AiLabelsConfig;
import com.google.common.base.Preconditions;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.ProcessingException;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Client for the opik-inference v1 API: the batch endpoints, and the synchronous {@link #decide} that guardrail checks
 * use. Its retries mirror the Python client's: up to 2, backing off 0.1 s then
 * 0.2 s or honoring {@code Retry-After}, except that a submit answered with {@code quota_exceeded} fails at once, so
 * the caller can back that one workspace off.
 * <p>
 * Every call takes a deadline: no attempt outlasts it (its connect and read timeouts are cut to the time left), no
 * retry starts that can't finish its backoff before it, and a call made past it fails with {@code out_of_time} without
 * sending anything.
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
    public static final int MAX_STATUS_JOB_IDS = 1_000;

    /**
     * The service reads and enqueues the whole body before it answers a submit, so the read timeout grows by a second
     * per MiB sent.
     */
    private static final long SUBMIT_BYTES_PER_SECOND = 1024L * 1024;

    private static final String DECIDE_PATH = "v1/decide";
    private static final String BATCH_DECIDE_PATH = "v1/batch/decide";
    private static final String BATCH_GENERATE_PATH = "v1/batch/generate";
    private static final String JOB_PATH_TEMPLATE = "v1/batch/jobs/{job_id}";
    private static final String JOBS_STATUS_PATH = "v1/batch/jobs/status";

    private final @NonNull Client client;
    private final @NonNull @Config("aiLabels") AiLabelsConfig config;

    public Mono<BatchSubmitResponse> submitDecide(@NonNull BatchDecideRequest request, @NonNull Instant deadline) {
        return Mono.defer(() -> {
            var withId = request.requestId() == null ? request.toBuilder().requestId(newRequestId()).build() : request;
            return submit(BATCH_DECIDE_PATH, withId, withId.items().size(), deadline);
        });
    }

    public Mono<BatchSubmitResponse> submitGenerate(@NonNull BatchGenerateRequest request,
            @NonNull Instant deadline) {
        return Mono.defer(() -> {
            var withId = request.requestId() == null ? request.toBuilder().requestId(newRequestId()).build() : request;
            return submit(BATCH_GENERATE_PATH, withId, withId.items().size(), deadline);
        });
    }

    /** {@code results=false} returns status and counts only; a service that predates it ignores it. */
    public Mono<JobView> getJob(@NonNull String jobId, int cursor, boolean results, @NonNull Instant deadline) {
        Preconditions.checkArgument(cursor >= 0, "Argument 'cursor' must not be negative");
        return execute(Call.<JobView>builder()
                .method(HttpMethod.GET)
                .path(JOB_PATH_TEMPLATE)
                .target(base -> {
                    var target = base.path(JOB_PATH_TEMPLATE).resolveTemplate("job_id", jobId).queryParam("cursor",
                            cursor);
                    return results ? target : target.queryParam("results", false);
                })
                .readTimeout(config.getInference().getReadTimeout().toJavaDuration())
                .deadline(deadline)
                .type(JobView.class)
                .build());
    }

    /**
     * The status and counts of up to {@value #MAX_STATUS_JOB_IDS} jobs in one call, as {@link #getJob} returns them
     * with {@code results=false}. A job the service doesn't know (unknown, expired, or another deployment's) is left
     * out. A read with no side effects, so it is retried like a GET.
     */
    public Mono<List<JobView>> getJobs(@NonNull List<String> jobIds, @NonNull Instant deadline) {
        Preconditions.checkArgument(!jobIds.isEmpty() && jobIds.size() <= MAX_STATUS_JOB_IDS,
                "a status call takes 1 to '%s' job ids, jobIds '%s'", MAX_STATUS_JOB_IDS, jobIds.size());
        var body = InferenceJson.writeBytes(BatchJobStatusRequest.builder().jobIds(jobIds).build());
        return execute(Call.<BatchJobStatusResponse>builder()
                .method(HttpMethod.POST)
                .path(JOBS_STATUS_PATH)
                .target(base -> base.path(JOBS_STATUS_PATH))
                .body(body)
                .readTimeout(config.getInference().getReadTimeout().toJavaDuration())
                .deadline(deadline)
                .type(BatchJobStatusResponse.class)
                .build())
                .map(BatchJobStatusResponse::jobs);
    }

    /**
     * One synchronous decide call: every question answered about the same state. {@code request.deadlineMs()} is the
     * time the service may spend; {@code deadline} bounds the client, retries included, and should leave room for one
     * retry on top of the service's.
     */
    public Mono<DecideResponse> decide(@NonNull DecideRequest request, @NonNull Instant deadline) {
        Preconditions.checkArgument(!request.questions().isEmpty()
                && request.questions().size() <= DecideRequest.MAX_QUESTIONS,
                "a decide call takes 1 to '%s' questions, questions '%s'", DecideRequest.MAX_QUESTIONS,
                request.questions().size());
        request.questions().values().forEach(Question::validate);
        return Mono.defer(() -> {
            var withId = request.requestId() == null ? request.toBuilder().requestId(newRequestId()).build() : request;
            return execute(Call.<DecideResponse>builder()
                    .method(HttpMethod.POST)
                    .path(DECIDE_PATH)
                    .target(base -> base.path(DECIDE_PATH))
                    .body(InferenceJson.writeBytes(withId))
                    .readTimeout(config.getInference().getReadTimeout().toJavaDuration())
                    .deadline(deadline)
                    .type(DecideResponse.class)
                    .build());
        });
    }

    private Mono<BatchSubmitResponse> submit(String path, Object request, int items, Instant deadline) {
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
                .deadline(deadline)
                .type(BatchSubmitResponse.class)
                .failFastOnQuotaExceeded(true)
                .build());
    }

    private <T> Mono<T> execute(Call<T> call) {
        return Mono.defer(() -> attempt(call, 0));
    }

    private <T> Mono<T> attempt(Call<T> call, int attempt) {
        return Mono.defer(() -> {
            var left = Duration.between(Instant.now(), call.deadline());
            // Under 1 ms is out of time: Jersey's timeouts are whole milliseconds, so the attempt would outlast it.
            return left.toMillis() >= 1 ? send(call, left) : Mono.<T>error(InferenceException.outOfTime());
        }).onErrorResume(InferenceException.class, exception -> {
            if (attempt >= MAX_RETRIES || !exception.isRetryable()
                    || (call.failFastOnQuotaExceeded() && exception.isQuotaExceeded())) {
                return Mono.error(exception);
            }
            var backoff = exception.getRetryAfter() != null
                    ? exception.getRetryAfter()
                    : BASE_BACKOFF.multipliedBy(1L << attempt);
            if (!Instant.now().plus(backoff).isBefore(call.deadline())) {
                return Mono.error(exception);
            }
            // DEBUG: a retry that recovers is routine, and the caller logs a call that fails for good.
            log.debug("Retrying opik-inference call, method '{}', path '{}', code '{}', status '{}', attempt '{}'",
                    call.method(), call.path(), exception.getCode(), exception.getHttpStatus(), attempt + 1);
            return Mono.delay(backoff).then(Mono.defer(() -> attempt(call, attempt + 1)));
        });
    }

    private <T> Mono<T> send(Call<T> call, Duration left) {
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
                            timeoutMillis(inference.getConnectTimeout().toJavaDuration(), left))
                    .property(ClientProperties.READ_TIMEOUT, timeoutMillis(call.readTimeout(), left));
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
            byte[] body;
            try {
                body = response.hasEntity() ? response.readEntity(byte[].class) : new byte[0];
            } catch (ProcessingException exception) {
                // A connection reset or truncated body after the headers: as retryable as one before them.
                throw InferenceException.transport(exception);
            }
            if (response.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
                throw InferenceException.fromResponse(status, response.getHeaderString(HttpHeaders.RETRY_AFTER),
                        body);
            }
            T value;
            try {
                value = InferenceJson.MAPPER.readValue(body, call.type());
            } catch (IOException | RuntimeException exception) {
                throw InferenceException.upstreamError(status, null, exception);
            }
            if (value == null) {
                throw InferenceException.upstreamError(status, null, null);
            }
            return value;
        }
    }

    /** The timeout cut to the time left, and at least 1 ms: Jersey reads 0 as no timeout at all. */
    private static int timeoutMillis(Duration timeout, Duration left) {
        return (int) Math.clamp((timeout.compareTo(left) <= 0 ? timeout : left).toMillis(), 1, Integer.MAX_VALUE);
    }

    private static String newRequestId() {
        return UUID.randomUUID().toString();
    }

    @Builder(toBuilder = true)
    private record Call<T>(
            @NonNull String method,
            @NonNull String path,
            @NonNull Function<WebTarget, WebTarget> target,
            byte[] body,
            @NonNull Duration readTimeout,
            @NonNull Instant deadline,
            @NonNull Class<T> type,
            boolean failFastOnQuotaExceeded) {
    }
}
