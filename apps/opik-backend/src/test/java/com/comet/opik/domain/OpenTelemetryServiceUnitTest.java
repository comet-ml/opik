package com.comet.opik.domain;

import com.comet.opik.api.Project;
import com.comet.opik.api.SpanBatch;
import com.comet.opik.api.Trace;
import com.comet.opik.infrastructure.OpenTelemetryConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.podam.PodamFactoryUtils;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucketReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.client.RedisConnectionException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis failure paths of {@link OpenTelemetryServiceImpl}, which a shared Redis container cannot produce without
 * breaking every other Redis user in the app. The happy path, including the trace-id mapping carried across two
 * batches, is covered by {@code OpenTelemetryResourceTest}.
 */
@ExtendWith(MockitoExtension.class)
class OpenTelemetryServiceUnitTest {

    private static final Duration TTL = Duration.ofMinutes(5);

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();
    private final IdGenerator idGenerator = TestIdGeneratorFactory.create();

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
    private String projectName;
    private UUID projectId;
    private String workspaceId;
    private byte[] otelTraceId;
    private String redisKey;

    @BeforeEach
    void setUp() {
        projectName = RandomStringUtils.randomAlphanumeric(10);
        projectId = idGenerator.generateId();
        workspaceId = idGenerator.generateId().toString();
        otelTraceId = UUID.randomUUID().toString().getBytes();
        redisKey = "otelTraceId:%s:%s:%s".formatted(workspaceId, projectId,
                Base64.getEncoder().encodeToString(otelTraceId));

        var config = OpenTelemetryConfig.builder().ttl(io.dropwizard.util.Duration.minutes(5)).build();
        service = new OpenTelemetryServiceImpl(config, traceService, spanService, projectService, redisson);

        when(projectService.getOrCreate(projectName)).thenReturn(Mono.just(podamFactory.manufacturePojo(Project.class)
                .toBuilder().id(projectId).name(projectName).build()));
        doReturn(bucket).when(redisson).getBucket(redisKey);
        lenient().when(traceService.create(any(Trace.class)))
                .thenAnswer(invocation -> Mono.just(invocation.<Trace>getArgument(0).id()));
        lenient().when(spanService.create(any(SpanBatch.class)))
                .thenAnswer(invocation -> Mono.just((long) invocation.<SpanBatch>getArgument(0).spans().size()));
    }

    @Test
    void storesTheBatchWhenTheRedisLookupFails() {
        when(bucket.getAndExpire(TTL)).thenReturn(Mono.error(new RedisConnectionException("stale pool")));
        var startMillis = System.currentTimeMillis() - 1_000;

        var traceId = assertBatchStoredWithAFreshTraceId(startMillis);

        // the mapping may still exist in Redis; overwriting it would split every later batch of the trace
        verify(bucket, never()).set(expectedOpikTraceId(startMillis), TTL);
        assertThat(traceId).isEqualTo(expectedOpikTraceIdAsUuid(startMillis));
    }

    @Test
    void storesTheBatchWhenTheRedisStoreFails() {
        when(bucket.getAndExpire(TTL)).thenReturn(Mono.empty());
        var startMillis = System.currentTimeMillis() - 1_000;
        when(bucket.set(expectedOpikTraceId(startMillis), TTL))
                .thenReturn(Mono.<Void>error(new RedisConnectionException("stale pool")));

        var traceId = assertBatchStoredWithAFreshTraceId(startMillis);

        // the lookup succeeded and found nothing, so the store is attempted and its failure swallowed
        verify(bucket).set(expectedOpikTraceId(startMillis), TTL);
        assertThat(traceId).isEqualTo(expectedOpikTraceIdAsUuid(startMillis));
    }

    @Test
    void propagatesNonRedisErrors() {
        when(bucket.getAndExpire(TTL)).thenReturn(Mono.error(new IllegalStateException("bug")));

        StepVerifier.create(parseAndStore(System.currentTimeMillis()))
                .verifyError(IllegalStateException.class);

        verify(spanService, never()).create(any(SpanBatch.class));
    }

    /**
     * Both Redis failures have the same observable outcome: the batch is stored whole, under a trace id derived from
     * the earliest span start exactly as on a cache miss. Returns that id so each test can assert what differs.
     */
    private UUID assertBatchStoredWithAFreshTraceId(long startMillis) {
        StepVerifier.create(parseAndStore(startMillis))
                .expectNext(2L)
                .verifyComplete();

        var traceCaptor = ArgumentCaptor.forClass(Trace.class);
        verify(traceService).create(traceCaptor.capture());
        var traceId = traceCaptor.getValue().id();
        assertThat(traceId.version()).isEqualTo(7);
        assertThat(traceId.getMostSignificantBits() >>> 16).isEqualTo(startMillis);

        var batchCaptor = ArgumentCaptor.forClass(SpanBatch.class);
        verify(spanService).create(batchCaptor.capture());
        assertThat(batchCaptor.getValue().spans())
                .hasSize(2)
                .allSatisfy(span -> assertThat(span.traceId()).isEqualTo(traceId));

        return traceId;
    }

    /** The id the service derives for an unmapped otel trace id, which is what it stores and what it reports. */
    private UUID expectedOpikTraceIdAsUuid(long startMillis) {
        return OpenTelemetryMapper.convertOtelIdToUUIDv7(otelTraceId, startMillis);
    }

    private String expectedOpikTraceId(long startMillis) {
        return expectedOpikTraceIdAsUuid(startMillis).toString();
    }

    private Mono<Long> parseAndStore(long startMillis) {
        var startNanos = startMillis * 1_000_000L;
        var root = Span.newBuilder()
                .setName(RandomStringUtils.randomAlphanumeric(10))
                .setTraceId(ByteString.copyFrom(otelTraceId))
                .setSpanId(ByteString.copyFrom(UUID.randomUUID().toString().getBytes()))
                .setStartTimeUnixNano(startNanos)
                .setEndTimeUnixNano(startNanos + 1_000_000L)
                .build();
        var child = root.toBuilder()
                .setName(RandomStringUtils.randomAlphanumeric(10))
                .setParentSpanId(root.getSpanId())
                .setSpanId(ByteString.copyFrom(UUID.randomUUID().toString().getBytes()))
                .build();
        var request = ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(ResourceSpans.newBuilder()
                        .addScopeSpans(ScopeSpans.newBuilder().addSpans(root).addSpans(child)))
                .build();

        return service.parseAndStoreSpans(request, projectName)
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, workspaceId));
    }
}
