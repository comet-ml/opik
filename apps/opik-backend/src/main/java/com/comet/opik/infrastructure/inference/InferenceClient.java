package com.comet.opik.infrastructure.inference;

import com.codahale.metrics.MetricRegistry;
import com.comet.opik.infrastructure.AiLabelsConfig;
import com.comet.opik.infrastructure.http.DestinationGuardJerseyClientBuilder;
import com.comet.opik.infrastructure.http.HttpModule;
import com.comet.opik.infrastructure.inference.ItemResponse.GenerateResponse;
import com.comet.opik.infrastructure.net.DestinationGuard;
import com.comet.opik.infrastructure.net.DestinationGuardDnsResolver;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.dropwizard.client.JerseyClientConfiguration;
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
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.glassfish.jersey.client.ClientProperties;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Client for the opik-inference v1 API: the batch endpoints, {@code /v1/embed}, {@code /v1/generate}, and the
 * synchronous {@link #decide} that guardrail checks use. Its retries mirror the Python client's: up to 2, backing off
 * 0.1 s then 0.2 s or honoring {@code Retry-After}, except that a submit, embed or generate answered with
 * {@code quota_exceeded} fails at once, so the caller can back that one workspace off.
 * <p>
 * Every call takes a deadline: each attempt's connect and read timeouts are cut to the time left, no retry starts that
 * can't finish its backoff before it, and a call made past it fails with {@code out_of_time} without sending anything.
 * <p>
 * {@link #decide}, which a request waits on, runs on a client of its own, built like the shared one: on the shared
 * client it would queue behind batch calls for a pooled connection. Its connections and threads are sized to
 * {@code maxConcurrentDecides}, the most decide calls in flight; a call over it fails with {@code client_busy} after the
 * usual retries. A call cut off at its deadline frees its caller, but its thread reads on until the response ends and
 * keeps its slot until then, so a slow service ties up decide's slots, never the shared client.
 * <p>
 * A redirect is never followed: it fails the call with its 3xx status, as {@code upstream_error}. A response body,
 * success or error, is read up to {@code aiLabels.inference.maxResponseBytes}; a longer one fails the call the same
 * way, and its connection is closed without reading the rest.
 * <p>
 * Constructed whether or not ai-labels is enabled: the url and credential are only required when a call is made.
 */
@Slf4j
@Singleton
public class InferenceClient {

    static final int MAX_RETRIES = 2;
    static final Duration BASE_BACKOFF = Duration.ofMillis(100);
    public static final int MAX_BATCH_ITEMS = 10_000;
    public static final int MAX_STATUS_JOB_IDS = 1_000;
    public static final int MAX_EMBED_INPUTS = 256;
    /** The service rejects an embed {@code deadline_ms} above this. */
    static final Duration MAX_EMBED_DEADLINE = Duration.ofSeconds(60);

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
    private static final String EMBED_PATH = "v1/embed";
    private static final String GENERATE_PATH = "v1/generate";

    /**
     * A relaxed guard checks no address or scheme: opik-inference is an internal service. It is set on every request
     * only so that closing the response ends its connection instead of reading the rest of the body, see
     * {@code DestinationGuardJerseyClientBuilder}; a body read to its end keeps its connection for reuse.
     */
    private static final DestinationGuard CLOSE_WITHOUT_DRAINING = DestinationGuard.builder()
            .mode(DestinationGuard.Mode.RELAXED)
            .build();

    private final @NonNull Client client;
    private final @NonNull AiLabelsConfig config;
    private final @NonNull Client decideClient;
    private final @NonNull Semaphore decidePermits;

    @Inject
    public InferenceClient(@NonNull Client client, @NonNull @Config("aiLabels") AiLabelsConfig config) {
        this.client = client;
        this.config = config;
        this.decideClient = newDecideClient(config.getInference());
        this.decidePermits = new Semaphore(config.getInference().getMaxConcurrentDecides());
    }

    private static Client newDecideClient(AiLabelsConfig.InferenceConfig inference) {
        var maxConcurrentDecides = inference.getMaxConcurrentDecides();
        var clientConfig = new JerseyClientConfiguration();
        clientConfig.setMaxConnections(maxConcurrentDecides);
        clientConfig.setMaxConnectionsPerRoute(maxConcurrentDecides);
        // The rest as the shared client sends to opik-inference.
        clientConfig.setGzipEnabledForRequests(false);
        clientConfig.setChunkedEncodingEnabled(false);
        clientConfig.setValidateAfterInactivityPeriod(io.dropwizard.util.Duration.seconds(5));
        clientConfig.setTimeToLive(inference.getDecideTimeToLive());
        // Dropwizard's default of 0 closes every connection after one call: each check would pay a new TCP and TLS
        // handshake.
        clientConfig.setKeepAlive(inference.getDecideKeepAlive());
        clientConfig.setUserAgent(Optional.of("opik-inference-decide"));
        // A thread per call in flight, started when needed: the permits keep calls within the cap, so none queues.
        var executor = new ThreadPoolExecutor(0, maxConcurrentDecides, 60, TimeUnit.SECONDS, new SynchronousQueue<>(),
                new ThreadFactoryBuilder().setNameFormat("opik-inference-decide-%d").setDaemon(true).build());
        return HttpModule.buildClient(
                new DestinationGuardJerseyClientBuilder(new MetricRegistry()).using(executor), clientConfig);
    }

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
     * One vector per input, in the inputs' order, from the realtime embedding pool. Retried like a submit, and like a
     * submit a {@code quota_exceeded} answer fails at once, so the caller can stop calling for a while. The request
     * gets a new {@code request_id}, and {@code deadline_ms} is the time left before {@code deadline}, at most the
     * service's maximum.
     */
    public Mono<EmbedResponse> embed(@NonNull EmbedRequest request, @NonNull Instant deadline) {
        Preconditions.checkArgument(!request.inputs().isEmpty() && request.inputs().size() <= MAX_EMBED_INPUTS,
                "an embed call takes 1 to '%s' inputs, inputs '%s'", MAX_EMBED_INPUTS, request.inputs().size());
        return Mono.defer(() -> {
            var left = Duration.between(Instant.now(), deadline);
            if (left.toMillis() < 1) {
                return Mono.error(InferenceException.outOfTime());
            }
            var withIds = request.toBuilder()
                    .requestId(newRequestId())
                    .deadlineMs(Math.min(left.toMillis(), MAX_EMBED_DEADLINE.toMillis()))
                    .build();
            // Serialized once, so every retry sends the same bytes and the same request_id.
            var body = InferenceJson.writeBytes(withIds);
            return execute(Call.<EmbedResponse>builder()
                    .method(HttpMethod.POST)
                    .path(EMBED_PATH)
                    .target(base -> base.path(EMBED_PATH))
                    .body(body)
                    .readTimeout(config.getInference().getReadTimeout().toJavaDuration())
                    .deadline(deadline)
                    .type(EmbedResponse.class)
                    .failFastOnQuotaExceeded(true)
                    .build());
        });
    }

    /**
     * One completion, answered in the call. Retried like a submit: a retry sends the same bytes, so the same
     * {@code request_id}, and a {@code quota_exceeded} answer fails at once. So does {@code schema_violation}: the
     * service checks the answer against the schema, and the same bytes at temperature 0 give the same answer. The
     * request's {@code deadline_ms} is the caller's, if set, cut to the time left before {@code deadline} and to the
     * lane's maximum; the read timeout is that deadline, as the service holds the connection until the model answers.
     */
    public Mono<GenerateResponse> generate(@NonNull GenerateRequest request, @NonNull Instant deadline) {
        Preconditions.checkArgument(!request.messages().isEmpty(), "a generate call takes at least one message");
        Preconditions.checkArgument(
                request.maxTokens() >= BatchGenerateItem.MIN_MAX_TOKENS
                        && request.maxTokens() <= BatchGenerateItem.MAX_MAX_TOKENS,
                "max_tokens must be between '%s' and '%s', maxTokens '%s'", BatchGenerateItem.MIN_MAX_TOKENS,
                BatchGenerateItem.MAX_MAX_TOKENS, request.maxTokens());
        Preconditions.checkArgument(request.deadlineMs() == null || request.deadlineMs() > 0,
                "a generate deadline must be positive, deadlineMs '%s'", request.deadlineMs());
        return Mono.defer(() -> {
            var left = Duration.between(Instant.now(), deadline);
            if (left.toMillis() < 1) {
                return Mono.error(InferenceException.outOfTime());
            }
            var deadlineMs = Math.min(request.deadlineMs() == null ? Long.MAX_VALUE : request.deadlineMs(),
                    Math.min(left.toMillis(), request.lane().getMaxDeadline().toMillis()));
            var withIds = request.toBuilder()
                    .requestId(request.requestId() == null ? newRequestId() : request.requestId())
                    .deadlineMs(deadlineMs)
                    .build();
            // Serialized once, so every retry sends the same bytes and the same request_id.
            var body = InferenceJson.writeBytes(withIds);
            return execute(Call.<GenerateResponse>builder()
                    .method(HttpMethod.POST)
                    .path(GENERATE_PATH)
                    .target(base -> base.path(GENERATE_PATH))
                    .body(body)
                    .readTimeout(config.getInference().getReadTimeout().toJavaDuration()
                            .plusMillis(deadlineMs))
                    .deadline(deadline)
                    .type(GenerateResponse.class)
                    .failFastOnQuotaExceeded(true)
                    .failFastOnSchemaViolation(true)
                    .build());
        });
    }

    /**
     * One synchronous decide call: every question answered about the same state. {@code request.deadlineMs()}, when
     * set, is the time the service may spend; {@code deadline} bounds the client, retries included, and should leave
     * room for one retry on top of the service's. A {@code deadline_exceeded} is not retried: a second attempt would
     * miss it too, and would double the load on a service that is already behind.
     */
    public Mono<DecideResponse> decide(@NonNull DecideRequest request, @NonNull Instant deadline) {
        Preconditions.checkArgument(!request.questions().isEmpty()
                && request.questions().size() <= DecideRequest.MAX_QUESTIONS,
                "a decide call takes 1 to '%s' questions, questions '%s'", DecideRequest.MAX_QUESTIONS,
                request.questions().size());
        request.questions().values().forEach(Question::validate);
        Preconditions.checkArgument(request.deadlineMs() == null || request.deadlineMs() > 0,
                "a decide deadline must be positive when set, deadlineMs '%s'", request.deadlineMs());
        return Mono.defer(() -> {
            var withId = request.requestId() == null ? request.toBuilder().requestId(newRequestId()).build() : request;
            var left = Duration.between(Instant.now(), deadline);
            return execute(Call.<DecideResponse>builder()
                    .method(HttpMethod.POST)
                    .path(DECIDE_PATH)
                    .target(base -> base.path(DECIDE_PATH))
                    .body(InferenceJson.writeBytes(withId))
                    .readTimeout(config.getInference().getReadTimeout().toJavaDuration())
                    .deadline(deadline)
                    .type(DecideResponse.class)
                    .failFastOnDeadlineExceeded(true)
                    .realtime(true)
                    .build())
                    // A body that trickles in escapes the socket timeouts: this frees the caller at the deadline.
                    .timeout(left.isNegative() ? Duration.ZERO : left)
                    .onErrorMap(TimeoutException.class, exception -> InferenceException.outOfTime());
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
                    || (call.failFastOnQuotaExceeded() && exception.isQuotaExceeded())
                    || (call.failFastOnSchemaViolation() && exception.isSchemaViolation())
                    || (call.failFastOnDeadlineExceeded() && exception.isDeadlineExceeded())) {
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
        if (call.realtime() && !decidePermits.tryAcquire()) {
            return Mono.error(InferenceException.clientBusy());
        }
        // A decide call keeps its slot until its thread is done, which a cancel at the deadline does not cut short.
        Runnable release = call.realtime() ? decidePermits::release : () -> {
        };
        var httpClient = call.realtime() ? decideClient : client;
        return Mono.<T>create(sink -> {
            var builder = call.target().apply(httpClient.target(inference.getUrl()))
                    .request(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + inference.getCredential())
                    // opik-inference never redirects, and following one would send the credential where it points.
                    .property(ClientProperties.FOLLOW_REDIRECTS, false)
                    // A body over the cap could otherwise keep the read going as long as the service sends it.
                    .property(DestinationGuardDnsResolver.PROPERTY, CLOSE_WITHOUT_DRAINING)
                    .property(ClientProperties.CONNECT_TIMEOUT,
                            timeoutMillis(inference.getConnectTimeout().toJavaDuration(), left))
                    .property(ClientProperties.READ_TIMEOUT, timeoutMillis(call.readTimeout(), left));
            // Read on the client's thread, even after a cancel: the response is always closed, and its pooled
            // connection given back.
            var callback = new InvocationCallback<Response>() {
                @Override
                public void completed(Response response) {
                    T value;
                    try {
                        value = read(call, response);
                    } catch (RuntimeException exception) {
                        sink.error(exception);
                        return;
                    } finally {
                        release.run();
                    }
                    sink.success(value);
                }

                @Override
                public void failed(Throwable throwable) {
                    release.run();
                    sink.error(InferenceException.transport(throwable));
                }
            };
            try {
                if (call.body() != null) {
                    builder.async().method(call.method(),
                            Entity.entity(call.body(), MediaType.APPLICATION_JSON_TYPE), callback);
                } else {
                    builder.async().method(call.method(), callback);
                }
            } catch (RuntimeException exception) {
                release.run();
                throw exception;
            }
        });
    }

    private <T> T read(Call<T> call, Response response) {
        try (response) {
            var status = response.getStatus();
            var maxBytes = config.getInference().getMaxResponseBytes();
            var body = readBody(response, maxBytes);
            if (body.length > maxBytes) {
                // Not retryable: the same request would only get the same body again.
                throw new InferenceException(InferenceErrorCode.UPSTREAM_ERROR.getValue(), false, status, null, null,
                        new IllegalStateException("response body exceeds aiLabels.inference.maxResponseBytes '%d'"
                                .formatted(maxBytes)));
            }
            if (response.getStatusInfo().getFamily() == Response.Status.Family.REDIRECTION) {
                // Redirects are not followed, and the same request would only be redirected again.
                throw new InferenceException(InferenceErrorCode.UPSTREAM_ERROR.getValue(), false, status, null, null,
                        null);
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

    /**
     * The body, read up to one byte past {@code maxBytes}, so a longer one is seen without holding more of it, as
     * {@code WebhookHttpClient} reads a response. Closing the stream of a longer one ends its connection, see
     * {@link #CLOSE_WITHOUT_DRAINING}, so the rest is never read.
     */
    private static byte[] readBody(Response response, long maxBytes) {
        try {
            // hasEntity reads ahead, so it can fail like the read itself.
            if (!response.hasEntity()) {
                return new byte[0];
            }
            try (var body = response.readEntity(InputStream.class)) {
                return body.readNBytes(Math.toIntExact(maxBytes + 1));
            }
        } catch (IOException | ProcessingException exception) {
            // A connection reset or truncated body after the headers: as retryable as one before them.
            throw InferenceException.transport(exception);
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
            boolean failFastOnQuotaExceeded,
            boolean failFastOnSchemaViolation,
            boolean failFastOnDeadlineExceeded,
            boolean realtime) {
    }
}
