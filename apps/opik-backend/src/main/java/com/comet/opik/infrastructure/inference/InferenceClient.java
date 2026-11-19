package com.comet.opik.infrastructure.inference;

import com.codahale.metrics.MetricRegistry;
import com.comet.opik.infrastructure.AiLabelsConfig;
import com.comet.opik.infrastructure.http.DestinationGuardJerseyClientBuilder;
import com.comet.opik.infrastructure.http.HttpModule;
import com.comet.opik.infrastructure.net.DestinationGuard;
import com.comet.opik.infrastructure.net.DestinationGuardDnsResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Client for the opik-inference v1 API: the batch endpoints, {@code /v1/embed}, and the synchronous {@link #decide} that
 * guardrail checks use. Its retries mirror the Python client's: up to 2, backing off 0.1 s then 0.2 s or honoring
 * {@code Retry-After}, except that a submit or embed answered with {@code quota_exceeded} fails at once, so the caller
 * can back that one workspace off.
 * <p>
 * Every call takes a deadline: each attempt's connect and read timeouts are cut to the time left, no retry starts that
 * can't finish its backoff before it, and a call made past it fails with {@code out_of_time} without sending anything.
 * A body that trickles in escapes the socket timeouts, so the whole call is also cut off at the deadline with
 * {@code out_of_time}. A call that sends the service a deadline doesn't retry {@code deadline_exceeded}: a second attempt
 * would miss it too, and would add load to a service that is already behind.
 * <p>
 * A call cut off at its deadline, or cancelled by its caller, closes its response: the read stops, and the connection
 * is dropped instead of read to its end.
 * <p>
 * {@link #decide}, which a request waits on, runs on a client of its own, built like the shared one: on the shared
 * client it would queue behind batch calls for a pooled connection. Its connections and threads are sized to
 * {@code maxConcurrentDecides}, the most decide calls in flight; a call over it fails with {@code client_busy} after the
 * usual retries.
 * <p>
 * A redirect is never followed: it fails the call with its 3xx status, as {@code upstream_error}. A response body
 * longer than {@code aiLabels.inference.maxResponseBytes} fails the call the same way: at once when its
 * {@code Content-Length} says so, otherwise when the read reaches the cap. A success body is parsed as it is read, so
 * the raw bytes are never held next to the parsed value.
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
    /** The service rejects an embed {@code deadline_ms} above this: {@code limits.embed_max_deadline_ms}. */
    public static final long MAX_EMBED_DEADLINE_SECONDS = 60;
    static final Duration MAX_EMBED_DEADLINE = Duration.ofSeconds(MAX_EMBED_DEADLINE_SECONDS);

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
        return Mono.defer(() -> submit(BATCH_DECIDE_PATH, request, request.items().size(), deadline));
    }

    public Mono<BatchSubmitResponse> submitGenerate(@NonNull BatchGenerateRequest request,
            @NonNull Instant deadline) {
        return Mono.defer(() -> submit(BATCH_GENERATE_PATH, request, request.items().size(), deadline));
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

    /** One vector per input, in the inputs' order, from the realtime embedding pool. Retried like a submit. */
    public Mono<EmbedResponse> embed(@NonNull EmbedRequest request, @NonNull Instant deadline) {
        Preconditions.checkArgument(!request.inputs().isEmpty() && request.inputs().size() <= MAX_EMBED_INPUTS,
                "an embed call takes 1 to '%s' inputs, inputs '%s'", MAX_EMBED_INPUTS, request.inputs().size());
        return Mono.defer(() -> {
            var left = Duration.between(Instant.now(), deadline);
            if (left.toMillis() < 1) {
                return Mono.error(InferenceException.outOfTime());
            }
            var wire = withRequestId(request);
            wire.put("deadline_ms", Math.min(left.toMillis(), MAX_EMBED_DEADLINE.toMillis()));
            // Serialized once, so every retry sends the same bytes and the same request_id.
            var body = InferenceJson.writeBytes(wire);
            return execute(Call.<EmbedResponse>builder()
                    .method(HttpMethod.POST)
                    .path(EMBED_PATH)
                    .target(base -> base.path(EMBED_PATH))
                    .body(body)
                    .readTimeout(config.getInference().getReadTimeout().toJavaDuration())
                    .deadline(deadline)
                    .type(EmbedResponse.class)
                    .failFastOnQuotaExceeded(true)
                    .failFastOnDeadlineExceeded(true)
                    .build());
        });
    }

    /**
     * One synchronous decide call: every question answered about the same state. {@code request.deadlineMs()}, when
     * set, is the time the service may spend; {@code deadline} bounds the client, retries included, and should leave
     * room for one retry on top of the service's.
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
            return execute(Call.<DecideResponse>builder()
                    .method(HttpMethod.POST)
                    .path(DECIDE_PATH)
                    .target(base -> base.path(DECIDE_PATH))
                    .body(InferenceJson.writeBytes(withRequestId(request)))
                    .readTimeout(config.getInference().getReadTimeout().toJavaDuration())
                    .deadline(deadline)
                    .type(DecideResponse.class)
                    .failFastOnDeadlineExceeded(true)
                    .realtime(true)
                    .build());
        });
    }

    private Mono<BatchSubmitResponse> submit(String path, Object request, int items, Instant deadline) {
        Preconditions.checkArgument(items >= 1 && items <= MAX_BATCH_ITEMS,
                "a batch must have 1 to '%s' items, items '%s'", MAX_BATCH_ITEMS, items);
        // Serialized once, so every retry sends the same bytes and the same request_id.
        var body = InferenceJson.writeBytes(withRequestId(request));
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
        return Mono.defer(() -> {
            var left = Duration.between(Instant.now(), call.deadline());
            // A body that trickles in escapes the socket timeouts: this frees the caller at the deadline, and the
            // cancel closes the response.
            return attempt(call, 0)
                    .timeout(left.isNegative() ? Duration.ZERO : left)
                    .onErrorMap(TimeoutException.class, exception -> InferenceException.outOfTime());
        });
    }

    private <T> Mono<T> attempt(Call<T> call, int attempt) {
        return Mono.defer(() -> {
            var left = Duration.between(Instant.now(), call.deadline());
            // Under 1 ms is out of time: Jersey's timeouts are whole milliseconds, so the attempt would outlast it.
            return left.toMillis() >= 1 ? send(call, left) : Mono.<T>error(InferenceException.outOfTime());
        }).onErrorResume(InferenceException.class, exception -> {
            if (attempt >= MAX_RETRIES || !exception.isRetryable()
                    || (call.failFastOnQuotaExceeded() && exception.isQuotaExceeded())
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
        // A decide call keeps its slot until its thread is done: a cancel closes the response, which ends the read.
        Runnable release = call.realtime() ? decidePermits::release : () -> {
        };
        var httpClient = call.realtime() ? decideClient : client;
        return Mono.<T>create(sink -> {
            var cancelled = new AtomicBoolean();
            var arrived = new AtomicReference<Response>();
            // Closing the response cancels its connection, see CLOSE_WITHOUT_DRAINING, which ends a read blocked on it.
            sink.onCancel(() -> {
                cancelled.set(true);
                closeQuietly(arrived.get());
            });
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
            // Read on the client's thread. The response is always closed, and its pooled connection given back.
            var callback = new InvocationCallback<Response>() {
                @Override
                public void completed(Response response) {
                    arrived.set(response);
                    if (cancelled.get()) {
                        // Cancelled before the response arrived: the read below then fails at once.
                        closeQuietly(response);
                    }
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
            checkContentLength(response, status, maxBytes);
            if (response.getStatusInfo().getFamily() == Response.Status.Family.REDIRECTION) {
                // Redirects are not followed, and the same request would only be redirected again.
                throw new InferenceException(InferenceErrorCode.UPSTREAM_ERROR.getValue(), false, status, null, null,
                        null);
            }
            if (response.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
                // The error envelope is parsed from the bytes, and a body that isn't one is kept as the message.
                var body = readBody(response, status, maxBytes);
                throw InferenceException.fromResponse(status, response.getHeaderString(HttpHeaders.RETRY_AFTER),
                        body);
            }
            return parse(call, response, status, maxBytes);
        }
    }

    /**
     * Fails before the body is read when its {@code Content-Length} is over the cap or invalid. Parsed as a
     * {@code long}: {@link Response#getLength()} is an {@code int}, and reads a larger value as no length at all.
     */
    private static void checkContentLength(Response response, int status, long maxBytes) {
        var header = response.getHeaderString(HttpHeaders.CONTENT_LENGTH);
        if (header == null) {
            return;
        }
        long length;
        try {
            length = Long.parseLong(header.trim());
        } catch (NumberFormatException exception) {
            throw new InferenceException(InferenceErrorCode.UPSTREAM_ERROR.getValue(), false, status, null, null,
                    new IllegalStateException("invalid Content-Length", exception));
        }
        if (length < 0) {
            throw new InferenceException(InferenceErrorCode.UPSTREAM_ERROR.getValue(), false, status, null, null,
                    new IllegalStateException("invalid Content-Length"));
        }
        if (length > maxBytes) {
            throw overTheCap(status, maxBytes);
        }
    }

    /** Parses the body as it is read, then reads the rest, so the connection is kept for the next call. */
    private <T> T parse(Call<T> call, Response response, int status, long maxBytes) {
        T value;
        try {
            // hasEntity reads ahead, so it can fail like the read itself.
            if (!response.hasEntity()) {
                throw InferenceException.upstreamError(status, null, null);
            }
            var body = new CappedStream(response.readEntity(InputStream.class), maxBytes);
            value = InferenceJson.MAPPER.readValue(body, call.type());
            body.transferTo(OutputStream.nullOutputStream());
        } catch (OverTheCap exception) {
            throw overTheCap(status, maxBytes);
        } catch (JsonProcessingException exception) {
            throw InferenceException.upstreamError(status, null, exception);
        } catch (IOException | ProcessingException exception) {
            // A connection reset or truncated body after the headers: as retryable as one before them.
            throw InferenceException.transport(exception);
        } catch (InferenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw InferenceException.upstreamError(status, null, exception);
        }
        if (value == null) {
            throw InferenceException.upstreamError(status, null, null);
        }
        return value;
    }

    private static byte[] readBody(Response response, int status, long maxBytes) {
        try {
            // hasEntity reads ahead, so it can fail like the read itself.
            if (!response.hasEntity()) {
                return new byte[0];
            }
            return new CappedStream(response.readEntity(InputStream.class), maxBytes).readAllBytes();
        } catch (OverTheCap exception) {
            throw overTheCap(status, maxBytes);
        } catch (IOException | ProcessingException exception) {
            // A connection reset or truncated body after the headers: as retryable as one before them.
            throw InferenceException.transport(exception);
        }
    }

    /** Not retryable: the same request would only get the same body again. */
    private static InferenceException overTheCap(int status, long maxBytes) {
        return new InferenceException(InferenceErrorCode.UPSTREAM_ERROR.getValue(), false, status, null, null,
                new IllegalStateException("response body exceeds aiLabels.inference.maxResponseBytes '%d'"
                        .formatted(maxBytes)));
    }

    private static void closeQuietly(Response response) {
        if (response == null) {
            return;
        }
        try {
            response.close();
        } catch (RuntimeException ignored) {
            // the read that is cut short reports the failure, and nobody waits for a cancelled one
        }
    }

    /** The timeout cut to the time left, and at least 1 ms: Jersey reads 0 as no timeout at all. */
    private static int timeoutMillis(Duration timeout, Duration left) {
        return (int) Math.clamp((timeout.compareTo(left) <= 0 ? timeout : left).toMillis(), 1, Integer.MAX_VALUE);
    }

    /** The body a call sends: the caller's request, plus a new {@code request_id} for this call. */
    private static ObjectNode withRequestId(Object request) {
        ObjectNode wire = InferenceJson.MAPPER.valueToTree(request);
        wire.put("request_id", UUID.randomUUID().toString());
        return wire;
    }

    /** Thrown on a read past the cap: telling it apart from a malformed body is the point of the cap. */
    private static final class OverTheCap extends IOException {
    }

    /**
     * Reads at most {@code maxBytes}, and fails a read that finds a byte past them with {@link OverTheCap}. Closing it
     * leaves the stream open: the response owns it, and closes it with its connection.
     */
    private static final class CappedStream extends FilterInputStream {

        private long left;

        CappedStream(InputStream in, long maxBytes) {
            super(in);
            this.left = maxBytes;
        }

        @Override
        public int read() throws IOException {
            if (left == 0) {
                return atTheCap();
            }
            var read = super.read();
            if (read >= 0) {
                left--;
            }
            return read;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (left == 0) {
                return atTheCap();
            }
            var read = super.read(buffer, offset, (int) Math.min(length, left));
            if (read > 0) {
                left -= read;
            }
            return read;
        }

        @Override
        public long skip(long count) throws IOException {
            var skipped = super.skip(Math.min(count, left));
            left -= skipped;
            return skipped;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(super.available(), left);
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        @Override
        public void close() {
            // the response closes the stream
        }

        private int atTheCap() throws IOException {
            if (in.read() >= 0) {
                throw new OverTheCap();
            }
            return -1;
        }
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
            boolean failFastOnDeadlineExceeded,
            boolean realtime) {
    }
}
