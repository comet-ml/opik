package com.comet.opik.infrastructure.inference;

import com.comet.opik.infrastructure.AiLabelsConfig;
import com.comet.opik.infrastructure.InferenceConfig;
import com.comet.opik.infrastructure.net.DestinationGuard;
import com.comet.opik.infrastructure.net.DestinationGuardDnsResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.input.BoundedInputStream;
import org.apache.commons.lang3.StringUtils;
import org.glassfish.jersey.client.ClientProperties;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Client for the opik-inference v1 API: the batch endpoints, {@code /v1/embed}, and the synchronous {@link #decide} that
 * guardrail checks use. Its retries mirror the Python client's: up to {@code inference.retry.maxRetries}, backing off
 * {@code baseBackoff} and doubling it, or honoring {@code Retry-After}, except that a submit or embed answered with
 * {@code quota_exceeded} fails at once, so the caller can back that one workspace off. What one call may carry is
 * {@code inference.limits}.
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
 * {@link #decide}, which a request waits on, goes through the shared client like every other call, but as a synchronous
 * call on threads of its own, so it never queues behind batch calls for one of the client's threads. There are
 * {@code maxConcurrentDecides} of them, the most decide calls in flight; a call over it fails with {@code client_busy}
 * after the usual retries. That cap is kept below the shared pool's connections per route, so decide calls alone can't
 * take every connection to opik-inference.
 * <p>
 * A redirect is never followed: it fails the call with its 3xx status, as {@code upstream_error}. A response body
 * longer than {@code inference.maxResponseBytes} fails the call the same way: at once when its
 * {@code Content-Length} says so, otherwise when the read reaches the cap. A success body is parsed as it is read, so
 * the raw bytes are never held next to the parsed value.
 * <p>
 * Constructed whether or not ai-labels is enabled: the url and credential are only required when a call is made.
 */
@Slf4j
@Singleton
public class InferenceClient {

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
    private final @NonNull InferenceConfig config;
    private final @NonNull AiLabelsConfig aiLabels;
    private final @NonNull Semaphore decidePermits;
    private final @NonNull Scheduler decideScheduler;

    @Inject
    public InferenceClient(@NonNull Client client, @NonNull @Config("inference") InferenceConfig config,
            @NonNull @Config("aiLabels") AiLabelsConfig aiLabels) {
        this.client = client;
        this.config = config;
        this.aiLabels = aiLabels;
        this.decidePermits = new Semaphore(config.getMaxConcurrentDecides());
        // The permits keep calls within the threads, so none waits in the queue.
        this.decideScheduler = Schedulers.newBoundedElastic(config.getMaxConcurrentDecides(),
                config.getMaxConcurrentDecides(), "opik-inference-decide");
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
                .readTimeout(config.getReadTimeout().toJavaDuration())
                .deadline(deadline)
                .type(JobView.class)
                .build());
    }

    /**
     * The status and counts of up to {@code inference.limits.maxStatusJobIds} jobs in one call, as {@link #getJob}
     * returns them with {@code results=false}. A job the service doesn't know (unknown, expired, or another
     * deployment's) is left out. A read with no side effects, so it is retried like a GET.
     */
    public Mono<List<JobView>> getJobs(@NonNull List<String> jobIds, @NonNull Instant deadline) {
        var max = config.getLimits().getMaxStatusJobIds();
        Preconditions.checkArgument(!jobIds.isEmpty() && jobIds.size() <= max,
                "a status call takes 1 to '%s' job ids, jobIds '%s'", max, jobIds.size());
        var body = InferenceJson.writeBytes(BatchJobStatusRequest.builder().jobIds(jobIds).build());
        return execute(Call.<BatchJobStatusResponse>builder()
                .method(HttpMethod.POST)
                .path(JOBS_STATUS_PATH)
                .target(base -> base.path(JOBS_STATUS_PATH))
                .body(body)
                .readTimeout(config.getReadTimeout().toJavaDuration())
                .deadline(deadline)
                .type(BatchJobStatusResponse.class)
                .build())
                .map(BatchJobStatusResponse::jobs);
    }

    /** One vector per input, in the inputs' order, from the realtime embedding pool. Retried like a submit. */
    public Mono<EmbedResponse> embed(@NonNull EmbedRequest request, @NonNull Instant deadline) {
        var max = config.getLimits().getMaxEmbedInputs();
        Preconditions.checkArgument(!request.inputs().isEmpty() && request.inputs().size() <= max,
                "an embed call takes 1 to '%s' inputs, inputs '%s'", max, request.inputs().size());
        return Mono.defer(() -> {
            var left = Duration.between(Instant.now(), deadline);
            if (left.toMillis() < 1) {
                return Mono.error(InferenceException.outOfTime());
            }
            var wire = withRequestId(request);
            wire.put("deadline_ms",
                    Math.min(left.toMillis(), config.getLimits().getMaxEmbedDeadline().toMilliseconds()));
            // Serialized once, so every retry sends the same bytes and the same request_id.
            var body = InferenceJson.writeBytes(wire);
            return execute(Call.<EmbedResponse>builder()
                    .method(HttpMethod.POST)
                    .path(EMBED_PATH)
                    .target(base -> base.path(EMBED_PATH))
                    .body(body)
                    .readTimeout(config.getReadTimeout().toJavaDuration())
                    .deadline(deadline)
                    .type(EmbedResponse.class)
                    .noNullPrimitives(true)
                    .check(response -> checkVectors(response, request.inputs().size()))
                    .failFastOnQuotaExceeded(true)
                    .failFastOnDeadlineExceeded(true)
                    .build());
        });
    }

    /** One vector per input, each {@code dimensions} long; a null inside a vector already failed the parse. */
    private static void checkVectors(EmbedResponse response, int inputs) {
        var result = response.result();
        Preconditions.checkState(result != null && result.embeddings() != null, "embed answer has no vectors");
        Preconditions.checkState(result.dimensions() >= 1, "embed answer has dimensions '%s'", result.dimensions());
        Preconditions.checkState(result.embeddings().length == inputs, "embed answer has '%s' vectors for '%s' inputs",
                result.embeddings().length, inputs);
        for (var vector : result.embeddings()) {
            Preconditions.checkState(vector != null && vector.length == result.dimensions(),
                    "embed vector has '%s' values, dimensions '%s'", vector == null ? null : vector.length,
                    result.dimensions());
        }
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
                    .readTimeout(config.getReadTimeout().toJavaDuration())
                    .deadline(deadline)
                    .type(DecideResponse.class)
                    .failFastOnDeadlineExceeded(true)
                    .realtime(true)
                    .build());
        });
    }

    private Mono<BatchSubmitResponse> submit(String path, Object request, int items, Instant deadline) {
        var max = config.getLimits().getMaxBatchItems();
        Preconditions.checkArgument(items >= 1 && items <= max, "a batch must have 1 to '%s' items, items '%s'", max,
                items);
        // Serialized once, so every retry sends the same bytes and the same request_id.
        var body = InferenceJson.writeBytes(withRequestId(request));
        if (body.length > aiLabels.getMaxJobBytes()) {
            return Mono.error(new IllegalArgumentException(
                    "batch body exceeds aiLabels.maxJobBytes, bytes '%d', max '%d'".formatted(body.length,
                            aiLabels.getMaxJobBytes())));
        }
        var readTimeout = config.getReadTimeout().toJavaDuration()
                .plusSeconds(body.length / config.getRetry().getSubmitBytesPerSecond());
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
            var retry = config.getRetry();
            if (attempt >= retry.getMaxRetries() || !exception.isRetryable()
                    || (call.failFastOnQuotaExceeded() && exception.isQuotaExceeded())
                    || (call.failFastOnDeadlineExceeded() && exception.isDeadlineExceeded())) {
                return Mono.error(exception);
            }
            var backoff = exception.getRetryAfter() != null
                    ? exception.getRetryAfter()
                    : retry.getBaseBackoff().toJavaDuration().multipliedBy(1L << attempt);
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
        Preconditions.checkState(StringUtils.isNotBlank(config.getUrl()),
                "inference.url must be set to call opik-inference");
        Preconditions.checkState(StringUtils.isNotBlank(config.getCredential()),
                "inference.credential must be set to call opik-inference");
        if (call.realtime() && !decidePermits.tryAcquire()) {
            return Mono.error(InferenceException.clientBusy());
        }
        // A decide call keeps its slot until its thread is done: a cancel closes the response, which ends the read.
        Runnable release = call.realtime() ? decidePermits::release : () -> {
        };
        return Mono.<T>create(sink -> {
            var cancelled = new AtomicBoolean();
            var arrived = new AtomicReference<Response>();
            // Closing the response cancels its connection, see CLOSE_WITHOUT_DRAINING, which ends a read blocked on it.
            sink.onCancel(() -> {
                cancelled.set(true);
                closeQuietly(arrived.get());
            });
            var builder = call.target().apply(client.target(config.getUrl()))
                    .request(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.getCredential())
                    // opik-inference never redirects, and following one would send the credential where it points.
                    .property(ClientProperties.FOLLOW_REDIRECTS, false)
                    // A body over the cap could otherwise keep the read going as long as the service sends it.
                    .property(DestinationGuardDnsResolver.PROPERTY, CLOSE_WITHOUT_DRAINING)
                    .property(ClientProperties.CONNECT_TIMEOUT,
                            timeoutMillis(config.getConnectTimeout().toJavaDuration(), left))
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
                if (call.realtime()) {
                    decideScheduler.schedule(() -> {
                        if (cancelled.get()) {
                            release.run();
                            return;
                        }
                        Response response;
                        try {
                            response = call.body() != null
                                    ? builder.method(call.method(),
                                            Entity.entity(call.body(), MediaType.APPLICATION_JSON_TYPE))
                                    : builder.method(call.method());
                        } catch (RuntimeException exception) {
                            callback.failed(exception);
                            return;
                        }
                        callback.completed(response);
                    });
                } else if (call.body() != null) {
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
            var maxBytes = config.getMaxResponseBytes();
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
            var body = capped(response, maxBytes);
            var reader = call.noNullPrimitives() ? InferenceJson.NO_NULL_PRIMITIVES : InferenceJson.MAPPER.reader();
            value = reader.forType(call.type()).readValue(body);
            body.transferTo(OutputStream.nullOutputStream());
            if (value == null) {
                throw InferenceException.upstreamError(status, null, null);
            }
            if (call.check() != null) {
                // A malformed answer, as one that doesn't parse.
                call.check().accept(value);
            }
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
        return value;
    }

    private static byte[] readBody(Response response, int status, long maxBytes) {
        try {
            // hasEntity reads ahead, so it can fail like the read itself.
            if (!response.hasEntity()) {
                return new byte[0];
            }
            return capped(response, maxBytes).readAllBytes();
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
                new IllegalStateException("response body exceeds inference.maxResponseBytes '%d'"
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
     * The body, read up to {@code maxBytes}: a read that finds a byte past them fails with {@link OverTheCap}. Closing
     * it leaves the stream open: the response owns it, and closes it with its connection.
     */
    private static InputStream capped(Response response, long maxBytes) throws IOException {
        return BoundedInputStream.builder()
                .setInputStream(response.readEntity(InputStream.class))
                // One byte over: the stream fails only on a read past maxBytes, so a body of exactly maxBytes passes.
                .setMaxCount(maxBytes + 1)
                .setPropagateClose(false)
                .setOnMaxCount((max, count) -> {
                    throw new OverTheCap();
                })
                .get();
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
            /** Reads the answer with {@link InferenceJson#NO_NULL_PRIMITIVES}. */
            boolean noNullPrimitives,
            /** Throws on an answer that parses but breaks the contract; it fails like one that doesn't parse. */
            Consumer<T> check,
            boolean failFastOnQuotaExceeded,
            boolean failFastOnDeadlineExceeded,
            boolean realtime) {
    }
}
