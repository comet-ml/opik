package com.comet.opik.domain.topics;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.comet.opik.domain.ailabels.AiLabelEndpoint;
import com.comet.opik.domain.ailabels.AiLabelTask;
import com.comet.opik.domain.ailabels.AiLabelTaskRegistry;
import com.comet.opik.infrastructure.AiLabelsConfig;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.inference.EmbedRequest;
import com.comet.opik.infrastructure.inference.EmbedResponse;
import com.comet.opik.infrastructure.inference.EmbedResult;
import com.comet.opik.infrastructure.inference.InferenceClient;
import com.comet.opik.infrastructure.inference.InferenceException;
import com.comet.opik.infrastructure.lock.LockService;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleGaugeBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.DoubleHistogramBuilder;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongCounterBuilder;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableDoubleMeasurement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import ru.vyarus.guicey.jdbi3.tx.TxConfig;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.opentelemetry.api.common.AttributeKey.longKey;
import static io.opentelemetry.api.common.AttributeKey.stringKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("topic vectors tick, without containers")
class TopicVectorsTickServiceTest {

    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final Instant APPLIES_FROM = Instant.parse("2026-11-01T00:00:00Z");

    @Nested
    @DisplayName("normalize")
    class Normalize {

        @Test
        @DisplayName("scales a vector to length 1")
        void scalesToUnitLength() {
            assertThat(TopicVectorsTickService.normalize(List.of(3f, 4f))).contains(List.of(0.6f, 0.8f));

            var normalized = TopicVectorsTickService.normalize(vectorOf("any text")).orElseThrow();
            var length = Math.sqrt(normalized.stream().mapToDouble(value -> (double) value * value).sum());
            assertThat(length).isCloseTo(1.0, within(1e-6));
        }

        @Test
        @DisplayName("rejects a zero or non-finite vector")
        void rejectsZeroAndNonFinite() {
            assertThat(TopicVectorsTickService.normalize(Collections.nCopies(384, 0f))).isEmpty();
            assertThat(TopicVectorsTickService.normalize(List.of(1f, Float.NaN))).isEmpty();
        }
    }

    static Stream<Arguments> windows() {
        return Stream.of(
                // Long after applies_from: the window decides.
                arguments(Duration.ofHours(24), NOW.minus(Duration.ofHours(24))),
                arguments(Duration.ofDays(7), NOW.minus(Duration.ofDays(7))),
                // A window reaching before applies_from starts at applies_from.
                arguments(Duration.ofDays(30), APPLIES_FROM));
    }

    @ParameterizedTest(name = "window {0} starts at {1}")
    @MethodSource("windows")
    @DisplayName("the window's lower bound is max(applies_from, now - window)")
    void lowerBound(Duration window, Instant expected) {
        assertThat(TopicVectorsTickService.lowerBound(APPLIES_FROM, NOW, window)).isEqualTo(expected);
    }

    static Stream<Arguments> backOffs() {
        return Stream.of(
                arguments(429, "rate_limited", false, true),
                arguments(429, "quota_exceeded", true, true),
                arguments(503, "something_new", false, true),
                arguments(502, "upstream_error", true, true),
                arguments(400, "invalid_request", false, false),
                arguments(403, "org_not_allowed", false, false));
    }

    @ParameterizedTest(name = "status {0}, code {1}, retryable {2}: backs off {3}")
    @MethodSource("backOffs")
    @DisplayName("any 429 or 5xx backs the workspace off, whatever its code")
    void backsOff(int status, String code, boolean retryable, boolean expected) {
        assertThat(TopicVectorsTickService.backsOff(inferenceException(status, code, retryable))).isEqualTo(expected);
    }

    @Nested
    @DisplayName("summary text")
    class SummaryText {

        private final TopicVectorCandidate candidate = candidate(UUID.randomUUID(), 2, NOW);

        @Test
        @DisplayName("is the summary exactly as stored")
        void normalRow() {
            var summary = summary(candidate, 2, " Refund a duplicate invoice charge");

            assertThat(TopicVectorsTickService.summaryText(candidate, summary))
                    .contains(" Refund a duplicate invoice charge");
        }

        @Test
        @DisplayName("is absent for an empty summary, a missing output row, or another task version")
        void absent() {
            assertThat(TopicVectorsTickService.summaryText(candidate, summary(candidate, 2, ""))).isEmpty();
            assertThat(TopicVectorsTickService.summaryText(candidate, summary(candidate, 2, "  "))).isEmpty();
            assertThat(TopicVectorsTickService.summaryText(candidate, summary(candidate, 2, " \n\t "))).isEmpty();
            assertThat(TopicVectorsTickService.summaryText(candidate, null)).isEmpty();
            assertThat(TopicVectorsTickService.summaryText(candidate, summary(candidate, 1, "older text")))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("tick")
    class Tick {

        private TopicsConfig config;
        private TopicVectorsDAO dao;
        private InferenceClient client;
        private LockService lockService;
        private TransactionTemplate template;
        private AiLabelTaskRegistry registry;
        private TestMeter meter;
        private TopicVectorsTickService service;
        private final List<EmbedRequest> requests = Collections.synchronizedList(new ArrayList<>());
        private final List<TopicVector> inserted = Collections.synchronizedList(new ArrayList<>());

        @BeforeEach
        @SuppressWarnings("unchecked")
        void setUp() {
            config = new TopicsConfig();
            dao = mock(TopicVectorsDAO.class);
            client = mock(InferenceClient.class);
            lockService = mock(LockService.class);
            template = mock(TransactionTemplate.class);
            registry = mock(AiLabelTaskRegistry.class);
            meter = new TestMeter();
            when(template.inTransaction(any(TxConfig.class), any())).thenReturn(List.of(WORKSPACE_ID));
            when(registry.find(TopicVectorsTickService.TASK)).thenReturn(java.util.Optional.of(task()));
            // The long-window pass is not due unless a test says so.
            when(lockService.lockUsingToken(any(), any())).thenReturn(Mono.just(false));
            when(lockService.unlockUsingToken(any())).thenReturn(Mono.empty());
            when(client.embed(any(), any())).thenAnswer(invocation -> {
                EmbedRequest request = invocation.getArgument(0);
                requests.add(request);
                return Mono.just(EmbedResponse.builder()
                        .result(EmbedResult.builder()
                                .embeddings(request.inputs().stream().map(TopicVectorsTickServiceTest::vectorOf)
                                        .toList())
                                .dimensions(384)
                                .build())
                        .build());
            });
            when(dao.insert(any())).thenAnswer(invocation -> {
                List<TopicVector> rows = invocation.getArgument(0);
                inserted.addAll(rows);
                return Mono.just((long) rows.size());
            });
            service = new TopicVectorsTickService(config, new AiLabelsConfig(), template, dao, client, lockService,
                    registry, meter.meter);
        }

        @Test
        @DisplayName("600 texts go out as 256 + 256 + 88 in order, and each vector is stored with its own trace")
        void chunksInOrder() {
            var candidates = IntStream.range(0, 600)
                    .mapToObj(i -> candidate(UUID.randomUUID(), 1, NOW.minusSeconds(600 - i)))
                    .toList();
            stub(candidates, candidate -> "intent of " + candidate.traceId());

            var result = service.tick(NOW);

            assertThat(requests).extracting(request -> request.inputs().size()).containsExactly(256, 256, 88);
            assertThat(requests).allSatisfy(request -> {
                assertThat(request.orgId()).isEqualTo(WORKSPACE_ID);
                assertThat(request.cache()).isFalse();
            });
            assertThat(requests.stream().flatMap(request -> request.inputs().stream()).toList())
                    .containsExactlyElementsOf(candidates.stream().map(c -> "intent of " + c.traceId()).toList());
            assertThat(inserted).hasSize(600).allSatisfy(vector -> {
                assertThat(vector.text()).isEqualTo("intent of " + vector.traceId());
                assertThat(vector.vector())
                        .isEqualTo(TopicVectorsTickService.normalize(vectorOf(vector.text())).orElseThrow());
                assertThat(vector.embedModel()).isEqualTo(TopicVectorsTickService.EMBED_MODEL);
                assertThat(vector.embedDimensions()).isEqualTo(384);
            });
            assertThat(result.written()).isEqualTo(600);
            assertThat(result.skipped()).isEmpty();
        }

        @Test
        @DisplayName("a zero vector and a missing summary are skipped and counted, the rest written")
        void zeroVectorAndMissingSummary() {
            var zero = candidate(UUID.randomUUID(), 1, NOW.minusSeconds(3));
            var missing = candidate(UUID.randomUUID(), 1, NOW.minusSeconds(2));
            var normal = candidate(UUID.randomUUID(), 1, NOW.minusSeconds(1));
            stub(List.of(zero, missing, normal), candidate -> candidate == zero ? "zero" : "fine");
            when(dao.findSummaries(anyString(), anyString(), any(), anyLong())).thenReturn(Flux.just(
                    summary(zero, 1, "zero"), summary(normal, 1, "fine")));

            var result = service.tick(NOW);

            assertThat(inserted).extracting(TopicVector::traceId).containsExactly(normal.traceId());
            assertThat(result.skipped()).isEqualTo(Map.of(
                    TopicVectorsTickService.INVALID_VECTOR, 1,
                    TopicVectorsTickService.MISSING_SUMMARY, 1));
        }

        @Test
        @DisplayName("a null embedding and a null element cost only their own traces, the rest of the chunk is written")
        void nullEmbeddingAndNullElement() {
            var nullEmbedding = candidate(UUID.randomUUID(), 1, NOW.minusSeconds(3));
            var nullElement = candidate(UUID.randomUUID(), 1, NOW.minusSeconds(2));
            var normal = candidate(UUID.randomUUID(), 1, NOW.minusSeconds(1));
            stub(List.of(nullEmbedding, nullElement, normal), candidate -> "intent of " + candidate.traceId());
            var withNull = new ArrayList<>(vectorOf("x"));
            withNull.set(7, null);
            var embeddings = new ArrayList<List<Float>>();
            embeddings.add(null);
            embeddings.add(withNull);
            embeddings.add(vectorOf("intent of " + normal.traceId()));
            answerEmbed(EmbedResponse.builder()
                    .result(EmbedResult.builder().embeddings(embeddings).dimensions(384).build())
                    .build());

            var result = service.tick(NOW);

            assertThat(inserted).extracting(TopicVector::traceId).containsExactly(normal.traceId());
            assertThat(result.written()).isOne();
            assertThat(result.skipped()).isEqualTo(Map.of(TopicVectorsTickService.INVALID_VECTOR, 2));
        }

        @Test
        @DisplayName("a response without embeddings counts its whole chunk as invalid, and the next chunk still runs")
        void nullEmbeddingsList() {
            config.getVectors().setEmbedBatchSize(2);
            var candidates = IntStream.range(0, 4)
                    .mapToObj(i -> candidate(UUID.randomUUID(), 1, NOW.minusSeconds(4 - i)))
                    .toList();
            stub(candidates, candidate -> "intent of " + candidate.traceId());
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            doAnswer(invocation -> {
                EmbedRequest request = invocation.getArgument(0);
                requests.add(request);
                if (calls.incrementAndGet() == 1) {
                    return Mono.just(EmbedResponse.builder()
                            .result(EmbedResult.builder().embeddings(null).dimensions(384).build())
                            .build());
                }
                return Mono.just(EmbedResponse.builder()
                        .result(EmbedResult.builder()
                                .embeddings(request.inputs().stream().map(TopicVectorsTickServiceTest::vectorOf)
                                        .toList())
                                .dimensions(384)
                                .build())
                        .build());
            }).when(client).embed(any(), any());

            var result = service.tick(NOW);

            assertThat(requests).hasSize(2);
            assertThat(inserted).extracting(TopicVector::traceId)
                    .containsExactly(candidates.get(2).traceId(), candidates.get(3).traceId());
            assertThat(result.skipped()).isEqualTo(Map.of(TopicVectorsTickService.INVALID_VECTOR, 2));
        }

        @Test
        @DisplayName("an empty answer or one without a result counts the chunk as invalid and throws nothing")
        void nullResult() {
            config.getVectors().setEmbedBatchSize(1);
            var candidates = List.of(candidate(UUID.randomUUID(), 1, NOW.minusSeconds(2)),
                    candidate(UUID.randomUUID(), 1, NOW.minusSeconds(1)));
            stub(candidates, c -> "intent");
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            doAnswer(invocation -> calls.incrementAndGet() == 1
                    ? Mono.empty()
                    : Mono.just(EmbedResponse.builder().build())).when(client).embed(any(), any());

            var result = service.tick(NOW);

            assertThat(inserted).isEmpty();
            assertThat(result.skipped()).isEqualTo(Map.of(TopicVectorsTickService.INVALID_VECTOR, 2));
        }

        private void answerEmbed(EmbedResponse response) {
            doAnswer(invocation -> {
                requests.add(invocation.getArgument(0));
                return Mono.just(response);
            }).when(client).embed(any(), any());
        }

        @Test
        @DisplayName("the short and the long window never start before applies_from, and both bound trace_created_at "
                + "by the ai-labels horizon plus the slack")
        void windowBounds() {
            stub(List.of(), candidate -> "");
            config.getVectors().setLongWindow(io.dropwizard.util.Duration.days(30));
            var horizonAndSlack = Duration.ofDays(7).plus(Duration.ofDays(1));

            service.tick(NOW);
            var shortLower = NOW.minus(Duration.ofHours(24));
            verify(dao).findUnvectored(eq(WORKSPACE_ID), eq(TopicVectorsTickService.TASK), eq(shortLower), eq(NOW),
                    eq(shortLower.minus(horizonAndSlack)), eq(5_000), anyLong());

            longWindowDue();
            var result = service.tick(NOW);
            assertThat(result.longWindowPass()).isTrue();
            verify(dao).findUnvectored(eq(WORKSPACE_ID), eq(TopicVectorsTickService.TASK), eq(APPLIES_FROM),
                    eq(NOW), eq(APPLIES_FROM.minus(horizonAndSlack)), eq(5_000), anyLong());
        }

        @Test
        @DisplayName("with a backfill window, the long-window pass uses it instead of the long window")
        void backfillWindow() {
            stub(List.of(), candidate -> "");
            config.getVectors().setBackfillWindow(io.dropwizard.util.Duration.days(12));
            longWindowDue();

            var result = service.tick(NOW);

            assertThat(result.longWindowPass()).isTrue();
            verify(dao).findUnvectored(eq(WORKSPACE_ID), eq(TopicVectorsTickService.TASK),
                    eq(NOW.minus(Duration.ofDays(12))), eq(NOW), any(), eq(5_000), anyLong());
            assertThat(TopicVectorsTickService.longWindow(new TopicsConfig().getVectors()))
                    .isEqualTo(io.dropwizard.util.Duration.days(7));
        }

        @Test
        @DisplayName("the long-window pass stays due while its ticks reach the cap, and starts the interval once one "
                + "finds fewer")
        void longWindowUntilDrained() {
            config.getVectors().setMaxTracesPerTick(2);
            longWindowDue();
            var lease = service.leaderLease();
            var full = IntStream.range(0, 2).mapToObj(i -> candidate(UUID.randomUUID(), 1, NOW.minusSeconds(9 - i)))
                    .toList();
            stub(full, candidate -> "intent of " + candidate.traceId());

            assertThat(service.tick(NOW).written()).isEqualTo(2);

            verify(lockService).lockUsingToken(TopicVectorsTickService.LONG_WINDOW_LOCK, lease);
            verify(lockService).unlockUsingToken(TopicVectorsTickService.LONG_WINDOW_LOCK);
            verify(lockService, never()).lockUsingToken(TopicVectorsTickService.LONG_WINDOW_LOCK,
                    Duration.ofDays(1));

            stub(List.of(candidate(UUID.randomUUID(), 1, NOW.minusSeconds(1))), candidate -> "last");

            assertThat(service.tick(NOW).written()).isOne();

            var inOrder = inOrder(lockService);
            inOrder.verify(lockService).unlockUsingToken(TopicVectorsTickService.LONG_WINDOW_LOCK);
            inOrder.verify(lockService).lockUsingToken(TopicVectorsTickService.LONG_WINDOW_LOCK, Duration.ofDays(1));
        }

        @Test
        @DisplayName("a long-window pass cut by a back-off is not drained")
        void longWindowNotDrainedAfterBackOff() {
            longWindowDue();
            stub(List.of(candidate(UUID.randomUUID(), 1, NOW.minusSeconds(1))), candidate -> "x");
            var exception = inferenceException(503, "something_new", false);
            doReturn(Mono.error(exception)).when(client).embed(any(), any());

            var result = service.tick(NOW);

            assertThat(result.skipped()).isEqualTo(Map.of(TopicVectorsTickService.EMBED_FAILED, 1));
            verify(lockService, never()).lockUsingToken(TopicVectorsTickService.LONG_WINDOW_LOCK,
                    Duration.ofDays(1));
        }

        @Test
        @DisplayName("the leader lease is the budget plus the embed timeout plus the find timeout plus 5 s")
        void leaderLease() {
            assertThat(service.leaderLease()).isEqualTo(Duration.ofSeconds(50 + 10 + 30 + 5));

            config.getVectors().setTickBudget(io.dropwizard.util.Duration.seconds(100));
            config.getVectors().setEmbedTimeout(io.dropwizard.util.Duration.seconds(20));
            config.getVectors().getFind().setMaxExecutionTime(io.dropwizard.util.Duration.seconds(40));
            assertThat(service.leaderLease()).isEqualTo(Duration.ofSeconds(165));
        }

        @Test
        @DisplayName("workspaces are read from an offset that moves by one every tick interval")
        void rotation() {
            when(template.inTransaction(any(TxConfig.class), any())).thenReturn(List.of("a", "b", "c"));
            when(dao.findUnvectored(anyString(), anyString(), any(), any(), any(), anyInt(), anyLong()))
                    .thenReturn(Flux.empty());
            var order = new ArrayList<List<String>>();
            for (int minute = 0; minute < 4; minute++) {
                var start = NOW.plus(Duration.ofMinutes(minute));
                var calls = new ArrayList<String>();
                doAnswer(invocation -> {
                    calls.add(invocation.getArgument(0));
                    return Flux.empty();
                }).when(dao).findUnvectored(anyString(), anyString(), any(), any(), any(), anyInt(), anyLong());
                service.tick(start);
                order.add(calls);
            }

            var first = order.getFirst();
            assertThat(first).containsExactlyInAnyOrder("a", "b", "c");
            for (int i = 1; i < order.size(); i++) {
                assertThat(order.get(i)).isEqualTo(TopicVectorsTickService.rotated(order.get(i - 1), 1));
            }
            assertThat(TopicVectorsTickService.rotated(List.of("a", "b", "c"), 4)).containsExactly("b", "c", "a");
            assertThat(TopicVectorsTickService.rotated(List.of("a", "b", "c"), -1)).containsExactly("c", "a", "b");
            assertThat(TopicVectorsTickService.rotated(List.<String>of(), 7)).isEmpty();
        }

        @Test
        @DisplayName("the cap is per workspace, and one workspace's 5xx with an unknown code backs off only that one")
        void perWorkspaceCapAndBackOff() {
            config.getVectors().setMaxTracesPerTick(3);
            config.getVectors().setEmbedBatchSize(1);
            when(template.inTransaction(any(TxConfig.class), any())).thenReturn(List.of("failing", "healthy"));
            var failing = IntStream.range(0, 3).mapToObj(i -> candidate("failing", NOW.minusSeconds(10 - i)))
                    .toList();
            var healthy = IntStream.range(0, 3).mapToObj(i -> candidate("healthy", NOW.minusSeconds(10 - i)))
                    .toList();
            when(dao.findUnvectored(anyString(), anyString(), any(), any(), any(), anyInt(), anyLong()))
                    .thenAnswer(invocation -> Flux.fromIterable(
                            "failing".equals(invocation.getArgument(0)) ? failing : healthy));
            when(dao.findSummaries(anyString(), anyString(), any(), anyLong())).thenAnswer(invocation -> {
                Set<UUID> traceIds = invocation.getArgument(2);
                return Flux.concat(Flux.fromIterable(failing), Flux.fromIterable(healthy))
                        .filter(candidate -> traceIds.contains(candidate.traceId()))
                        .map(candidate -> summary(candidate, 1, "intent of " + candidate.traceId()));
            });
            var exception = inferenceException(503, "something_new", false);
            doAnswer(invocation -> {
                EmbedRequest request = invocation.getArgument(0);
                requests.add(request);
                if ("failing".equals(request.orgId())) {
                    return Mono.error(exception);
                }
                return Mono.just(EmbedResponse.builder()
                        .result(EmbedResult.builder()
                                .embeddings(request.inputs().stream().map(TopicVectorsTickServiceTest::vectorOf)
                                        .toList())
                                .dimensions(384)
                                .build())
                        .build());
            }).when(client).embed(any(), any());

            var result = service.tick(NOW);

            verify(dao, times(2)).findUnvectored(anyString(), anyString(), any(), any(), any(), eq(3), anyLong());
            assertThat(requests).filteredOn(request -> request.orgId().equals("failing")).hasSize(1);
            assertThat(inserted).extracting(TopicVector::traceId)
                    .containsExactlyElementsOf(healthy.stream().map(TopicVectorCandidate::traceId).toList());
            assertThat(result.skipped()).isEqualTo(Map.of(TopicVectorsTickService.EMBED_FAILED, 3));
        }

        @Test
        @DisplayName("a chunk is not started with less than the embed timeout left: its traces are over_budget")
        void noChunkNearTheBudgetEnd() {
            config.getVectors().setTickBudget(io.dropwizard.util.Duration.milliseconds(1_500));
            config.getVectors().setEmbedTimeout(io.dropwizard.util.Duration.seconds(1));
            config.getVectors().setEmbedBatchSize(1);
            var early = earlyTask();
            when(registry.find(TopicVectorsTickService.TASK)).thenReturn(java.util.Optional.of(early));
            var now = Instant.now();
            var candidates = IntStream.range(0, 3).mapToObj(i -> candidate(UUID.randomUUID(), 1, now.minusSeconds(5)))
                    .toList();
            stub(candidates, candidate -> "intent of " + candidate.traceId());
            var deadlines = new ArrayList<Duration>();
            doAnswer(invocation -> {
                EmbedRequest request = invocation.getArgument(0);
                Instant callDeadline = invocation.getArgument(1);
                deadlines.add(Duration.between(Instant.now(), callDeadline));
                requests.add(request);
                // Leaves less than the embed timeout of the 1.5 s budget.
                Thread.sleep(600);
                return Mono.just(EmbedResponse.builder()
                        .result(EmbedResult.builder()
                                .embeddings(request.inputs().stream().map(TopicVectorsTickServiceTest::vectorOf)
                                        .toList())
                                .dimensions(384)
                                .build())
                        .build());
            }).when(client).embed(any(), any());

            var result = service.tick(Instant.now());

            assertThat(requests).hasSize(1);
            assertThat(result.written()).isOne();
            assertThat(result.skipped()).isEqualTo(Map.of(TopicVectorsTickService.OVER_BUDGET, 2));
            // A call that starts gets its whole timeout, even when that runs past the tick's budget.
            assertThat(deadlines).singleElement()
                    .satisfies(left -> assertThat(left).isGreaterThan(Duration.ofMillis(900)));
        }

        @ParameterizedTest(name = "{0} ms left: {1} window reads")
        // Time only shrinks between here and the read, so the cases sit at the far side of each bound: 1,990 ms keeps
        // ~1 s of slack above the 1 s minimum and still rounds down to max_execution_time = 1; 0 and 900 ms can only
        // shrink, so they stay under it.
        @CsvSource({"0, 0", "900, 0", "1990, 1"})
        @DisplayName("a window read starts only with at least a second left in the tick, and gets the whole seconds left")
        void noWindowReadUnderASecondLeft(long leftMillis, int reads) {
            when(registry.find(TopicVectorsTickService.TASK)).thenReturn(java.util.Optional.of(earlyTask()));
            stub(List.of(), candidate -> "");
            var budget = config.getVectors().getTickBudget().toJavaDuration();
            var appender = new ListAppender<ILoggingEvent>();
            var logger = (Logger) LoggerFactory.getLogger(TopicVectorsTickService.class);
            appender.start();
            logger.addAppender(appender);
            try {
                service.tick(Instant.now().minus(budget).plusMillis(leftMillis));

                verify(dao, times(reads)).findUnvectored(anyString(), anyString(), any(), any(), any(), anyInt(),
                        eq(1L));
                verify(dao, times(reads)).findUnvectored(anyString(), anyString(), any(), any(), any(), anyInt(),
                        anyLong());
                assertThat(appender.list)
                        .filteredOn(event -> event.getFormattedMessage().contains("workspaces not read"))
                        .hasSize(1 - reads);
            } finally {
                logger.detachAppender(appender);
            }
        }

        @Test
        @DisplayName("done statuses without a summary log one WARN per workspace with the count and the oldest trace")
        void missingSummaryWarn() {
            var oldest = candidate(UUID.randomUUID(), 1, NOW.minusSeconds(30));
            var newer = candidate(UUID.randomUUID(), 1, NOW.minusSeconds(20));
            var normal = candidate(UUID.randomUUID(), 1, NOW.minusSeconds(10));
            stub(List.of(oldest, newer, normal), candidate -> candidate == normal ? "fine" : "   ");
            var appender = new ListAppender<ILoggingEvent>();
            var logger = (Logger) LoggerFactory.getLogger(TopicVectorsTickService.class);
            appender.start();
            logger.addAppender(appender);
            try {
                var result = service.tick(NOW);

                assertThat(result.skipped()).isEqualTo(Map.of(TopicVectorsTickService.MISSING_SUMMARY, 2));
                assertThat(appender.list)
                        .filteredOn(event -> event.getFormattedMessage().contains("without a usable summary"))
                        .singleElement()
                        .satisfies(event -> {
                            assertThat(event.getLevel()).isEqualTo(Level.WARN);
                            assertThat(event.getFormattedMessage())
                                    .contains("workspace '" + WORKSPACE_ID + "'")
                                    .contains("traces '2'")
                                    .contains("oldest trace '" + oldest.traceId() + "'");
                        });
            } finally {
                logger.detachAppender(appender);
            }
        }

        @Test
        @DisplayName("a failed window read is counted, and the workspace has no gauge value for that tick")
        void findFailure() {
            when(template.inTransaction(any(TxConfig.class), any())).thenReturn(List.of("broken", "fine"));
            var fine = candidate("fine", NOW.minusSeconds(60));
            when(dao.findUnvectored(anyString(), anyString(), any(), any(), any(), anyInt(), anyLong()))
                    .thenAnswer(invocation -> "broken".equals(invocation.getArgument(0))
                            ? Flux.error(new IllegalStateException("too many rows"))
                            : Flux.just(fine));
            when(dao.findSummaries(anyString(), anyString(), any(), anyLong()))
                    .thenReturn(Flux.just(summary(fine, 1, "fine")));
            config.getVectors().setTickInterval(io.dropwizard.util.Duration.hours(1));
            when(registry.find(TopicVectorsTickService.TASK)).thenReturn(java.util.Optional.of(earlyTask()));

            service.tick(Instant.now());

            verify(meter.counter("topics.vectors.read.failures"))
                    .add(1, Attributes.of(stringKey("phase"), "find", stringKey("workspace_id"), "broken",
                            stringKey("error_type"), "IllegalStateException"));
            assertThat(meter.observeGauge()).containsOnlyKeys("fine");
        }

        @Test
        @DisplayName("a failed summary read is skipped as read_failed and counted")
        void summaryReadFailure() {
            stub(List.of(candidate(UUID.randomUUID(), 1, NOW.minusSeconds(1))), candidate -> "x");
            when(dao.findSummaries(anyString(), anyString(), any(), anyLong()))
                    .thenReturn(Flux.error(new IllegalStateException("down")));

            var result = service.tick(NOW);

            assertThat(result.skipped()).isEqualTo(Map.of(TopicVectorsTickService.READ_FAILED, 1));
            verify(meter.counter("topics.vectors.read.failures"))
                    .add(1, Attributes.of(stringKey("phase"), "summaries", stringKey("workspace_id"), WORKSPACE_ID,
                            stringKey("error_type"), "IllegalStateException"));
            verify(meter.counter("topics.vectors.skipped"))
                    .add(1, Attributes.of(stringKey("reason"), TopicVectorsTickService.READ_FAILED));
        }

        @Test
        @DisplayName("the gauge is published only while this pod's last tick is under two tick intervals old")
        void gaugeGoesStale() throws InterruptedException {
            config.getVectors().setTickInterval(io.dropwizard.util.Duration.milliseconds(100));
            when(registry.find(TopicVectorsTickService.TASK)).thenReturn(java.util.Optional.of(earlyTask()));
            stub(List.of(), candidate -> "");

            assertThat(meter.observeGauge()).isEmpty();
            service.tick(Instant.now());
            assertThat(meter.observeGauge()).containsOnlyKeys(WORKSPACE_ID);

            Thread.sleep(300);
            assertThat(meter.observeGauge()).isEmpty();
        }

        @Test
        @DisplayName("an embed failure is counted by status and code, and a long-window pass counts only the old rows "
                + "it wrote as missed")
        void metrics() {
            config.getVectors().setEmbedBatchSize(1);
            longWindowDue();
            var old = candidate(UUID.randomUUID(), 1, NOW.minus(Duration.ofDays(2)));
            var recent = candidate(UUID.randomUUID(), 1, NOW.minusSeconds(5));
            var oldFailing = candidate(UUID.randomUUID(), 1, NOW.minus(Duration.ofDays(3)));
            stub(List.of(oldFailing, old, recent), candidate -> candidate == oldFailing ? "bad" : "fine " + candidate);
            var exception = inferenceException(400, "invalid_request", false);
            doAnswer(invocation -> {
                EmbedRequest request = invocation.getArgument(0);
                if (request.inputs().getFirst().equals("bad")) {
                    return Mono.error(exception);
                }
                return Mono.just(EmbedResponse.builder()
                        .result(EmbedResult.builder()
                                .embeddings(request.inputs().stream().map(TopicVectorsTickServiceTest::vectorOf)
                                        .toList())
                                .dimensions(384)
                                .build())
                        .build());
            }).when(client).embed(any(), any());

            var result = service.tick(NOW);

            assertThat(result.written()).isEqualTo(2);
            verify(meter.counter("topics.vectors.embed.failures"))
                    .add(1, Attributes.of(longKey("http_status"), 400L, stringKey("code"), "invalid_request"));
            verify(meter.counter("topics.vectors.long_window.missed")).add(1L);
            verify(meter.counter("topics.vectors.long_window.missed"), never()).add(2L);
        }

        private void longWindowDue() {
            when(lockService.lockUsingToken(eq(TopicVectorsTickService.LONG_WINDOW_LOCK), any()))
                    .thenAnswer(invocation -> Mono.just(!Duration.ofDays(1).equals(invocation.getArgument(1))));
        }

        @Test
        @DisplayName("before the task applies, nothing is read")
        void beforeAppliesFrom() {
            var result = service.tick(APPLIES_FROM.minusSeconds(1));

            assertThat(result.written()).isZero();
            verify(dao, never()).findUnvectored(any(), any(), any(), any(), any(), anyInt(), anyLong());
        }

        private void stub(List<TopicVectorCandidate> candidates,
                java.util.function.Function<TopicVectorCandidate, String> text) {
            when(dao.findUnvectored(anyString(), anyString(), any(), any(), any(), anyInt(), anyLong()))
                    .thenReturn(Flux.fromIterable(candidates));
            when(dao.findSummaries(anyString(), anyString(), any(), anyLong())).thenAnswer(invocation -> {
                Set<UUID> traceIds = invocation.getArgument(2);
                return Flux.fromIterable(candidates)
                        .filter(candidate -> traceIds.contains(candidate.traceId()))
                        .map(candidate -> summary(candidate, candidate.taskVersion(), text.apply(candidate)));
            });
        }
    }

    /** A deterministic, non-unit 384-number vector per text; "zero" gives the zero vector. */
    static List<Float> vectorOf(String text) {
        if (text.equals("zero")) {
            return Collections.nCopies(384, 0f);
        }
        int seed = text.hashCode();
        return IntStream.range(0, 384)
                .mapToObj(i -> (float) (Math.floorMod(seed + i * 31, 1_000) + 1) / 100f)
                .toList();
    }

    private static AiLabelTask task() {
        return AiLabelTask.builder()
                .name(TopicVectorsTickService.TASK)
                .endpoint(AiLabelEndpoint.GENERATE)
                .version(2)
                .appliesFrom(Instant.parse("2026-11-10T00:00:00Z"))
                .history(List.of(AiLabelTask.Version.builder().version(1).appliesFrom(APPLIES_FROM).build()))
                .build();
    }

    private static TopicVectorCandidate candidate(UUID traceId, int taskVersion, Instant statusUpdatedAt) {
        return TopicVectorCandidate.builder()
                .workspaceId(WORKSPACE_ID)
                .projectId(UUID.randomUUID())
                .traceId(traceId)
                .traceCreatedAt(statusUpdatedAt.minusSeconds(60))
                .taskVersion(taskVersion)
                .statusUpdatedAt(statusUpdatedAt)
                .build();
    }

    private static TopicSummary summary(TopicVectorCandidate candidate, int taskVersion, String text) {
        return TopicSummary.builder().traceId(candidate.traceId()).taskVersion(taskVersion).summary(text).build();
    }

    private static TopicVectorCandidate candidate(String workspaceId, Instant statusUpdatedAt) {
        return candidate(UUID.randomUUID(), 1, statusUpdatedAt).toBuilder().workspaceId(workspaceId).build();
    }

    /** A task that applied long before the real clock, for tests that tick at the real time. */
    private static AiLabelTask earlyTask() {
        return task().toBuilder()
                .appliesFrom(Instant.parse("2020-01-02T00:00:00Z"))
                .history(List.of(AiLabelTask.Version.builder().version(1)
                        .appliesFrom(Instant.parse("2020-01-01T00:00:00Z")).build()))
                .build();
    }

    /** A real InferenceException, as the client builds it from an error envelope; its constructor is the client's. */
    private static InferenceException inferenceException(int httpStatus, String code, boolean retryable) {
        try {
            var constructor = InferenceException.class.getDeclaredConstructor(String.class, boolean.class, int.class,
                    Duration.class, String.class, Throwable.class);
            constructor.setAccessible(true);
            return constructor.newInstance(code, retryable, httpStatus, null, null, null);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** A Meter whose instruments are mocks to verify, by name, and whose gauge callback can be observed. */
    static final class TestMeter {

        final Meter meter = mock(Meter.class);
        private final Map<String, LongCounter> counters = new ConcurrentHashMap<>();
        private final AtomicReference<Consumer<ObservableDoubleMeasurement>> gauge = new AtomicReference<>();

        TestMeter() {
            when(meter.counterBuilder(anyString())).thenAnswer(invocation -> {
                var builder = mock(LongCounterBuilder.class, RETURNS_SELF);
                var counter = counter(invocation.getArgument(0));
                doReturn(counter).when(builder).build();
                return builder;
            });
            when(meter.histogramBuilder(anyString())).thenAnswer(invocation -> {
                var builder = mock(DoubleHistogramBuilder.class, RETURNS_SELF);
                doReturn(mock(DoubleHistogram.class)).when(builder).build();
                return builder;
            });
            when(meter.gaugeBuilder(anyString())).thenAnswer(invocation -> {
                var builder = mock(DoubleGaugeBuilder.class, RETURNS_SELF);
                doAnswer(build -> {
                    gauge.set(build.getArgument(0));
                    return null;
                }).when(builder).buildWithCallback(any());
                return builder;
            });
        }

        LongCounter counter(String name) {
            return counters.computeIfAbsent(name, key -> mock(LongCounter.class));
        }

        /** One scrape of the gauge: its value per workspace. */
        Map<String, Double> observeGauge() {
            var values = new HashMap<String, Double>();
            var measurement = mock(ObservableDoubleMeasurement.class);
            doAnswer(invocation -> {
                Attributes attributes = invocation.getArgument(1);
                values.put(attributes.get(stringKey("workspace_id")), invocation.getArgument(0));
                return null;
            }).when(measurement).record(anyDouble(), any(Attributes.class));
            gauge.get().accept(measurement);
            return values;
        }
    }
}
