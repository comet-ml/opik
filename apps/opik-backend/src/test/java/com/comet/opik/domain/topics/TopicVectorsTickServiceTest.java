package com.comet.opik.domain.topics;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.comet.opik.TestConfigUtils;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
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
import io.opentelemetry.api.common.AttributesBuilder;
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.opentelemetry.api.common.AttributeKey.longKey;
import static io.opentelemetry.api.common.AttributeKey.stringKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@DisplayName("topic vectors tick, without containers")
class TopicVectorsTickServiceTest {

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();
    private static final String WORKSPACE_ID = ID_GENERATOR.generateId().toString();
    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final Instant APPLIES_FROM = Instant.parse("2026-11-01T00:00:00Z");
    // config-test.yml's topics.vectors: the windows and the embed timeout. With the clock at the tick's start, a window
    // read gets find.maxExecutionTime (30 s, under the 50 s budget), and a later read the whole budget.
    private static final Duration SHORT_WINDOW = Duration.ofHours(24);
    private static final Duration LONG_WINDOW = Duration.ofDays(7);
    private static final Duration EMBED_TIMEOUT = Duration.ofSeconds(10);
    private static final long FIND_SECONDS = 30;
    private static final long BUDGET_SECONDS = 50;

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

        private final TopicVectorCandidate candidate = candidate(ID_GENERATOR.generateId(), 2, NOW);

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
            // Blank to opik-inference (Python's str.strip()), though not to String.isBlank().
            assertThat(
                    TopicVectorsTickService.summaryText(candidate, summary(candidate, 2, "\u00A0\u2007\u202F\u0085")))
                    .isEmpty();
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
        private TopicWorkspaceService workspaces;
        private AiLabelTaskRegistry registry;
        private TestMeter meter;
        private TestClock clock;
        private TopicVectorsTickService service;
        private final List<EmbedRequest> requests = Collections.synchronizedList(new ArrayList<>());
        private final List<TopicVector> inserted = Collections.synchronizedList(new ArrayList<>());
        private final List<List<TopicTraceKey>> statusChecks = Collections.synchronizedList(new ArrayList<>());

        @BeforeEach
        @SuppressWarnings("unchecked")
        void setUp() {
            config = TestConfigUtils.loadConfigTest().getTopics();
            dao = mock(TopicVectorsDAO.class);
            client = mock(InferenceClient.class);
            lockService = mock(LockService.class);
            workspaces = mock(TopicWorkspaceService.class);
            registry = mock(AiLabelTaskRegistry.class);
            meter = new TestMeter();
            clock = new TestClock(NOW);
            service = new TopicVectorsTickService(config, new AiLabelsConfig(), workspaces, dao, client, lockService,
                    registry, names(), meter.meter, clock);
            when(workspaces.eligibleWorkspaceIds()).thenReturn(List.of(WORKSPACE_ID));
            when(registry.find(TopicVectorsTickService.TASK)).thenReturn(java.util.Optional.of(task()));
            // The long-window pass is not due unless a test says so.
            when(lockService.lockUsingToken(TopicVectorsTickService.LONG_WINDOW_LOCK, service.leaderLease()))
                    .thenReturn(Mono.just(false));
            when(lockService.unlockUsingToken(TopicVectorsTickService.LONG_WINDOW_LOCK)).thenReturn(Mono.empty());
            // A chunk no test stubs gets the vectors of its own inputs, so this takes any request and deadline.
            when(client.embed(any(EmbedRequest.class), any(Instant.class))).thenAnswer(invocation -> {
                EmbedRequest request = invocation.getArgument(0);
                requests.add(request);
                return Mono.just(embedded(request.inputs()));
            });
            // Records the rows it gets, so this takes any list.
            when(dao.insert(anyList())).thenAnswer(invocation -> {
                List<TopicVector> rows = invocation.getArgument(0);
                inserted.addAll(rows);
                return Mono.just((long) rows.size());
            });
            // No trace is deleted while it is embedded unless a test says so: the status check answers every key it
            // is asked for, and records them, so this takes any workspace, keys and time left.
            when(dao.findStatusKeys(anyString(), eq(TopicVectorsTickService.TASK), anyCollection(), anyLong()))
                    .thenAnswer(invocation -> {
                        Collection<TopicTraceKey> keys = invocation.getArgument(2);
                        statusChecks.add(List.copyOf(keys));
                        return Flux.fromIterable(keys);
                    });
        }

        @Test
        @DisplayName("600 texts go out as 256 + 256 + 88 in order, and each vector is stored with its own trace")
        void chunksInOrder() {
            var candidates = IntStream.range(0, 600)
                    .mapToObj(i -> candidate(ID_GENERATOR.generateId(), 1, NOW.minusSeconds(600 - i)))
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
            var zero = candidate(ID_GENERATOR.generateId(), 1, NOW.minusSeconds(3));
            var missing = candidate(ID_GENERATOR.generateId(), 1, NOW.minusSeconds(2));
            var normal = candidate(ID_GENERATOR.generateId(), 1, NOW.minusSeconds(1));
            var candidates = List.of(zero, missing, normal);
            stub(candidates, candidate -> candidate == zero ? "zero" : "fine");
            when(dao.findSummaries(WORKSPACE_ID, TopicVectorsTickService.TASK, keys(candidates), BUDGET_SECONDS))
                    .thenReturn(Flux.just(summary(zero, 1, "zero"), summary(normal, 1, "fine")));

            var result = service.tick(NOW);

            assertThat(inserted).extracting(TopicVector::traceId).containsExactly(normal.traceId());
            assertThat(result.skipped()).isEqualTo(Map.of(
                    TopicVectorsTickService.INVALID_VECTOR, 1,
                    TopicVectorsTickService.MISSING_SUMMARY, 1));
        }

        @Test
        @DisplayName("a null embedding and a null element cost only their own traces, the rest of the chunk is written")
        void nullEmbeddingAndNullElement() {
            var nullEmbedding = candidate(ID_GENERATOR.generateId(), 1, NOW.minusSeconds(3));
            var nullElement = candidate(ID_GENERATOR.generateId(), 1, NOW.minusSeconds(2));
            var normal = candidate(ID_GENERATOR.generateId(), 1, NOW.minusSeconds(1));
            var candidates = List.of(nullEmbedding, nullElement, normal);
            stub(candidates, candidate -> "intent of " + candidate.traceId());
            var texts = candidates.stream().map(candidate -> "intent of " + candidate.traceId()).toList();
            var withNull = new ArrayList<>(vectorOf("x"));
            withNull.set(7, null);
            var embeddings = new ArrayList<List<Float>>();
            embeddings.add(null);
            embeddings.add(withNull);
            embeddings.add(vectorOf("intent of " + normal.traceId()));
            answerEmbed(embedRequest(WORKSPACE_ID, texts), EmbedResponse.builder()
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
                    .mapToObj(i -> candidate(ID_GENERATOR.generateId(), 1, NOW.minusSeconds(4 - i)))
                    .toList();
            stub(candidates, candidate -> "intent of " + candidate.traceId());
            var texts = candidates.stream().map(candidate -> "intent of " + candidate.traceId()).toList();
            var first = embedRequest(WORKSPACE_ID, texts.subList(0, 2));
            var second = embedRequest(WORKSPACE_ID, texts.subList(2, 4));
            answerEmbed(first, EmbedResponse.builder()
                    .result(EmbedResult.builder().embeddings(null).dimensions(384).build())
                    .build());

            var result = service.tick(NOW);

            verify(client).embed(first, NOW.plus(EMBED_TIMEOUT));
            verify(client).embed(second, NOW.plus(EMBED_TIMEOUT));
            verifyNoMoreInteractions(client);
            assertThat(inserted).extracting(TopicVector::traceId)
                    .containsExactly(candidates.get(2).traceId(), candidates.get(3).traceId());
            assertThat(result.skipped()).isEqualTo(Map.of(TopicVectorsTickService.INVALID_VECTOR, 2));
        }

        @Test
        @DisplayName("an empty answer or one without a result counts the chunk as invalid and throws nothing")
        void nullResult() {
            config.getVectors().setEmbedBatchSize(1);
            var candidates = List.of(candidate(ID_GENERATOR.generateId(), 1, NOW.minusSeconds(2)),
                    candidate(ID_GENERATOR.generateId(), 1, NOW.minusSeconds(1)));
            stub(candidates, c -> "intent");
            // Both chunks send the same request: the first answer is empty, the second has no result.
            doReturn(Mono.empty(), Mono.just(EmbedResponse.builder().build())).when(client)
                    .embed(embedRequest(WORKSPACE_ID, List.of("intent")), NOW.plus(EMBED_TIMEOUT));

            var result = service.tick(NOW);

            assertThat(inserted).isEmpty();
            assertThat(result.skipped()).isEqualTo(Map.of(TopicVectorsTickService.INVALID_VECTOR, 2));
        }

        private void answerEmbed(EmbedRequest request, EmbedResponse response) {
            doReturn(Mono.just(response)).when(client).embed(request, NOW.plus(EMBED_TIMEOUT));
        }

        @Test
        @DisplayName("the short and the long window never start before applies_from, and both bound trace_created_at "
                + "by the ai-labels horizon plus the slack")
        void windowBounds() {
            stub(List.of(), candidate -> "");
            config.getVectors().setLongWindow(io.dropwizard.util.Duration.days(30));
            stub(Duration.ofDays(30), List.of(), candidate -> "");
            var horizonAndSlack = Duration.ofDays(7).plus(Duration.ofDays(1));

            service.tick(NOW);
            var shortLower = NOW.minus(Duration.ofHours(24));
            verify(dao).findUnvectored(WORKSPACE_ID, TopicVectorsTickService.TASK, shortLower, NOW,
                    shortLower.minus(horizonAndSlack), 5_000, FIND_SECONDS);

            longWindowDue();
            var result = service.tick(NOW);
            assertThat(result.longWindowPass()).isTrue();
            verify(dao).findUnvectored(WORKSPACE_ID, TopicVectorsTickService.TASK, APPLIES_FROM, NOW,
                    APPLIES_FROM.minus(horizonAndSlack), 5_000, FIND_SECONDS);
        }

        @Test
        @DisplayName("with a backfill window, the long-window pass uses it instead of the long window")
        void backfillWindow() {
            config.getVectors().setBackfillWindow(io.dropwizard.util.Duration.days(12));
            stub(Duration.ofDays(12), List.of(), candidate -> "");
            longWindowDue();

            var result = service.tick(NOW);

            assertThat(result.longWindowPass()).isTrue();
            var lower = NOW.minus(Duration.ofDays(12));
            verify(dao).findUnvectored(WORKSPACE_ID, TopicVectorsTickService.TASK, lower, NOW,
                    lower.minus(Duration.ofDays(7).plus(Duration.ofDays(1))), 5_000, FIND_SECONDS);
            assertThat(TopicVectorsTickService.longWindow(TestConfigUtils.loadConfigTest().getTopics().getVectors()))
                    .isEqualTo(io.dropwizard.util.Duration.days(7));
        }

        @Test
        @DisplayName("the long-window pass stays due while its ticks reach the cap, and starts the interval once one "
                + "finds fewer")
        void longWindowUntilDrained() {
            config.getVectors().setMaxTracesPerTick(2);
            longWindowDue();
            var lease = service.leaderLease();
            var full = IntStream.range(0, 2)
                    .mapToObj(i -> candidate(ID_GENERATOR.generateId(), 1, NOW.minusSeconds(9 - i)))
                    .toList();
            stub(LONG_WINDOW, full, candidate -> "intent of " + candidate.traceId());

            assertThat(service.tick(NOW).written()).isEqualTo(2);

            verify(lockService).lockUsingToken(TopicVectorsTickService.LONG_WINDOW_LOCK, lease);
            verify(lockService).unlockUsingToken(TopicVectorsTickService.LONG_WINDOW_LOCK);
            // Not drained: due again after one normal tick (1.5 tick intervals), so the short window is served too.
            verify(lockService).lockUsingToken(TopicVectorsTickService.LONG_WINDOW_LOCK, Duration.ofSeconds(90));
            verify(lockService, never()).lockUsingToken(TopicVectorsTickService.LONG_WINDOW_LOCK,
                    Duration.ofDays(1));

            stub(LONG_WINDOW, List.of(candidate(ID_GENERATOR.generateId(), 1, NOW.minusSeconds(1))),
                    candidate -> "last");

            assertThat(service.tick(NOW).written()).isOne();

            var inOrder = inOrder(lockService);
            inOrder.verify(lockService).unlockUsingToken(TopicVectorsTickService.LONG_WINDOW_LOCK);
            inOrder.verify(lockService).lockUsingToken(TopicVectorsTickService.LONG_WINDOW_LOCK, Duration.ofDays(1));
        }

        @Test
        @DisplayName("a long-window pass cut by a back-off is not drained")
        void longWindowNotDrainedAfterBackOff() {
            longWindowDue();
            stub(LONG_WINDOW, List.of(candidate(ID_GENERATOR.generateId(), 1, NOW.minusSeconds(1))),
                    candidate -> "x");
            var exception = inferenceException(503, "something_new", false);
            doReturn(Mono.error(exception)).when(client)
                    .embed(embedRequest(WORKSPACE_ID, List.of("x")), NOW.plus(EMBED_TIMEOUT));

            var result = service.tick(NOW);

            assertThat(result.skipped()).isEqualTo(Map.of(TopicVectorsTickService.EMBED_FAILED, 1));
            verify(lockService).lockUsingToken(TopicVectorsTickService.LONG_WINDOW_LOCK, Duration.ofSeconds(90));
            verify(lockService, never()).lockUsingToken(TopicVectorsTickService.LONG_WINDOW_LOCK,
                    Duration.ofDays(1));
        }

        @Test
        @DisplayName("a summary that only the service counts as blank (no-break spaces, U+0085) is skipped as "
                + "missing_summary and never sent")
        void serviceBlankSummary() {
            var candidates = IntStream.range(0, 3)
                    .mapToObj(i -> candidate(ID_GENERATOR.generateId(), 1, NOW.minusSeconds(10 - i)))
                    .toList();
            var blank = candidates.get(1);
            stub(candidates,
                    candidate -> candidate == blank ? "\u00A0\u2007 \u202F\u0085" : "intent " + candidate.traceId());

            var result = service.tick(NOW);

            assertThat(requests).singleElement().satisfies(request -> assertThat(request.inputs())
                    .containsExactly("intent " + candidates.get(0).traceId(), "intent " + candidates.get(2).traceId()));
            assertThat(result.written()).isEqualTo(2);
            assertThat(result.skipped()).isEqualTo(Map.of(TopicVectorsTickService.MISSING_SUMMARY, 1));
        }

        @Test
        @DisplayName("a non-retryable 4xx halves the chunk until the rejected input is alone: it alone is embed_failed, "
                + "the rest are written")
        void rejectedInputCostsOnlyItself() {
            var candidates = IntStream.range(0, 5)
                    .mapToObj(i -> candidate(ID_GENERATOR.generateId(), 1, NOW.minusSeconds(10 - i)))
                    .toList();
            var bad = candidates.get(3);
            stub(candidates, candidate -> candidate == bad ? "rejected" : "intent " + candidate.traceId());
            when(client.embed(any(EmbedRequest.class), any(Instant.class))).thenAnswer(invocation -> {
                EmbedRequest request = invocation.getArgument(0);
                requests.add(request);
                return request.inputs().contains("rejected")
                        ? Mono.error(inferenceException(400, "invalid_request", false))
                        : Mono.just(embedded(request.inputs()));
            });

            var result = service.tick(NOW);

            // 5 -> 2 + 3 (rejected) -> 1 + 2 (rejected) -> 1 (rejected) + 1.
            assertThat(requests).extracting(request -> request.inputs().size()).containsExactly(5, 2, 3, 1, 2, 1, 1);
            assertThat(inserted).extracting(TopicVector::traceId)
                    .containsExactlyInAnyOrderElementsOf(candidates.stream().filter(candidate -> candidate != bad)
                            .map(TopicVectorCandidate::traceId).toList());
            assertThat(result.written()).isEqualTo(4);
            assertThat(result.skipped()).isEqualTo(Map.of(TopicVectorsTickService.EMBED_FAILED, 1));
        }

        @Test
        @DisplayName("a 4xx that is retryable, or a 429, is not halved: the workspace backs off")
        void retryableOrThrottledIsNotHalved() {
            assertThat(TopicVectorsTickService.isRejectedInput(inferenceException(400, "invalid_request", false)))
                    .isTrue();
            assertThat(TopicVectorsTickService.isRejectedInput(inferenceException(413, "input_too_long", false)))
                    .isTrue();
            assertThat(TopicVectorsTickService.isRejectedInput(inferenceException(429, "quota_exceeded", true)))
                    .isFalse();
            assertThat(TopicVectorsTickService.isRejectedInput(inferenceException(409, "something_new", true)))
                    .isFalse();
            assertThat(TopicVectorsTickService.isRejectedInput(inferenceException(503, "overloaded", true)))
                    .isFalse();
        }

        @Test
        @DisplayName("an interrupt during a window read stops the tick: the next workspace is not read")
        void interruptStopsTheTick() {
            when(workspaces.eligibleWorkspaceIds()).thenReturn(List.of("first", "second"));
            when(findUnvectored(dao, "first", NOW, SHORT_WINDOW, 5_000, FIND_SECONDS))
                    .thenReturn(Flux.error(reactor.core.Exceptions.propagate(new InterruptedException())));

            try {
                assertThatThrownBy(() -> service.tick(NOW))
                        .satisfies(error -> assertThat(reactor.core.Exceptions.unwrap(error))
                                .isInstanceOf(InterruptedException.class));
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
            findUnvectored(verify(dao, never()), "second", NOW, SHORT_WINDOW, 5_000, FIND_SECONDS);
        }

        @Test
        @DisplayName("a failed insert is skipped as insert_failed, keeps the long-window pass on, and the next "
                + "workspace still runs")
        void insertFailure() {
            longWindowDue();
            when(workspaces.eligibleWorkspaceIds()).thenReturn(List.of("failing", "healthy"));
            var failing = candidate("failing", NOW.minusSeconds(10));
            var healthy = candidate("healthy", NOW.minusSeconds(10));
            when(findUnvectored(dao, "failing", NOW, LONG_WINDOW, 5_000, FIND_SECONDS)).thenReturn(Flux.just(failing));
            when(findUnvectored(dao, "healthy", NOW, LONG_WINDOW, 5_000, FIND_SECONDS)).thenReturn(Flux.just(healthy));
            stubSummaries("failing", List.of(failing), candidate -> "failing text", BUDGET_SECONDS);
            stubSummaries("healthy", List.of(healthy), candidate -> "healthy text", BUDGET_SECONDS);
            when(dao.insert(anyList())).thenAnswer(invocation -> {
                List<TopicVector> rows = invocation.getArgument(0);
                if (rows.getFirst().workspaceId().equals("failing")) {
                    return Mono.error(new IllegalStateException("insert down"));
                }
                inserted.addAll(rows);
                return Mono.just((long) rows.size());
            });

            var result = service.tick(NOW);

            assertThat(inserted).extracting(TopicVector::traceId).containsExactly(healthy.traceId());
            assertThat(result.written()).isEqualTo(1);
            assertThat(result.skipped()).isEqualTo(Map.of(TopicVectorsTickService.INSERT_FAILED, 1));
            verify(lockService).lockUsingToken(TopicVectorsTickService.LONG_WINDOW_LOCK, Duration.ofSeconds(90));
            verify(lockService, never()).lockUsingToken(TopicVectorsTickService.LONG_WINDOW_LOCK,
                    Duration.ofDays(1));
        }

        @Test
        @DisplayName("embed error codes become metric attributes from the known set only; anything else is unknown")
        void metricCode() {
            assertThat(TopicVectorsTickService.metricCode("quota_exceeded")).isEqualTo("quota_exceeded");
            assertThat(TopicVectorsTickService.metricCode("out_of_time")).isEqualTo("out_of_time");
            assertThat(TopicVectorsTickService.metricCode("client_busy")).isEqualTo("client_busy");
            assertThat(TopicVectorsTickService.metricCode("something_new")).isEqualTo("unknown");
            assertThat(TopicVectorsTickService.metricCode(null)).isEqualTo("unknown");
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
            when(workspaces.eligibleWorkspaceIds()).thenReturn(List.of("a", "b", "c"));
            var order = new ArrayList<List<String>>();
            for (int minute = 0; minute < 4; minute++) {
                var start = clock.instant();
                var calls = new ArrayList<String>();
                for (var workspaceId : List.of("a", "b", "c")) {
                    when(findUnvectored(dao, workspaceId, start, SHORT_WINDOW, 5_000, FIND_SECONDS))
                            .thenAnswer(invocation -> {
                                calls.add(workspaceId);
                                return Flux.empty();
                            });
                }
                service.tick(start);
                order.add(calls);
                clock.advance(Duration.ofMinutes(1));
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
            when(workspaces.eligibleWorkspaceIds()).thenReturn(List.of("failing", "healthy"));
            var failing = IntStream.range(0, 3).mapToObj(i -> candidate("failing", NOW.minusSeconds(10 - i)))
                    .toList();
            var healthy = IntStream.range(0, 3).mapToObj(i -> candidate("healthy", NOW.minusSeconds(10 - i)))
                    .toList();
            Function<TopicVectorCandidate, String> text = candidate -> "intent of " + candidate.traceId();
            when(findUnvectored(dao, "failing", NOW, SHORT_WINDOW, 3, FIND_SECONDS))
                    .thenReturn(Flux.fromIterable(failing));
            when(findUnvectored(dao, "healthy", NOW, SHORT_WINDOW, 3, FIND_SECONDS))
                    .thenReturn(Flux.fromIterable(healthy));
            stubSummaries("failing", failing, text, BUDGET_SECONDS);
            stubSummaries("healthy", healthy, text, BUDGET_SECONDS);
            var exception = inferenceException(503, "something_new", false);
            var failed = embedRequest("failing", List.of(text.apply(failing.getFirst())));
            doReturn(Mono.error(exception)).when(client).embed(failed, NOW.plus(EMBED_TIMEOUT));

            var result = service.tick(NOW);

            findUnvectored(verify(dao), "failing", NOW, SHORT_WINDOW, 3, FIND_SECONDS);
            findUnvectored(verify(dao), "healthy", NOW, SHORT_WINDOW, 3, FIND_SECONDS);
            // The failing workspace is called once and backs off; the healthy one goes on.
            verify(client).embed(failed, NOW.plus(EMBED_TIMEOUT));
            healthy.forEach(candidate -> verify(client)
                    .embed(embedRequest("healthy", List.of(text.apply(candidate))), NOW.plus(EMBED_TIMEOUT)));
            verifyNoMoreInteractions(client);
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
            // The lease follows the budget: the long-window pass is not due under the new one either.
            when(lockService.lockUsingToken(TopicVectorsTickService.LONG_WINDOW_LOCK, service.leaderLease()))
                    .thenReturn(Mono.just(false));
            var candidates = IntStream.range(0, 3)
                    .mapToObj(i -> candidate(ID_GENERATOR.generateId(), 1, NOW.minusSeconds(5)))
                    .toList();
            // With 1.5 s left, each read gets the 1 whole second left.
            when(findUnvectored(dao, WORKSPACE_ID, NOW, SHORT_WINDOW, 5_000, 1))
                    .thenReturn(Flux.fromIterable(candidates));
            stubSummaries(WORKSPACE_ID, candidates, candidate -> "intent of " + candidate.traceId(), 1);
            var first = embedRequest(WORKSPACE_ID, List.of("intent of " + candidates.getFirst().traceId()));
            var callDeadline = NOW.plusSeconds(1);
            doAnswer(invocation -> {
                // Leaves less than the embed timeout of the 1.5 s budget.
                clock.advance(Duration.ofMillis(600));
                return Mono.just(embedded(first.inputs()));
            }).when(client).embed(first, callDeadline);

            var result = service.tick(NOW);

            // A call that starts gets its whole timeout: its deadline is exactly the embed timeout from its start.
            verify(client).embed(first, callDeadline);
            verifyNoMoreInteractions(client);
            assertThat(result.written()).isOne();
            assertThat(result.skipped()).isEqualTo(Map.of(TopicVectorsTickService.OVER_BUDGET, 2));
        }

        @ParameterizedTest(name = "{0} ms left: {1} window reads")
        // A read needs at least 1 s left, and gets the whole seconds left: 1,999 ms is a read of 1 s.
        @CsvSource({"0, 0", "999, 0", "1000, 1", "1999, 1"})
        @DisplayName("a window read starts only with at least a second left in the tick, and gets the whole seconds left")
        void noWindowReadUnderASecondLeft(long leftMillis, int reads) {
            var start = NOW.minus(config.getVectors().getTickBudget().toJavaDuration()).plusMillis(leftMillis);
            when(findUnvectored(dao, WORKSPACE_ID, start, SHORT_WINDOW, 5_000, 1)).thenReturn(Flux.empty());
            var appender = new ListAppender<ILoggingEvent>();
            var logger = (Logger) LoggerFactory.getLogger(TopicVectorsTickService.class);
            appender.start();
            logger.addAppender(appender);
            try {
                service.tick(start);

                findUnvectored(verify(dao, times(reads)), WORKSPACE_ID, start, SHORT_WINDOW, 5_000, 1);
                verifyNoMoreInteractions(dao);
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
            var oldest = candidate(ID_GENERATOR.generateId(), 1, NOW.minusSeconds(30));
            var newer = candidate(ID_GENERATOR.generateId(), 1, NOW.minusSeconds(20));
            var normal = candidate(ID_GENERATOR.generateId(), 1, NOW.minusSeconds(10));
            stub(List.of(oldest, newer, normal), candidate -> candidate == normal ? "fine" : "   ");
            var appender = new ListAppender<ILoggingEvent>();
            var logger = (Logger) LoggerFactory.getLogger(TopicVectorsTickService.class);
            appender.start();
            logger.addAppender(appender);
            try {
                var result = service.tick(NOW);

                assertThat(result.skipped()).isEqualTo(Map.of(TopicVectorsTickService.MISSING_SUMMARY, 2));
                assertThat(appender.list)
                        .filteredOn(event -> event.getFormattedMessage().contains("without a summary"))
                        .singleElement()
                        .satisfies(event -> {
                            assertThat(event.getLevel()).isEqualTo(Level.WARN);
                            assertThat(event.getFormattedMessage())
                                    .contains("workspace '" + WORKSPACE_ID + "'")
                                    .contains("traces '2'")
                                    .contains("oldest '" + oldest.traceId() + "'");
                        });
            } finally {
                logger.detachAppender(appender);
            }
        }

        @Test
        @DisplayName("a failed window read is counted, and the workspace has no gauge value for that tick")
        void findFailure() {
            when(workspaces.eligibleWorkspaceIds()).thenReturn(List.of("broken", "fine"));
            var fine = candidate("fine", NOW.minusSeconds(60));
            when(findUnvectored(dao, "broken", NOW, SHORT_WINDOW, 5_000, FIND_SECONDS))
                    .thenReturn(Flux.error(new IllegalStateException("too many rows")));
            when(findUnvectored(dao, "fine", NOW, SHORT_WINDOW, 5_000, FIND_SECONDS)).thenReturn(Flux.just(fine));
            stubSummaries("fine", List.of(fine), candidate -> "fine", BUDGET_SECONDS);

            service.tick(NOW);

            verify(meter.counter("topics.vectors.read.failures"))
                    .add(1, workspace("broken").put(stringKey("phase"), "find")
                            .put(stringKey("error_type"), "IllegalStateException").build());
            assertThat(meter.observeGauge()).containsOnlyKeys("fine");
        }

        @Test
        @DisplayName("a failed summary read is skipped as read_failed and counted")
        void summaryReadFailure() {
            var candidates = List.of(candidate(ID_GENERATOR.generateId(), 1, NOW.minusSeconds(1)));
            stub(candidates, candidate -> "x");
            when(dao.findSummaries(WORKSPACE_ID, TopicVectorsTickService.TASK, keys(candidates), BUDGET_SECONDS))
                    .thenReturn(Flux.error(new IllegalStateException("down")));

            var result = service.tick(NOW);

            assertThat(result.skipped()).isEqualTo(Map.of(TopicVectorsTickService.READ_FAILED, 1));
            verify(meter.counter("topics.vectors.read.failures"))
                    .add(1, workspace(WORKSPACE_ID).put(stringKey("phase"), "summaries")
                            .put(stringKey("error_type"), "IllegalStateException").build());
            verify(meter.counter("topics.vectors.skipped"))
                    .add(1, workspace(WORKSPACE_ID).put(stringKey("reason"), TopicVectorsTickService.READ_FAILED)
                            .build());
        }

        @Test
        @DisplayName("a trace deleted between the summary read and the insert has its vector deleted, and is skipped "
                + "as trace_deleted, not written")
        void traceDeletedWhileEmbedding() {
            var kept = candidate(ID_GENERATOR.generateId(), 1, NOW.minusSeconds(2));
            var deleted = candidate(ID_GENERATOR.generateId(), 1, NOW.minusSeconds(1));
            stub(List.of(kept, deleted), candidate -> "intent of " + candidate.traceId());
            var texts = List.of("intent of " + kept.traceId(), "intent of " + deleted.traceId());
            var cascaded = ConcurrentHashMap.<TopicTraceKey>newKeySet();
            doAnswer(invocation -> {
                // The trace-delete cascade runs while the chunk is embedded, and deletes the status rows first.
                cascaded.add(key(deleted));
                return Mono.just(embedded(texts));
            }).when(client).embed(embedRequest(WORKSPACE_ID, texts), NOW.plus(EMBED_TIMEOUT));
            var written = List.of(key(kept), key(deleted));
            doAnswer(invocation -> Flux.fromIterable(written).filter(traceKey -> !cascaded.contains(traceKey)))
                    .when(dao).findStatusKeys(WORKSPACE_ID, TopicVectorsTickService.TASK, written, BUDGET_SECONDS);
            when(dao.deleteByKeys(WORKSPACE_ID, Set.of(key(deleted)))).thenReturn(Mono.empty());

            var result = service.tick(NOW);

            assertThat(inserted).extracting(TopicVector::traceId).containsExactly(kept.traceId(), deleted.traceId());
            verify(dao).deleteByKeys(WORKSPACE_ID, Set.of(key(deleted)));
            assertThat(result.written()).isOne();
            assertThat(result.skipped()).isEqualTo(Map.of(TopicVectorsTickService.TRACE_DELETED, 1));
            var writtenCounter = meter.counter("topics.vectors.written");
            verify(writtenCounter).add(1, workspace(WORKSPACE_ID).build());
            verifyNoMoreInteractions(writtenCounter);
            var skippedCounter = meter.counter("topics.vectors.skipped");
            verify(skippedCounter)
                    .add(1, workspace(WORKSPACE_ID).put(stringKey("reason"), TopicVectorsTickService.TRACE_DELETED)
                            .build());
            verifyNoMoreInteractions(skippedCounter);
        }

        @Test
        @DisplayName("a failed deleted-trace check keeps the vectors as written and deletes nothing")
        void deletedTraceCheckFailure() {
            var candidate = candidate(ID_GENERATOR.generateId(), 1, NOW.minusSeconds(1));
            stub(List.of(candidate), c -> "intent");
            when(dao.findStatusKeys(WORKSPACE_ID, TopicVectorsTickService.TASK, List.of(key(candidate)),
                    BUDGET_SECONDS)).thenReturn(Flux.error(new IllegalStateException("down")));

            var result = service.tick(NOW);

            assertThat(inserted).extracting(TopicVector::traceId).containsExactly(candidate.traceId());
            verify(dao).findStatusKeys(WORKSPACE_ID, TopicVectorsTickService.TASK, List.of(key(candidate)),
                    BUDGET_SECONDS);
            verify(dao, never()).deleteByKeys(WORKSPACE_ID, Set.of(key(candidate)));
            assertThat(result.written()).isOne();
            assertThat(result.skipped()).isEmpty();
            verify(meter.counter("topics.vectors.written")).add(1, workspace(WORKSPACE_ID).build());
            verifyNoInteractions(meter.counter("topics.vectors.skipped"));
        }

        @Test
        @DisplayName("the deleted-trace check runs once per workspace, after its last chunk, reading 1,000 keys at a "
                + "time")
        void oneDeletedTraceCheckPerWorkspace() {
            var candidates = IntStream.range(0, 1_001)
                    .mapToObj(i -> candidate(ID_GENERATOR.generateId(), 1, NOW.minusSeconds(1_001 - i)))
                    .toList();
            when(findUnvectored(dao, WORKSPACE_ID, NOW, SHORT_WINDOW, config.getVectors().getMaxTracesPerTick(),
                    FIND_SECONDS)).thenReturn(Flux.fromIterable(candidates));
            stubSummaries(WORKSPACE_ID, candidates.subList(0, 1_000), candidate -> "intent of " + candidate.traceId(),
                    BUDGET_SECONDS);
            stubSummaries(WORKSPACE_ID, candidates.subList(1_000, 1_001),
                    candidate -> "intent of " + candidate.traceId(), BUDGET_SECONDS);

            var result = service.tick(NOW);

            // Four embed chunks (256 + 256 + 256 + 233), one check of their 1,001 keys in two reads.
            assertThat(requests).extracting(request -> request.inputs().size()).containsExactly(256, 256, 256, 233);
            assertThat(statusChecks).containsExactly(keys(candidates.subList(0, 1_000)),
                    keys(candidates.subList(1_000, 1_001)));
            assertThat(result.written()).isEqualTo(1_001);
            var writtenCounter = meter.counter("topics.vectors.written");
            verify(writtenCounter).add(1_001, workspace(WORKSPACE_ID).build());
            verifyNoMoreInteractions(writtenCounter);
        }

        @Test
        @DisplayName("a failed read of a later chunk of the deleted-trace check keeps the deletes of the chunk before "
                + "it, and keeps only its own vectors as written")
        void laterDeletedTraceCheckChunkFailure() {
            var candidates = IntStream.range(0, 1_001)
                    .mapToObj(i -> candidate(ID_GENERATOR.generateId(), 1, NOW.minusSeconds(1_001 - i)))
                    .toList();
            var deleted = candidates.get(500);
            when(findUnvectored(dao, WORKSPACE_ID, NOW, SHORT_WINDOW, config.getVectors().getMaxTracesPerTick(),
                    FIND_SECONDS)).thenReturn(Flux.fromIterable(candidates));
            stubSummaries(WORKSPACE_ID, candidates.subList(0, 1_000), candidate -> "intent of " + candidate.traceId(),
                    BUDGET_SECONDS);
            stubSummaries(WORKSPACE_ID, candidates.subList(1_000, 1_001),
                    candidate -> "intent of " + candidate.traceId(), BUDGET_SECONDS);
            var first = keys(candidates.subList(0, 1_000));
            var last = keys(candidates.subList(1_000, 1_001));
            when(dao.findStatusKeys(WORKSPACE_ID, TopicVectorsTickService.TASK, first, BUDGET_SECONDS))
                    .thenReturn(Flux.fromIterable(first).filter(traceKey -> !traceKey.equals(key(deleted))));
            when(dao.findStatusKeys(WORKSPACE_ID, TopicVectorsTickService.TASK, last, BUDGET_SECONDS))
                    .thenReturn(Flux.error(new IllegalStateException("down")));
            when(dao.deleteByKeys(WORKSPACE_ID, Set.of(key(deleted)))).thenReturn(Mono.empty());

            var result = service.tick(NOW);

            assertThat(inserted).hasSize(1_001);
            verify(dao).findStatusKeys(WORKSPACE_ID, TopicVectorsTickService.TASK, first, BUDGET_SECONDS);
            verify(dao).findStatusKeys(WORKSPACE_ID, TopicVectorsTickService.TASK, last, BUDGET_SECONDS);
            verify(dao).deleteByKeys(WORKSPACE_ID, Set.of(key(deleted)));
            verify(dao, never()).deleteByKeys(WORKSPACE_ID, Set.copyOf(last));
            assertThat(result.written()).isEqualTo(1_000);
            assertThat(result.skipped()).isEqualTo(Map.of(TopicVectorsTickService.TRACE_DELETED, 1));
            var writtenCounter = meter.counter("topics.vectors.written");
            verify(writtenCounter).add(1_000, workspace(WORKSPACE_ID).build());
            verifyNoMoreInteractions(writtenCounter);
            var skippedCounter = meter.counter("topics.vectors.skipped");
            verify(skippedCounter)
                    .add(1, workspace(WORKSPACE_ID).put(stringKey("reason"), TopicVectorsTickService.TRACE_DELETED)
                            .build());
            verifyNoMoreInteractions(skippedCounter);
        }

        @Test
        @DisplayName("a trace id reused in another project counts as missed only for the project whose row the short "
                + "window missed")
        void missedKeyedByProject() {
            longWindowDue();
            var traceId = ID_GENERATOR.generateId();
            var old = candidate(traceId, 1, NOW.minus(Duration.ofDays(2)));
            var recent = candidate(traceId, 1, NOW.minusSeconds(5));
            stub(LONG_WINDOW, List.of(old, recent), candidate -> "intent of " + candidate.projectId());

            var result = service.tick(NOW);

            assertThat(result.written()).isEqualTo(2);
            var missed = meter.counter("topics.vectors.long_window.missed");
            verify(missed).add(1L, workspace(WORKSPACE_ID).build());
            verifyNoMoreInteractions(missed);
        }

        @Test
        @DisplayName("the gauge is published only while this pod's last tick is at most two tick intervals old")
        void gaugeGoesStale() {
            stub(List.of(), candidate -> "");

            assertThat(meter.observeGauge()).isEmpty();
            service.tick(NOW);
            assertThat(meter.observeGauge()).containsOnlyKeys(WORKSPACE_ID);

            clock.advance(Duration.ofMinutes(2));
            assertThat(meter.observeGauge()).containsOnlyKeys(WORKSPACE_ID);
            clock.advance(Duration.ofMillis(1));
            assertThat(meter.observeGauge()).isEmpty();
        }

        @Test
        @DisplayName("an embed failure is counted by status and code, and a long-window pass counts only the old rows "
                + "it wrote as missed")
        void metrics() {
            config.getVectors().setEmbedBatchSize(1);
            longWindowDue();
            var old = candidate(ID_GENERATOR.generateId(), 1, NOW.minus(Duration.ofDays(2)));
            var recent = candidate(ID_GENERATOR.generateId(), 1, NOW.minusSeconds(5));
            var oldFailing = candidate(ID_GENERATOR.generateId(), 1, NOW.minus(Duration.ofDays(3)));
            stub(LONG_WINDOW, List.of(oldFailing, old, recent),
                    candidate -> candidate == oldFailing ? "bad" : "fine " + candidate);
            var exception = inferenceException(400, "invalid_request", false);
            doReturn(Mono.error(exception)).when(client)
                    .embed(embedRequest(WORKSPACE_ID, List.of("bad")), NOW.plus(EMBED_TIMEOUT));

            var result = service.tick(NOW);

            assertThat(result.written()).isEqualTo(2);
            verify(meter.counter("topics.vectors.embed.failures"))
                    .add(1, workspace(WORKSPACE_ID).put(longKey("http_status"), 400L)
                            .put(stringKey("code"), "invalid_request")
                            .put(stringKey("error_type"), "InferenceException").build());
            var missed = meter.counter("topics.vectors.long_window.missed");
            verify(missed).add(1L, workspace(WORKSPACE_ID).build());
            verifyNoMoreInteractions(missed);
        }

        @Test
        @DisplayName("every per-workspace metric carries workspace_id and workspace_name, and the gauge takes the name "
                + "the tick resolved, never one looked up at scrape time")
        void workspaceAttributes() {
            var lookups = new java.util.concurrent.atomic.AtomicInteger();
            service = new TopicVectorsTickService(config, new AiLabelsConfig(), workspaces, dao, client, lockService,
                    registry, new TopicsWorkspaceNames(id -> {
                        lookups.incrementAndGet();
                        return "name-" + id;
                    }, clock), meter.meter, clock);
            stub(List.of(candidate(ID_GENERATOR.generateId(), 1, NOW.minusSeconds(5))), candidate -> "intent");

            assertThat(service.tick(NOW).written()).isOne();

            var workspace = workspace(WORKSPACE_ID).build();
            verify(meter.counter("topics.vectors.written")).add(1, workspace);
            // The call's wall time is not the test's to know.
            verify(meter.histogram("topics.vectors.embed.duration_seconds"))
                    .record(anyDouble(), eq(workspace(WORKSPACE_ID).put(stringKey("outcome"), "ok").build()));
            assertThat(meter.observeGaugeAttributes()).containsExactly(Map.entry(WORKSPACE_ID, workspace));
            meter.observeGaugeAttributes();
            assertThat(lookups).hasValue(1);
        }

        @Test
        @DisplayName("a failed name lookup costs only the name: the workspace is labelled with its id, and the tick "
                + "writes as usual")
        void nameFallback() {
            service = new TopicVectorsTickService(config, new AiLabelsConfig(), workspaces, dao, client, lockService,
                    registry, new TopicsWorkspaceNames(id -> {
                        throw new IllegalStateException("react service down");
                    }, clock), meter.meter, clock);
            stub(List.of(candidate(ID_GENERATOR.generateId(), 1, NOW.minusSeconds(5))), candidate -> "intent");

            assertThat(service.tick(NOW).written()).isOne();

            var byId = TopicsWorkspaceNames.builder(WORKSPACE_ID, WORKSPACE_ID).build();
            verify(meter.counter("topics.vectors.written")).add(1, byId);
            assertThat(meter.observeGaugeAttributes()).containsExactly(Map.entry(WORKSPACE_ID, byId));
        }

        @Test
        @DisplayName("workspaces the budget cuts off are counted once each in workspaces_not_read, with the name this "
                + "pod knows, after at most one lookup; they have no gauge value")
        void workspacesNotRead() {
            var looked = Collections.synchronizedList(new ArrayList<String>());
            var names = new TopicsWorkspaceNames(id -> {
                looked.add(id);
                return "name-" + id;
            }, clock);
            names.name("b");
            looked.clear();
            service = new TopicVectorsTickService(config, new AiLabelsConfig(), workspaces, dao, client, lockService,
                    registry, names, meter.meter, clock);
            when(workspaces.eligibleWorkspaceIds()).thenReturn(List.of("a", "b", "c"));
            var budget = config.getVectors().getTickBudget().toJavaDuration();

            service.tick(NOW.minus(budget));

            verifyNoInteractions(dao);
            // Only the first workspace in visiting order is looked up, before the time check stops the reads.
            assertThat(looked).hasSizeLessThanOrEqualTo(1);
            var notRead = meter.counter("topics.vectors.workspaces_not_read");
            for (var id : List.of("a", "b", "c")) {
                var name = id.equals("b") || looked.contains(id) ? "name-" + id : id;
                verify(notRead).add(1, TopicsWorkspaceNames.builder(id, name).build());
            }
            verifyNoMoreInteractions(notRead);
            assertThat(meter.observeGauge()).isEmpty();
        }

        @Test
        @DisplayName("without the trace_summary task the tick throws, so the job counts it as failed, not as ran")
        void missingTask() {
            when(registry.find(TopicVectorsTickService.TASK)).thenReturn(java.util.Optional.empty());

            assertThatThrownBy(() -> service.tick(NOW)).isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(TopicVectorsTickService.TASK);
            verifyNoInteractions(dao);
        }

        private void longWindowDue() {
            when(lockService.lockUsingToken(TopicVectorsTickService.LONG_WINDOW_LOCK, service.leaderLease()))
                    .thenReturn(Mono.just(true));
            // The pass ends by setting the lock again: for the interval when drained, else for 1.5 tick intervals.
            when(lockService.lockUsingToken(TopicVectorsTickService.LONG_WINDOW_LOCK, Duration.ofDays(1)))
                    .thenReturn(Mono.just(true));
            when(lockService.lockUsingToken(TopicVectorsTickService.LONG_WINDOW_LOCK, Duration.ofSeconds(90)))
                    .thenReturn(Mono.just(true));
        }

        @Test
        @DisplayName("before the task applies, nothing is read")
        void beforeAppliesFrom() {
            var result = service.tick(APPLIES_FROM.minusSeconds(1));

            assertThat(result.written()).isZero();
            verifyNoInteractions(dao);
        }

        /** Stubs a short-window tick at {@code NOW}. */
        private void stub(List<TopicVectorCandidate> candidates, Function<TopicVectorCandidate, String> text) {
            stub(SHORT_WINDOW, candidates, text);
        }

        /**
         * Stubs a tick at {@code NOW} with {@code window}, the clock at {@code NOW}: the window read answers
         * {@code candidates}, and the summary read of their keys answers their {@code text}.
         */
        private void stub(Duration window, List<TopicVectorCandidate> candidates,
                Function<TopicVectorCandidate, String> text) {
            when(findUnvectored(dao, WORKSPACE_ID, NOW, window, config.getVectors().getMaxTracesPerTick(),
                    FIND_SECONDS)).thenReturn(Flux.fromIterable(candidates));
            stubSummaries(WORKSPACE_ID, candidates, text, BUDGET_SECONDS);
        }

        /** Stubs one summary read of {@code candidates}' keys, in find order, answering their {@code text}. */
        private void stubSummaries(String workspaceId, List<TopicVectorCandidate> candidates,
                Function<TopicVectorCandidate, String> text, long maxExecutionSeconds) {
            when(dao.findSummaries(workspaceId, TopicVectorsTickService.TASK, keys(candidates), maxExecutionSeconds))
                    .thenReturn(Flux.fromIterable(candidates)
                            .map(candidate -> summary(candidate, candidate.taskVersion(), text.apply(candidate))));
        }

        /**
         * The window read a tick at {@code start} makes for {@code workspaceId}, with the bounds the tick derives from
         * {@code window}, the task's first applies_from, the 7 d ai-labels horizon and the 1 d slack. Called on
         * {@code dao} to stub it, on {@code verify(dao)} to verify it.
         */
        private Flux<TopicVectorCandidate> findUnvectored(TopicVectorsDAO on, String workspaceId, Instant start,
                Duration window, int limit, long maxExecutionSeconds) {
            var lowerBound = TopicVectorsTickService.lowerBound(APPLIES_FROM, start, window);
            var createdAfter = TopicVectorsTickService.createdAfter(lowerBound, Duration.ofDays(7),
                    Duration.ofDays(1));
            return on.findUnvectored(workspaceId, TopicVectorsTickService.TASK, lowerBound, start, createdAfter,
                    limit, maxExecutionSeconds);
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
                .projectId(ID_GENERATOR.generateId())
                .traceId(traceId)
                .traceCreatedAt(statusUpdatedAt.minusSeconds(60))
                .taskVersion(taskVersion)
                .statusUpdatedAt(statusUpdatedAt)
                .build();
    }

    private static TopicSummary summary(TopicVectorCandidate candidate, int taskVersion, String text) {
        return TopicSummary.builder().projectId(candidate.projectId()).traceId(candidate.traceId())
                .taskVersion(taskVersion).summary(text).build();
    }

    private static TopicVectorCandidate candidate(String workspaceId, Instant statusUpdatedAt) {
        return candidate(ID_GENERATOR.generateId(), 1, statusUpdatedAt).toBuilder().workspaceId(workspaceId).build();
    }

    private static TopicTraceKey key(TopicVectorCandidate candidate) {
        return TopicTraceKey.builder().projectId(candidate.projectId()).traceId(candidate.traceId()).build();
    }

    /** The keys of {@code candidates}, in their order: what one summary read gets. */
    private static List<TopicTraceKey> keys(List<TopicVectorCandidate> candidates) {
        return candidates.stream().map(TopicVectorsTickServiceTest::key).toList();
    }

    /** One chunk's embed request, as the tick builds it. */
    private static EmbedRequest embedRequest(String workspaceId, List<String> inputs) {
        return EmbedRequest.builder().orgId(workspaceId).cache(false).inputs(inputs).build();
    }

    /** The embed answer for {@code inputs}: {@link #vectorOf} of each, in order. */
    private static EmbedResponse embedded(List<String> inputs) {
        return EmbedResponse.builder()
                .result(EmbedResult.builder()
                        .embeddings(inputs.stream().map(TopicVectorsTickServiceTest::vectorOf).toList())
                        .dimensions(384)
                        .build())
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

    /** A clock that stands still until a test moves it, so every deadline, time left and age the tick uses is exact. */
    static final class TestClock extends Clock {

        private volatile Instant now;

        TestClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** A Meter whose instruments are mocks to verify, by name, and whose gauge callback can be observed. */
    static final class TestMeter {

        final Meter meter = mock(Meter.class);
        private final Map<String, LongCounter> counters = new ConcurrentHashMap<>();
        private final Map<String, DoubleHistogram> histograms = new ConcurrentHashMap<>();
        private final AtomicReference<Consumer<ObservableDoubleMeasurement>> gauge = new AtomicReference<>();

        @SuppressWarnings("unchecked")
        TestMeter() {
            // Each builder answers the instrument kept under the name it is given, so these take any name.
            when(meter.counterBuilder(anyString())).thenAnswer(invocation -> {
                var builder = mock(LongCounterBuilder.class, RETURNS_SELF);
                var counter = counter(invocation.getArgument(0));
                doReturn(counter).when(builder).build();
                return builder;
            });
            when(meter.histogramBuilder(anyString())).thenAnswer(invocation -> {
                var builder = mock(DoubleHistogramBuilder.class, RETURNS_SELF);
                var histogram = histogram(invocation.getArgument(0));
                doReturn(histogram).when(builder).build();
                return builder;
            });
            when(meter.gaugeBuilder(anyString())).thenAnswer(invocation -> {
                var builder = mock(DoubleGaugeBuilder.class, RETURNS_SELF);
                // Keeps the callback the service registers, whichever it is, to observe it.
                doAnswer(build -> {
                    gauge.set(build.getArgument(0));
                    return null;
                }).when(builder).buildWithCallback(any(Consumer.class));
                return builder;
            });
        }

        LongCounter counter(String name) {
            return counters.computeIfAbsent(name, key -> mock(LongCounter.class));
        }

        DoubleHistogram histogram(String name) {
            return histograms.computeIfAbsent(name, key -> mock(DoubleHistogram.class));
        }

        /** One scrape of the gauge: its value per workspace. */
        Map<String, Double> observeGauge() {
            var values = new HashMap<String, Double>();
            observe((value, attributes) -> values.put(attributes.get(stringKey("workspace_id")), value));
            return values;
        }

        /** One scrape of the gauge: the attributes of its value per workspace. */
        Map<String, Attributes> observeGaugeAttributes() {
            var values = new HashMap<String, Attributes>();
            observe((value, attributes) -> values.put(attributes.get(stringKey("workspace_id")), attributes));
            return values;
        }

        private void observe(java.util.function.BiConsumer<Double, Attributes> recorded) {
            var measurement = mock(ObservableDoubleMeasurement.class);
            // Hands on whatever the scrape publishes, so this takes any value and attributes.
            doAnswer(invocation -> {
                recorded.accept(invocation.getArgument(0), invocation.getArgument(1));
                return null;
            }).when(measurement).record(anyDouble(), any(Attributes.class));
            gauge.get().accept(measurement);
        }
    }

    /** Workspace names as the tests expect them: {@code name-} and the id, never failing. */
    static TopicsWorkspaceNames names() {
        return new TopicsWorkspaceNames(workspaceId -> "name-" + workspaceId, Clock.systemUTC());
    }

    /** Both workspace attributes, with the name {@link #names()} gives. */
    static AttributesBuilder workspace(String workspaceId) {
        return TopicsWorkspaceNames.builder(workspaceId, "name-" + workspaceId);
    }

    /** Every char Python's {@code str.isspace()} is true for, so {@code str.strip()} removes: opik-inference's rule. */
    private static final java.util.Set<Integer> PYTHON_SPACE = java.util.stream.Stream.of(
            IntStream.rangeClosed(0x09, 0x0D), IntStream.rangeClosed(0x1C, 0x20), IntStream.of(0x85, 0xA0, 0x1680),
            IntStream.rangeClosed(0x2000, 0x200A), IntStream.of(0x2028, 0x2029, 0x202F, 0x205F, 0x3000))
            .flatMap(IntStream::boxed).collect(java.util.stream.Collectors.toUnmodifiableSet());

    @Test
    @DisplayName("the window read's non-blank pattern and isBlankForEmbed agree with Python's str.strip() on every "
            + "single char")
    void blankRuleMatchesPythonStrip() {
        var pattern = java.util.regex.Pattern.compile(TopicVectorsDAO.NON_BLANK);
        for (int c = 0; c <= Character.MAX_VALUE; c++) {
            if (Character.isSurrogate((char) c)) {
                continue;
            }
            var text = String.valueOf((char) c);
            boolean blank = PYTHON_SPACE.contains(c);
            assertThat(TopicVectorsTickService.isBlankForEmbed(text)).as("U+%04X", c).isEqualTo(blank);
            assertThat(pattern.matcher(text).find()).as("U+%04X", c).isEqualTo(!blank);
        }
    }
}
