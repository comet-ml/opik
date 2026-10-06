package com.comet.opik.domain;

import com.comet.opik.api.Project;
import com.comet.opik.api.SpanBatch;
import com.comet.opik.api.Trace;
import com.comet.opik.infrastructure.OpenTelemetryConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucketReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.client.RedisConnectionException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis failure paths of {@link OpenTelemetryServiceImpl}, which a shared Redis container cannot produce without
 * breaking every other Redis user in the app. The happy path is covered by {@code OpenTelemetryResourceTest}.
 */
@ExtendWith(MockitoExtension.class)
class OpenTelemetryServiceUnitTest {

    private static final String PROJECT_NAME = "otel-project";
    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();

    enum FailingOperation {
        LOOKUP,
        STORE
    }

    @Mock
    private TraceService traceService;
    @Mock
    private SpanService spanService;
    @Mock
    private ProjectService projectService;
    @Mock
    private RedissonReactiveClient redisson;
    @Mock
    private RBucketReactive<Object> bucket;

    private OpenTelemetryServiceImpl service;

    @BeforeEach
    void setUp() {
        var config = new OpenTelemetryConfig();
        config.setTtl(io.dropwizard.util.Duration.minutes(5));
        service = new OpenTelemetryServiceImpl(config, traceService, spanService, projectService, redisson);

        when(projectService.getOrCreate(PROJECT_NAME))
                .thenReturn(Mono.just(Project.builder().id(PROJECT_ID).name(PROJECT_NAME).build()));
        doReturn(bucket).when(redisson).getBucket(anyString());
        lenient().when(traceService.create(any(Trace.class)))
                .thenAnswer(invocation -> Mono.just(invocation.<Trace>getArgument(0).id()));
        lenient().when(spanService.create(any(SpanBatch.class)))
                .thenAnswer(invocation -> Mono.just((long) invocation.<SpanBatch>getArgument(0).spans().size()));
    }

    @ParameterizedTest
    @EnumSource(FailingOperation.class)
    void storesTheBatchWhenRedisFails(FailingOperation failing) {
        var redisError = Mono.error(new RedisConnectionException("stale pool"));
        switch (failing) {
            case LOOKUP -> when(bucket.getAndExpire(any(Duration.class))).thenReturn(redisError);
            case STORE -> {
                when(bucket.getAndExpire(any(Duration.class))).thenReturn(Mono.empty());
                when(bucket.set(any(), any(Duration.class))).thenReturn(redisError.then());
            }
        }
        var startMillis = System.currentTimeMillis() - 1_000;

        StepVerifier.create(parseAndStore(startMillis))
                .expectNext(2L)
                .verifyComplete();

        var traceCaptor = ArgumentCaptor.forClass(Trace.class);
        verify(traceService).create(traceCaptor.capture());
        var traceId = traceCaptor.getValue().id();
        // A fresh id is a UUIDv7 stamped with the earliest span start, as on a cache miss.
        assertThat(traceId.version()).isEqualTo(7);
        assertThat(traceId.getMostSignificantBits() >>> 16).isEqualTo(startMillis);

        var batchCaptor = ArgumentCaptor.forClass(SpanBatch.class);
        verify(spanService).create(batchCaptor.capture());
        assertThat(batchCaptor.getValue().spans())
                .hasSize(2)
                .allSatisfy(span -> assertThat(span.traceId()).isEqualTo(traceId));

        if (failing == FailingOperation.LOOKUP) {
            // the mapping may still exist in Redis; overwriting it would split every later batch of the trace
            verify(bucket, never()).set(any(), any(Duration.class));
        }
    }

    @Test
    void propagatesNonRedisErrors() {
        when(bucket.getAndExpire(any(Duration.class))).thenReturn(Mono.error(new IllegalStateException("bug")));

        StepVerifier.create(parseAndStore(System.currentTimeMillis()))
                .verifyError(IllegalStateException.class);

        verify(spanService, never()).create(any(SpanBatch.class));
    }

    private Mono<Long> parseAndStore(long startMillis) {
        var startNanos = startMillis * 1_000_000L;
        var root = Span.newBuilder()
                .setName("root")
                .setTraceId(ByteString.copyFrom(UUID.randomUUID().toString().getBytes()))
                .setSpanId(ByteString.copyFrom(UUID.randomUUID().toString().getBytes()))
                .setStartTimeUnixNano(startNanos)
                .setEndTimeUnixNano(startNanos + 1_000_000L)
                .build();
        var child = root.toBuilder()
                .setName("child")
                .setParentSpanId(root.getSpanId())
                .setSpanId(ByteString.copyFrom(UUID.randomUUID().toString().getBytes()))
                .build();
        var request = ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(ResourceSpans.newBuilder()
                        .addScopeSpans(ScopeSpans.newBuilder().addSpans(root).addSpans(child)))
                .build();

        return service.parseAndStoreSpans(request, PROJECT_NAME)
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, WORKSPACE_ID));
    }
}
