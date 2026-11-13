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
import io.opentelemetry.api.OpenTelemetry;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

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
import java.util.concurrent.TimeUnit;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
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
        private TopicVectorBackfillService backfills;
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
            // One slice covers any window here, so the daily pass is one read, as the fresh phase's.
            config.getVectors().getFind().setSliceSize(io.dropwizard.util.Duration.days(365));
            dao = mock(TopicVectorsDAO.class);
            stubNoDiscovery(dao);
            backfills = allDrained();
            client = mock(InferenceClient.class);
            lockService = mock(LockService.class);
            workspaces = mock(TopicWorkspaceService.class);
            registry = mock(AiLabelTaskRegistry.class);
            meter = new TestMeter();
            clock = new TestClock(NOW);
            service = new TopicVectorsTickService(config, new AiLabelsConfig(), workspaces, dao, backfills, client,
                    lockService,
                    registry, names(), noArrival(), meter.meter, clock);
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
                    .result(EmbedResult.builder().embeddings(embeddings).build())
                    .build());

            var result = service.tick(NOW);

            assertThat(inserted).extracting(TopicVector::traceId).containsExactly(normal.traceId());
            assertThat(result.written()).isOne();
            assertThat(result.skipped()).isEqualTo(Map.of(TopicVectorsTickService.INVALID_VECTOR, 2));
        }

        @Test
        @DisplayName("a vector shorter or longer than 384 costs only its own trace, the rest of the chunk is written")
        void wrongLengthVector() {
            var shorter = candidate(ID_GENERATOR.generateId(), 1, NOW.minusSeconds(3));
            var longer = candidate(ID_GENERATOR.generateId(), 1, NOW.minusSeconds(2));
            var normal = candidate(ID_GENERATOR.generateId(), 1, NOW.minusSeconds(1));
            var candidates = List.of(shorter, longer, normal);
            stub(candidates, candidate -> "intent of " + candidate.traceId());
            var texts = candidates.stream().map(candidate -> "intent of " + candidate.traceId()).toList();
            var full = vectorOf("x");
            var embeddings = List.of(full.subList(0, TopicVectorsTickService.EMBED_DIMENSIONS - 1),
                    Stream.concat(full.stream(), full.stream()).toList(),
                    vectorOf("intent of " + normal.traceId()));
            answerEmbed(embedRequest(WORKSPACE_ID, texts), EmbedResponse.builder()
                    .result(EmbedResult.builder().embeddings(embeddings).build())
                    .build());

            var result = service.tick(NOW);

            assertThat(embeddings).extracting(List::size).containsExactly(383, 768, 384);
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
                    .result(EmbedResult.builder().embeddings(null).build())
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

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        @DisplayName("an interrupt during an insert or the deleted-trace check stops the tick: the next workspace's "
                + "traces are not embedded")
        void interruptAfterEmbedStopsTheTick(boolean duringInsert) {
            when(workspaces.eligibleWorkspaceIds()).thenReturn(List.of("first", "second"));
            var first = candidate("first", NOW.minusSeconds(10));
            var second = candidate("second", NOW.minusSeconds(10));
            when(findUnvectored(dao, "first", NOW, SHORT_WINDOW, 5_000, FIND_SECONDS)).thenReturn(Flux.just(first));
            when(findUnvectored(dao, "second", NOW, SHORT_WINDOW, 5_000, FIND_SECONDS)).thenReturn(Flux.just(second));
            stubSummaries("first", List.of(first), candidate -> "first text", BUDGET_SECONDS);
            stubSummaries("second", List.of(second), candidate -> "second text", BUDGET_SECONDS);
            var interrupt = reactor.core.Exceptions.propagate(new InterruptedException());
            if (duringInsert) {
                when(dao.insert(anyList())).thenReturn(Mono.error(interrupt));
            } else {
                when(dao.findStatusKeys(eq("first"), eq(TopicVectorsTickService.TASK), anyCollection(), anyLong()))
                        .thenReturn(Flux.error(interrupt));
            }

            try {
                assertThatThrownBy(() -> service.tick(NOW))
                        .satisfies(error -> assertThat(reactor.core.Exceptions.unwrap(error))
                                .isInstanceOf(InterruptedException.class));
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
            assertThat(inserted).extracting(TopicVector::workspaceId).doesNotContain("second");
            assertThat(requests).flatExtracting(EmbedRequest::inputs).containsExactly("first text");
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
                // The discovery's two reads are window reads too: none with under a second left.
                verify(dao, times(reads)).findBackfillProjects(anyString(), anyString(), any(Instant.class),
                        any(Instant.class), anyLong());
                verify(dao, times(reads)).countTracesWithVectorsByProject(anyString(), any(Instant.class), anyLong());
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
            service = new TopicVectorsTickService(config, new AiLabelsConfig(), workspaces, dao, backfills, client,
                    lockService,
                    registry, new TopicsWorkspaceNames(id -> {
                        lookups.incrementAndGet();
                        return "name-" + id;
                    }, clock), noArrival(), meter.meter, clock);
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
            service = new TopicVectorsTickService(config, new AiLabelsConfig(), workspaces, dao, backfills, client,
                    lockService,
                    registry, new TopicsWorkspaceNames(id -> {
                        throw new IllegalStateException("react service down");
                    }, clock), noArrival(), meter.meter, clock);
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
            service = new TopicVectorsTickService(config, new AiLabelsConfig(), workspaces, dao, backfills, client,
                    lockService,
                    registry, names, noArrival(), meter.meter, clock);
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

    /**
     * The long-window pass's pace over several ticks. The lock service is a fake that keeps each key until its TTL
     * runs out on the test clock, as Redis does, so the ticks decide "due" by the TTLs they really set.
     */
    @Nested
    @DisplayName("long-window pace")
    class LongWindowPace {

        private static final Duration INTERVAL = Duration.ofMinutes(1);

        private TopicsConfig config;
        private TestClock clock;
        private TopicVectorsTickService service;
        private final Map<String, Instant> lockedUntil = new HashMap<>();
        private final List<Duration> windowsRead = new ArrayList<>();
        // What the next window reads answer, and how long each embed call takes.
        private int unvectored;
        private Duration embedTime = Duration.ZERO;

        @BeforeEach
        void setUp() {
            config = TestConfigUtils.loadConfigTest().getTopics();
            config.getVectors().getFind().setSliceSize(io.dropwizard.util.Duration.days(365));
            var dao = mock(TopicVectorsDAO.class);
            stubNoDiscovery(dao);
            var backfills = allDrained();
            var client = mock(InferenceClient.class);
            var lockService = mock(LockService.class);
            var workspaces = mock(TopicWorkspaceService.class);
            var registry = mock(AiLabelTaskRegistry.class);
            clock = new TestClock(NOW);
            service = new TopicVectorsTickService(config, new AiLabelsConfig(), workspaces, dao, backfills, client,
                    lockService,
                    registry, names(), noArrival(), new TestMeter().meter, clock);
            when(workspaces.eligibleWorkspaceIds()).thenReturn(List.of(WORKSPACE_ID));
            when(registry.find(TopicVectorsTickService.TASK)).thenReturn(java.util.Optional.of(task()));
            // SET NX PX and DEL on the test clock: any lock and TTL, because the TTLs are what the tests are about.
            when(lockService.lockUsingToken(any(LockService.Lock.class), any(Duration.class)))
                    .thenAnswer(invocation -> {
                        LockService.Lock lock = invocation.getArgument(0);
                        Duration ttl = invocation.getArgument(1);
                        assertThat(ttl).isPositive();
                        var until = lockedUntil.get(lock.key());
                        if (until != null && until.isAfter(clock.instant())) {
                            return Mono.just(false);
                        }
                        lockedUntil.put(lock.key(), clock.instant().plus(ttl));
                        return Mono.just(true);
                    });
            when(lockService.unlockUsingToken(any(LockService.Lock.class))).thenAnswer(invocation -> {
                LockService.Lock lock = invocation.getArgument(0);
                lockedUntil.remove(lock.key());
                return Mono.empty();
            });
            // Every read answers the same backlog and records its window, so this takes any bounds.
            when(dao.findUnvectored(eq(WORKSPACE_ID), eq(TopicVectorsTickService.TASK), any(Instant.class),
                    any(Instant.class), any(Instant.class), anyInt(), anyLong())).thenAnswer(invocation -> {
                        Instant lowerBound = invocation.getArgument(2);
                        Instant upperBound = invocation.getArgument(3);
                        windowsRead.add(Duration.between(lowerBound, upperBound));
                        return Flux.fromStream(IntStream.range(0, unvectored)
                                .mapToObj(i -> candidate(ID_GENERATOR.generateId(), 1, upperBound.minusSeconds(60))));
                    });
            when(dao.findSummaries(eq(WORKSPACE_ID), eq(TopicVectorsTickService.TASK), anyCollection(), anyLong()))
                    .thenAnswer(invocation -> {
                        Collection<TopicTraceKey> keys = invocation.getArgument(2);
                        return Flux.fromIterable(keys).map(key -> TopicSummary.builder().projectId(key.projectId())
                                .traceId(key.traceId()).taskVersion(1).summary("intent of " + key.traceId()).build());
                    });
            when(client.embed(any(EmbedRequest.class), any(Instant.class))).thenAnswer(invocation -> {
                EmbedRequest request = invocation.getArgument(0);
                clock.advance(embedTime);
                return Mono.just(embedded(request.inputs()));
            });
            when(dao.insert(anyList())).thenAnswer(invocation -> {
                List<TopicVector> rows = invocation.getArgument(0);
                return Mono.just((long) rows.size());
            });
            when(dao.findStatusKeys(anyString(), eq(TopicVectorsTickService.TASK), anyCollection(), anyLong()))
                    .thenAnswer(invocation -> {
                        Collection<TopicTraceKey> keys = invocation.getArgument(2);
                        return Flux.fromIterable(keys);
                    });
        }

        // The tick lock lasts 59 s and each pod fires at its own second, so ticks are 59 to 61 s apart.
        @ParameterizedTest(name = "ticks {0} s apart")
        @ValueSource(longs = {59, 60, 61})
        @DisplayName("a pass that uses its whole budget and does not drain runs again after one short-window tick")
        void budgetBoundPassEverySecondTick(long gapSeconds) {
            // More than a tick embeds: 10 s per call and a 10 s embed timeout fill the 50 s budget with 5 calls.
            unvectored = config.getVectors().getMaxTracesPerTick();
            embedTime = EMBED_TIMEOUT;
            var gap = Duration.ofSeconds(gapSeconds);

            var first = tickAt(NOW);
            assertThat(first.longWindowPass()).isTrue();
            assertThat(first.written()).isEqualTo(5 * 256);
            assertThat(clock.instant()).isEqualTo(NOW.plusSeconds(BUDGET_SECONDS));

            assertThat(tickAt(NOW.plus(gap)).longWindowPass()).isFalse();
            assertThat(tickAt(NOW.plus(gap.multipliedBy(2))).longWindowPass()).isTrue();
            assertThat(tickAt(NOW.plus(gap.multipliedBy(3))).longWindowPass()).isFalse();
            assertThat(tickAt(NOW.plus(gap.multipliedBy(4))).longWindowPass()).isTrue();
            assertThat(windowsRead)
                    .containsExactly(LONG_WINDOW, SHORT_WINDOW, LONG_WINDOW, SHORT_WINDOW, LONG_WINDOW);
        }

        @ParameterizedTest(name = "ticks {0} s apart")
        @ValueSource(longs = {59, 60, 61})
        @DisplayName("a pass that ends fast and does not drain runs again after one short-window tick")
        void fastPassEverySecondTick(long gapSeconds) {
            // At the cap, so not drained; the embed calls take no time.
            config.getVectors().setMaxTracesPerTick(2);
            unvectored = 2;
            var gap = Duration.ofSeconds(gapSeconds);

            assertThat(tickAt(NOW).longWindowPass()).isTrue();
            assertThat(clock.instant()).isEqualTo(NOW);

            assertThat(tickAt(NOW.plus(gap)).longWindowPass()).isFalse();
            assertThat(tickAt(NOW.plus(gap.multipliedBy(2))).longWindowPass()).isTrue();
            assertThat(tickAt(NOW.plus(gap.multipliedBy(3))).longWindowPass()).isFalse();
            assertThat(tickAt(NOW.plus(gap.multipliedBy(4))).longWindowPass()).isTrue();
            assertThat(windowsRead)
                    .containsExactly(LONG_WINDOW, SHORT_WINDOW, LONG_WINDOW, SHORT_WINDOW, LONG_WINDOW);
        }

        @Test
        @DisplayName("a drained pass is not due again until longWindowInterval after the tick that drained it")
        void drainedPassWaitsForTheInterval() {
            // Under the cap and embedded in one 10 s call: drained at NOW + 10 s.
            unvectored = 1;
            embedTime = EMBED_TIMEOUT;
            var drainedAt = NOW.plus(EMBED_TIMEOUT);
            var interval = config.getVectors().getLongWindowInterval().toJavaDuration();

            assertThat(tickAt(NOW).longWindowPass()).isTrue();
            assertThat(clock.instant()).isEqualTo(drainedAt);

            assertThat(tickAt(NOW.plus(INTERVAL)).longWindowPass()).isFalse();
            assertThat(tickAt(NOW.plus(INTERVAL.multipliedBy(2))).longWindowPass()).isFalse();
            assertThat(tickAt(drainedAt.plus(interval).minusSeconds(1)).longWindowPass()).isFalse();
            assertThat(tickAt(drainedAt.plus(interval)).longWindowPass()).isTrue();
        }

        @Test
        @DisplayName("a pass that ends after its not-due time is due at the next tick")
        void overrunPassIsDueAtTheNextTick() {
            // One embed call that returns 95 s after the tick started: past 1.5 tick intervals, and the tick one
            // interval after the start could not run, because this one held the leader lock.
            config.getVectors().setMaxTracesPerTick(2);
            unvectored = 2;
            embedTime = Duration.ofSeconds(95);

            assertThat(tickAt(NOW).longWindowPass()).isTrue();

            embedTime = Duration.ZERO;
            assertThat(tickAt(NOW.plus(INTERVAL.multipliedBy(2))).longWindowPass()).isTrue();
        }

        /** Moves the clock to {@code start}, as the job does when a pod's trigger fires, and runs the tick. */
        private TopicVectorsTickService.TickResult tickAt(Instant start) {
            clock.advance(Duration.between(clock.instant(), start));
            return service.tick(start);
        }
    }

    static Stream<Arguments> backfillOrders() {
        var cap = 5_000;
        var id1 = UUID.fromString("00000000-0000-7000-8000-000000000001");
        var id2 = UUID.fromString("00000000-0000-7000-8000-000000000002");
        return Stream.of(
                // Both fit in one tick: the fewest remaining first.
                arguments(backfill(id1, 4_000, 0, NOW), backfill(id2, 4_000, 3_000, NOW.minusSeconds(60)), cap, 1),
                // Both fit, the same remaining: the most recently active first.
                arguments(backfill(id1, 100, 0, NOW), backfill(id2, 100, 0, NOW.minusSeconds(1)), cap, -1),
                // Both fit, the same remaining and activity: by project id.
                arguments(backfill(id1, 100, 0, NOW), backfill(id2, 100, 0, NOW), cap, -1),
                // One fits, one does not: the one that fits first, however old.
                arguments(backfill(id1, 5_000, 0, NOW.minusSeconds(3_600)), backfill(id2, 5_001, 0, NOW), cap, -1),
                // Neither fits: the most recently active first, whatever remains.
                arguments(backfill(id1, 9_000, 0, NOW.minusSeconds(1)), backfill(id2, 90_000, 0, NOW), cap, 1),
                // Neither fits, the same activity: by project id.
                arguments(backfill(id1, 90_000, 0, NOW), backfill(id2, 9_000, 0, NOW), cap, -1),
                // More done than total counts as nothing remaining.
                arguments(backfill(id1, 10, 50, NOW.minusSeconds(1)), backfill(id2, 10, 9, NOW), cap, -1));
    }

    @ParameterizedTest(name = "{0} against {1}, cap {2}: {3}")
    @MethodSource("backfillOrders")
    @DisplayName("the backfill takes the projects that fit in one tick first, fewest remaining first, then the rest by "
            + "latest activity")
    void backfillOrder(TopicVectorBackfill first, TopicVectorBackfill second, int cap, int expected) {
        var order = TopicVectorBackfill.backfillOrder(cap);

        assertThat(Integer.signum(order.compare(first, second))).isEqualTo(expected);
        assertThat(Integer.signum(order.compare(second, first))).isEqualTo(-expected);
    }

    private static TopicVectorBackfill backfill(UUID projectId, long total, long done, Instant lastDoneAt) {
        return TopicVectorBackfill.builder().workspaceId(WORKSPACE_ID).projectId(projectId).startedAt(APPLIES_FROM)
                .checkedAt(NOW).lastDoneAt(lastDoneAt).tracesTotal(total).tracesDone(done).build();
    }

    /**
     * The three parts of a tick over projects. ClickHouse and the backfill table are fakes that keep state: a window
     * read answers the traces without a vector that its bounds, project and exclusions select, an insert gives them
     * their vector, and the table keeps each project's row. So the tests see what a tick embeds, by which read, and
     * what it marks.
     */
    @Nested
    @DisplayName("backfill by project")
    class BackfillByProject {

        private static final String OTHER_WORKSPACE = "other-workspace";

        /** One window read as the tick made it. */
        private record Read(String workspaceId, UUID projectId, List<UUID> excluded, Instant createdAfter,
                Instant createdBefore) {
        }

        private TopicsConfig config;
        private TopicVectorsDAO dao;
        private TopicVectorBackfillService backfills;
        private InferenceClient client;
        private LockService lockService;
        private TopicWorkspaceService workspaces;
        private TestMeter meter;
        private TestClock clock;
        private TopicVectorsTickService service;
        private final List<TopicVectorCandidate> unvectored = new ArrayList<>();
        private final List<UUID> embedded = new ArrayList<>();
        private final Map<UUID, TopicVectorBackfill> rows = new HashMap<>();
        private final List<Read> reads = new ArrayList<>();
        private final List<TopicBackfillProject> discoverable = new ArrayList<>();
        private final Map<UUID, Long> vectorCounts = new HashMap<>();
        private final Set<UUID> failingProjects = new java.util.HashSet<>();
        private boolean recordFails;
        private Duration embedTime = Duration.ZERO;

        @BeforeEach
        void setUp() {
            config = TestConfigUtils.loadConfigTest().getTopics();
            dao = mock(TopicVectorsDAO.class);
            backfills = mock(TopicVectorBackfillService.class);
            client = mock(InferenceClient.class);
            lockService = mock(LockService.class);
            workspaces = mock(TopicWorkspaceService.class);
            var registry = mock(AiLabelTaskRegistry.class);
            meter = new TestMeter();
            clock = new TestClock(NOW);
            service = new TopicVectorsTickService(config, new AiLabelsConfig(), workspaces, dao, backfills, client,
                    lockService, registry, names(), noArrival(), meter.meter, clock);
            when(workspaces.eligibleWorkspaceIds()).thenReturn(List.of(WORKSPACE_ID));
            when(registry.find(TopicVectorsTickService.TASK)).thenReturn(java.util.Optional.of(task()));
            // The daily pass is not due unless a test says so; it ends by setting the lock again, for any time.
            when(lockService.lockUsingToken(eq(TopicVectorsTickService.LONG_WINDOW_LOCK), any(Duration.class)))
                    .thenReturn(Mono.just(false));
            when(lockService.unlockUsingToken(TopicVectorsTickService.LONG_WINDOW_LOCK)).thenReturn(Mono.empty());
            stubClickHouse();
            stubBackfillTable();
            when(client.embed(any(EmbedRequest.class), any(Instant.class))).thenAnswer(invocation -> {
                EmbedRequest request = invocation.getArgument(0);
                clock.advance(embedTime);
                return Mono.just(embedded(request.inputs()));
            });
        }

        /** Every read takes any bounds: the fake selects by them, and the tests check the reads it recorded. */
        private void stubClickHouse() {
            when(dao.findUnvectored(anyString(), eq(TopicVectorsTickService.TASK), any(Instant.class),
                    any(Instant.class), any(Instant.class), anyInt(), anyLong()))
                    .thenAnswer(invocation -> read(invocation.getArgument(0), null, List.of(),
                            invocation.getArgument(2), invocation.getArgument(3), invocation.getArgument(4), null,
                            invocation.getArgument(5)));
            when(dao.findUnvectored(anyString(), eq(TopicVectorsTickService.TASK), any(Instant.class),
                    any(Instant.class), any(Instant.class), nullable(Instant.class), nullable(UUID.class),
                    anyCollection(), anyInt(), anyLong()))
                    .thenAnswer(invocation -> read(invocation.getArgument(0), invocation.getArgument(6),
                            invocation.getArgument(7), invocation.getArgument(2), invocation.getArgument(3),
                            invocation.getArgument(4), invocation.getArgument(5), invocation.getArgument(8)));
            when(dao.findSummaries(anyString(), eq(TopicVectorsTickService.TASK), anyCollection(), anyLong()))
                    .thenAnswer(invocation -> {
                        Collection<TopicTraceKey> keys = invocation.getArgument(2);
                        return Flux.fromIterable(keys).map(key -> TopicSummary.builder().projectId(key.projectId())
                                .traceId(key.traceId()).taskVersion(1).summary("intent of " + key.traceId()).build());
                    });
            when(dao.insert(anyList())).thenAnswer(invocation -> {
                List<TopicVector> vectors = invocation.getArgument(0);
                vectors.forEach(vector -> {
                    embedded.add(vector.traceId());
                    unvectored.removeIf(candidate -> candidate.traceId().equals(vector.traceId()));
                });
                return Mono.just((long) vectors.size());
            });
            when(dao.findStatusKeys(anyString(), eq(TopicVectorsTickService.TASK), anyCollection(), anyLong()))
                    .thenAnswer(invocation -> {
                        Collection<TopicTraceKey> keys = invocation.getArgument(2);
                        return Flux.fromIterable(keys);
                    });
            when(dao.findBackfillProjects(anyString(), eq(TopicVectorsTickService.TASK), any(Instant.class),
                    any(Instant.class), anyLong())).thenAnswer(invocation -> Flux.fromIterable(discoverable));
            when(dao.countTracesWithVectorsByProject(anyString(), any(Instant.class), anyLong()))
                    .thenAnswer(invocation -> Mono.just(Map.copyOf(vectorCounts)));
        }

        private Flux<TopicVectorCandidate> read(String workspaceId, UUID projectId, Collection<UUID> excluded,
                Instant lowerBound, Instant upperBound, Instant createdAfter, Instant createdBefore, int limit) {
            reads.add(new Read(workspaceId, projectId, List.copyOf(excluded), createdAfter, createdBefore));
            if (projectId != null && failingProjects.contains(projectId)) {
                return Flux.error(new IllegalStateException("read failed"));
            }
            return Flux.fromIterable(unvectored.stream()
                    .filter(candidate -> candidate.workspaceId().equals(workspaceId))
                    .filter(candidate -> projectId == null || candidate.projectId().equals(projectId))
                    .filter(candidate -> !excluded.contains(candidate.projectId()))
                    .filter(candidate -> !candidate.statusUpdatedAt().isBefore(lowerBound)
                            && !candidate.statusUpdatedAt().isAfter(upperBound))
                    .filter(candidate -> !candidate.traceCreatedAt().isBefore(createdAfter))
                    .filter(candidate -> createdBefore == null || candidate.traceCreatedAt().isBefore(createdBefore))
                    .sorted(java.util.Comparator.comparing(TopicVectorCandidate::statusUpdatedAt))
                    .limit(limit)
                    .toList());
        }

        /** The table takes any workspace, key and row: it is the state the tests read back. */
        private void stubBackfillTable() {
            when(backfills.findUndrained(anyCollection())).thenAnswer(invocation -> {
                Collection<String> workspaceIds = invocation.getArgument(0);
                return rows.values().stream()
                        .filter(row -> row.drainedAt() == null && workspaceIds.contains(row.workspaceId()))
                        .collect(java.util.stream.Collectors.groupingBy(TopicVectorBackfill::workspaceId));
            });
            when(backfills.findByKeys(anyString(), anyCollection())).thenAnswer(invocation -> {
                Collection<UUID> projectIds = invocation.getArgument(1);
                return rows.values().stream().filter(row -> projectIds.contains(row.projectId()))
                        .collect(java.util.stream.Collectors.toMap(TopicVectorBackfill::projectId,
                                Function.identity()));
            });
            doAnswer(invocation -> {
                List<TopicVectorBackfill> started = invocation.getArgument(0);
                started.forEach(row -> rows.putIfAbsent(row.projectId(), row));
                return null;
            }).when(backfills).start(anyList());
            doAnswer(invocation -> {
                List<TopicVectorBackfill> found = invocation.getArgument(1);
                found.forEach(row -> rows.merge(row.projectId(), row, (old, fresh) -> old.toBuilder()
                        .checkedAt(fresh.checkedAt()).lastDoneAt(fresh.lastDoneAt())
                        .tracesTotal(fresh.tracesTotal()).tracesDone(fresh.tracesDone()).build()));
                return 0;
            }).when(backfills).discovered(anyString(), anyList(), any(Instant.class), any(Instant.class));
            doAnswer(invocation -> {
                if (recordFails) {
                    throw new IllegalStateException("MySQL is down");
                }
                List<TopicVectorBackfill.Progress> progress = invocation.getArgument(0);
                progress.forEach(done -> rows.computeIfPresent(done.projectId(), (id, row) -> row.toBuilder()
                        .drainedAt(done.drainedAt() != null ? done.drainedAt() : row.drainedAt())
                        .tracesDone(row.tracesDone() + done.written()).build()));
                return null;
            }).when(backfills).record(anyList());
        }

        @Test
        @DisplayName("two projects in backfill: the first drains and the tick goes on to the second, each trace "
                + "embedded once")
        void twoProjectsInOneTick() {
            var first = undrained(WORKSPACE_ID, NOW.minusSeconds(10));
            var second = undrained(WORKSPACE_ID, NOW.minusSeconds(20));
            var traces = Stream.of(first, first, first, second, second)
                    .map(project -> trace(project, NOW.minusSeconds(3_600))).toList();

            var result = service.tick(NOW);

            assertThat(result.written()).isEqualTo(5);
            assertThat(result.projectsDrained()).isEqualTo(2);
            assertThat(embedded).containsExactlyInAnyOrderElementsOf(
                    traces.stream().map(TopicVectorCandidate::traceId).toList());
            assertThat(rows.get(first.projectId()).drainedAt()).isEqualTo(NOW);
            assertThat(rows.get(second.projectId()).drainedAt()).isEqualTo(NOW);
            // The workspace read leaves both out; then each project is read on its own, the most recent first.
            assertThat(reads).extracting(Read::projectId).containsExactly(null, first.projectId(),
                    second.projectId());
            assertThat(reads.getFirst().excluded()).containsExactlyInAnyOrder(first.projectId(),
                    second.projectId());
            assertThat(unvectored).isEmpty();
        }

        @Test
        @DisplayName("the workspace read serves the drained projects and leaves the others to the backfill phase")
        void freshPhaseSkipsUndrainedProjects() {
            var drained = drained(WORKSPACE_ID);
            var backlog = undrained(WORKSPACE_ID, NOW.minusSeconds(10));
            var fresh = trace(drained, NOW.minusSeconds(30));
            // Written inside the short window too: without the exclusion the workspace read would take it.
            var old = trace(backlog, NOW.minusSeconds(40));
            var requests = new ArrayList<List<String>>();
            when(client.embed(any(EmbedRequest.class), any(Instant.class))).thenAnswer(invocation -> {
                EmbedRequest request = invocation.getArgument(0);
                requests.add(request.inputs());
                return Mono.just(embedded(request.inputs()));
            });

            var result = service.tick(NOW);

            // One embed call per phase, each with its own trace: no trace is embedded by both.
            assertThat(requests).containsExactly(List.of("intent of " + fresh.traceId()),
                    List.of("intent of " + old.traceId()));
            assertThat(embedded).containsExactly(fresh.traceId(), old.traceId());
            assertThat(reads.getFirst()).isEqualTo(new Read(WORKSPACE_ID, null, List.of(backlog.projectId()),
                    reads.getFirst().createdAfter(), null));
            assertThat(result.projectsDrained()).isOne();
        }

        @Test
        @DisplayName("a project marked drained has no done status without a vector in its window at the tick's start")
        void drainedMeansNothingLeft() {
            config.getVectors().setMaxTracesPerTick(2);
            var small = undrained(WORKSPACE_ID, NOW.minusSeconds(10));
            var large = undrained(WORKSPACE_ID, NOW.minusSeconds(20));
            trace(small, NOW.minusSeconds(3_600));
            IntStream.range(0, 3).forEach(i -> trace(large, NOW.minusSeconds(7_200 + i)));
            // Time for two embed calls: the small project's, and one turn of the large one.
            embedTime = EMBED_TIMEOUT;
            clock.advance(Duration.ofSeconds(BUDGET_SECONDS).minus(EMBED_TIMEOUT.multipliedBy(2)));

            assertThat(service.tick(NOW).projectsDrained()).isOne();

            assertThat(rows.get(small.projectId()).drainedAt()).isEqualTo(NOW);
            assertThat(unvectored).noneMatch(candidate -> candidate.projectId().equals(small.projectId()));
            // At the cap: one trace is left, so the project is not drained, and its two vectors are counted.
            assertThat(rows.get(large.projectId()).drainedAt()).isNull();
            assertThat(rows.get(large.projectId()).tracesDone()).isEqualTo(2);
            assertThat(unvectored).hasSize(1);

            embedTime = Duration.ZERO;
            clock.advance(Duration.between(clock.instant(), NOW.plusSeconds(60)));
            assertThat(service.tick(NOW.plusSeconds(60)).projectsDrained()).isOne();
            assertThat(rows.get(large.projectId()).drainedAt()).isEqualTo(NOW.plusSeconds(60));
            assertThat(unvectored).isEmpty();
        }

        @Test
        @DisplayName("a project whose read fails stays in backfill, is counted, and does not stop the next project")
        void failingProjectRead() {
            var broken = undrained(WORKSPACE_ID, NOW.minusSeconds(10));
            var healthy = undrained(WORKSPACE_ID, NOW.minusSeconds(20));
            trace(broken, NOW.minusSeconds(3_600));
            var kept = trace(healthy, NOW.minusSeconds(3_600));
            failingProjects.add(broken.projectId());

            var result = service.tick(NOW);

            assertThat(result.projectsDrained()).isOne();
            assertThat(embedded).containsExactly(kept.traceId());
            assertThat(rows.get(broken.projectId()).drainedAt()).isNull();
            assertThat(rows.get(healthy.projectId()).drainedAt()).isEqualTo(NOW);
            verify(meter.counter("topics.vectors.read.failures")).add(1, workspace(WORKSPACE_ID)
                    .put(stringKey("phase"), "backfill")
                    .put(stringKey("error_type"), "IllegalStateException").build());
        }

        @Test
        @DisplayName("a project new to a running workspace gets its row from the workspace read and is drained by "
                + "the backfill phase of the same tick")
        void newProjectDrainsAtOnce() {
            var projectId = ID_GENERATOR.generateId();
            var first = trace(WORKSPACE_ID, projectId, NOW.minusSeconds(30), NOW.minusSeconds(20));

            var result = service.tick(NOW);

            assertThat(result.written()).isOne();
            assertThat(result.projectsDrained()).isOne();
            assertThat(embedded).containsExactly(first.traceId());
            assertThat(rows.get(projectId)).isEqualTo(TopicVectorBackfill.builder().workspaceId(WORKSPACE_ID)
                    .projectId(projectId).startedAt(NOW).checkedAt(NOW).drainedAt(NOW)
                    .lastDoneAt(first.statusUpdatedAt()).tracesDone(1).build());
            // Found by the workspace read, embedded by the project's own read.
            assertThat(reads).extracting(Read::projectId).containsExactly(null, projectId);

            // The next tick's workspace read serves it like any drained project.
            var next = trace(WORKSPACE_ID, projectId, NOW, NOW.plusSeconds(10));
            clock.advance(Duration.ofMinutes(1));
            assertThat(service.tick(NOW.plusSeconds(60)).written()).isOne();
            assertThat(embedded).containsExactly(first.traceId(), next.traceId());
            assertThat(reads).extracting(Read::projectId).containsExactly(null, projectId, null);
        }

        @Test
        @DisplayName("workspaces take turns in the backfill phase, one project each")
        void workspacesTakeTurns() {
            when(workspaces.eligibleWorkspaceIds()).thenReturn(List.of(WORKSPACE_ID, OTHER_WORKSPACE));
            var a1 = undrained(WORKSPACE_ID, NOW.minusSeconds(10));
            var a2 = undrained(WORKSPACE_ID, NOW.minusSeconds(20));
            var b1 = undrained(OTHER_WORKSPACE, NOW.minusSeconds(10));
            var b2 = undrained(OTHER_WORKSPACE, NOW.minusSeconds(20));

            assertThat(service.tick(NOW).projectsDrained()).isEqualTo(4);

            var order = reads.stream().map(Read::projectId).filter(java.util.Objects::nonNull).toList();
            // The visiting order rotates with the tick, so either workspace may lead; then they alternate.
            assertThat(order).isIn(
                    List.of(a1.projectId(), b1.projectId(), a2.projectId(), b2.projectId()),
                    List.of(b1.projectId(), a1.projectId(), b2.projectId(), a2.projectId()));
        }

        @Test
        @DisplayName("after an embed back-off the workspace's other projects wait for the next tick, and none is "
                + "marked drained")
        void backOffStopsTheWorkspace() {
            var first = undrained(WORKSPACE_ID, NOW.minusSeconds(10));
            var second = undrained(WORKSPACE_ID, NOW.minusSeconds(20));
            trace(first, NOW.minusSeconds(3_600));
            trace(second, NOW.minusSeconds(3_600));
            when(client.embed(any(EmbedRequest.class), any(Instant.class)))
                    .thenReturn(Mono.error(inferenceException(503, "something_new", false)));

            var result = service.tick(NOW);

            assertThat(result.projectsDrained()).isZero();
            assertThat(result.skipped()).isEqualTo(Map.of(TopicVectorsTickService.EMBED_FAILED, 1));
            assertThat(reads).extracting(Read::projectId).containsExactly(null, first.projectId());
            assertThat(rows.values()).allMatch(row -> row.drainedAt() == null);
        }

        @Test
        @DisplayName("progress that MySQL does not take leaves the projects in backfill: the next tick reads them "
                + "again and marks them")
        void recordFailure() {
            var project = undrained(WORKSPACE_ID, NOW.minusSeconds(10));
            trace(project, NOW.minusSeconds(3_600));
            recordFails = true;

            var result = service.tick(NOW);

            assertThat(result.written()).isOne();
            assertThat(result.projectsDrained()).isZero();
            assertThat(rows.get(project.projectId()).drainedAt()).isNull();

            recordFails = false;
            clock.advance(Duration.ofMinutes(1));
            assertThat(service.tick(NOW.plusSeconds(60)).projectsDrained()).isOne();
            assertThat(embedded).hasSize(1);
        }

        @Test
        @DisplayName("a large project is read in slices of trace creation time, newest first, that cover its window "
                + "once: a trace on a slice edge and an old trace with a late status are each found once")
        void largeProjectInSlices() {
            var slice = Duration.ofDays(1);
            var project = TopicVectorBackfill.builder().workspaceId(WORKSPACE_ID)
                    .projectId(ID_GENERATOR.generateId()).startedAt(NOW).checkedAt(NOW).lastDoneAt(NOW)
                    .tracesTotal(TopicVectorsTickService.ONE_SLICE_MAX_TRACES + 1).build();
            rows.put(project.projectId(), project);
            // Created exactly on the edge between the first and the second slice.
            var onEdge = trace(WORKSPACE_ID, project.projectId(), NOW.minus(slice), NOW.minusSeconds(5));
            // Created ten days ago, its status written an hour ago.
            var late = trace(WORKSPACE_ID, project.projectId(), NOW.minus(Duration.ofDays(10)),
                    NOW.minusSeconds(3_600));
            var recent = trace(project, NOW.minusSeconds(60));

            var result = service.tick(NOW);

            assertThat(result.projectsDrained()).isOne();
            // Newest slice first; in a slice, the oldest status first.
            assertThat(embedded).containsExactly(recent.traceId(), onEdge.traceId(), late.traceId());
            var slices = reads.stream().filter(read -> project.projectId().equals(read.projectId())).toList();
            // 7 d window + 7 d horizon + 1 d slack = 15 slices of a day.
            assertThat(slices).hasSize(15);
            assertThat(slices.getFirst().createdBefore()).isNull();
            assertThat(slices.getFirst().createdAfter()).isEqualTo(NOW.minus(slice));
            for (int i = 1; i < slices.size(); i++) {
                assertThat(slices.get(i).createdBefore()).isEqualTo(slices.get(i - 1).createdAfter());
                assertThat(slices.get(i).createdAfter()).isEqualTo(NOW.minus(slice.multipliedBy(i + 1)));
            }
            assertThat(slices.getLast().createdAfter()).isEqualTo(NOW.minus(Duration.ofDays(15)));
        }

        @Test
        @DisplayName("a small project is read in one query over its whole window")
        void smallProjectInOneRead() {
            var project = undrained(WORKSPACE_ID, NOW.minusSeconds(10));
            trace(WORKSPACE_ID, project.projectId(), NOW.minus(Duration.ofDays(10)), NOW.minusSeconds(3_600));

            assertThat(service.tick(NOW).projectsDrained()).isOne();

            assertThat(reads).filteredOn(read -> read.projectId() != null)
                    .containsExactly(new Read(WORKSPACE_ID, project.projectId(), List.of(),
                            NOW.minus(Duration.ofDays(15)), null));
        }

        @Test
        @DisplayName("the daily pass reads the workspace in slices, without the projects in backfill, and finds the "
                + "same traces as one read of the window")
        void dailyPassInSlices() {
            when(lockService.lockUsingToken(eq(TopicVectorsTickService.LONG_WINDOW_LOCK), any(Duration.class)))
                    .thenReturn(Mono.just(true));
            var drained = drained(WORKSPACE_ID);
            var backlog = undrained(WORKSPACE_ID, NOW.minusSeconds(10));
            var onEdge = trace(WORKSPACE_ID, drained.projectId(), NOW.minus(Duration.ofDays(2)),
                    NOW.minus(Duration.ofDays(2)).plusSeconds(5));
            var late = trace(WORKSPACE_ID, drained.projectId(), NOW.minus(Duration.ofDays(12)),
                    NOW.minus(Duration.ofDays(3)));
            var recent = trace(drained, NOW.minusSeconds(60));
            // What one read of the whole window selects, before anything is embedded.
            var window = TopicVectorsTickService.lowerBound(APPLIES_FROM, NOW, LONG_WINDOW);
            var unsliced = read(WORKSPACE_ID, null, List.of(backlog.projectId()), window, NOW,
                    NOW.minus(Duration.ofDays(15)), null, 5_000).map(TopicVectorCandidate::traceId).collectList()
                    .block();
            reads.clear();

            var result = service.tick(NOW);

            assertThat(result.longWindowPass()).isTrue();
            var workspaceReads = reads.stream().filter(read -> read.projectId() == null).toList();
            assertThat(workspaceReads).hasSize(15)
                    .allMatch(read -> read.excluded().equals(List.of(backlog.projectId())));
            assertThat(unsliced).containsExactlyInAnyOrder(recent.traceId(), onEdge.traceId(), late.traceId());
            assertThat(embedded).containsExactlyInAnyOrderElementsOf(unsliced);
            assertThat(embedded).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the discovery gives each project with done statuses a row with its counts, once per "
                + "longWindowInterval, and again at once for a workspace enabled again")
        void discovery() {
            var known = drained(WORKSPACE_ID);
            var fresh = ID_GENERATOR.generateId();
            discoverable.add(TopicBackfillProject.builder().projectId(known.projectId()).traces(40)
                    .lastDoneAt(NOW.minusSeconds(5)).build());
            discoverable.add(TopicBackfillProject.builder().projectId(fresh).traces(100)
                    .lastDoneAt(NOW.minusSeconds(9)).build());
            // More vector rows than traces: capped at the total.
            vectorCounts.put(known.projectId(), 45L);
            vectorCounts.put(fresh, 60L);

            service.tick(NOW);

            var lowerBound = NOW.minus(LONG_WINDOW);
            verify(dao).findBackfillProjects(WORKSPACE_ID, TopicVectorsTickService.TASK, lowerBound,
                    lowerBound.minus(Duration.ofDays(8)), FIND_SECONDS);
            verify(dao).countTracesWithVectorsByProject(WORKSPACE_ID, lowerBound, FIND_SECONDS);
            verify(backfills).discovered(eq(WORKSPACE_ID), anyList(), eq(NOW), eq(NOW.minus(Duration.ofDays(2))));
            // The known project keeps its start and its drained mark.
            assertThat(rows.get(known.projectId())).isEqualTo(known.toBuilder().checkedAt(NOW)
                    .lastDoneAt(NOW.minusSeconds(5)).tracesTotal(40).tracesDone(40).build());
            // The new one starts a backfill; with nothing left to embed, this tick drains it.
            assertThat(rows.get(fresh)).isEqualTo(TopicVectorBackfill.builder().workspaceId(WORKSPACE_ID)
                    .projectId(fresh).startedAt(NOW).checkedAt(NOW).drainedAt(NOW)
                    .lastDoneAt(NOW.minusSeconds(9)).tracesTotal(100).tracesDone(60).build());

            clock.advance(Duration.ofHours(23));
            service.tick(NOW.plus(Duration.ofHours(23)));
            verify(dao, times(1)).findBackfillProjects(anyString(), anyString(), any(Instant.class),
                    any(Instant.class), anyLong());

            clock.advance(Duration.ofHours(1));
            service.tick(NOW.plus(Duration.ofDays(1)));
            verify(dao, times(2)).findBackfillProjects(anyString(), anyString(), any(Instant.class),
                    any(Instant.class), anyLong());

            // Out of the list for one tick, then back: discovered at its first tick.
            when(workspaces.eligibleWorkspaceIds()).thenReturn(List.of());
            clock.advance(Duration.ofMinutes(1));
            service.tick(clock.instant());
            when(workspaces.eligibleWorkspaceIds()).thenReturn(List.of(WORKSPACE_ID));
            clock.advance(Duration.ofMinutes(1));
            service.tick(clock.instant());
            verify(dao, times(3)).findBackfillProjects(anyString(), anyString(), any(Instant.class),
                    any(Instant.class), anyLong());
        }

        @Test
        @DisplayName("a failed discovery is counted and tried again next tick, and the tick still serves traces")
        void discoveryFailure() {
            var project = drained(WORKSPACE_ID);
            var kept = trace(project, NOW.minusSeconds(30));
            when(dao.findBackfillProjects(anyString(), anyString(), any(Instant.class), any(Instant.class),
                    anyLong())).thenReturn(Flux.error(new IllegalStateException("read failed")));

            assertThat(service.tick(NOW).written()).isOne();

            assertThat(embedded).containsExactly(kept.traceId());
            verify(meter.counter("topics.vectors.read.failures")).add(1, workspace(WORKSPACE_ID)
                    .put(stringKey("phase"), "discover")
                    .put(stringKey("error_type"), "IllegalStateException").build());
            clock.advance(Duration.ofMinutes(1));
            service.tick(NOW.plusSeconds(60));
            verify(dao, times(2)).findBackfillProjects(anyString(), anyString(), any(Instant.class),
                    any(Instant.class), anyLong());
        }

        @Test
        @DisplayName("a workspace with more projects in backfill than a read can leave out is not read as a whole: it "
                + "is counted as not read every tick, and logged once")
        void tooManyProjectsToLeaveOut() {
            var drained = drained(WORKSPACE_ID);
            trace(drained, NOW.minusSeconds(30));
            IntStream.rangeClosed(0, TopicVectorsTickService.MAX_EXCLUDED_PROJECTS)
                    .forEach(i -> undrained(WORKSPACE_ID, NOW.minusSeconds(i)));
            // Time for a window read, not for an embed call: the backfill phase does not start, so none is drained.
            clock.advance(Duration.ofSeconds(BUDGET_SECONDS - 5));
            var appender = new ListAppender<ILoggingEvent>();
            var logger = (Logger) LoggerFactory.getLogger(TopicVectorsTickService.class);
            appender.start();
            logger.addAppender(appender);

            var result = service.tick(NOW);

            assertThat(result.written()).isZero();
            assertThat(reads).isEmpty();
            // Visible to an operator: counted as not read, and without a value in the oldest-unvectored gauge.
            assertThat(meter.observeGauge()).isEmpty();
            verify(meter.counter("topics.vectors.workspaces_not_read")).add(1, workspace(WORKSPACE_ID).build());
            var warnings = appender.list.stream()
                    .filter(event -> event.getFormattedMessage().contains("workspace reads skipped")).count();
            assertThat(warnings).isOne();

            // Still over: counted again, logged once.
            clock.advance(Duration.ofMinutes(1));
            service.tick(clock.instant().minusSeconds(BUDGET_SECONDS - 5));
            verify(meter.counter("topics.vectors.workspaces_not_read"), times(2)).add(1,
                    workspace(WORKSPACE_ID).build());
            assertThat(appender.list.stream()
                    .filter(event -> event.getFormattedMessage().contains("workspace reads skipped")).count())
                    .isOne();
            logger.detachAppender(appender);
        }

        @Test
        @DisplayName("a large project alone takes turn after turn in one tick while the tick has time, not one cap")
        void largeProjectUsesTheTick() {
            config.getVectors().setMaxTracesPerTick(2);
            var large = undrained(WORKSPACE_ID, NOW.minusSeconds(10));
            IntStream.range(0, 20).forEach(i -> trace(large, NOW.minusSeconds(7_200 + i)));
            // 10 s per embed call and a 10 s embed timeout: the 50 s budget has time for 5 turns.
            embedTime = EMBED_TIMEOUT;

            var result = service.tick(NOW);

            assertThat(result.written()).isEqualTo(10);
            assertThat(result.projectsDrained()).isZero();
            assertThat(rows.get(large.projectId()).tracesDone()).isEqualTo(10);
            assertThat(reads).filteredOn(read -> read.projectId() != null).hasSize(5);
            assertThat(clock.instant()).isEqualTo(NOW.plusSeconds(BUDGET_SECONDS));

            // With time to spare, the same project is drained in one tick: 2 + 2 + 2 + 2 + 2 of 10, then an empty read.
            embedTime = Duration.ZERO;
            clock.advance(Duration.ofSeconds(10));
            var next = service.tick(NOW.plusSeconds(60));
            assertThat(next.written()).isEqualTo(10);
            assertThat(next.projectsDrained()).isOne();
            assertThat(unvectored).isEmpty();
        }

        @Test
        @DisplayName("a large project takes its workspace's turns; the other workspace still gets every second turn")
        void largeProjectKeepsTheTurns() {
            when(workspaces.eligibleWorkspaceIds()).thenReturn(List.of(WORKSPACE_ID, OTHER_WORKSPACE));
            config.getVectors().setMaxTracesPerTick(2);
            var large = undrained(WORKSPACE_ID, NOW.minusSeconds(10));
            IntStream.range(0, 5).forEach(i -> trace(large, NOW.minusSeconds(7_200 + i)));
            var b1 = undrained(OTHER_WORKSPACE, NOW.minusSeconds(10));
            var b2 = undrained(OTHER_WORKSPACE, NOW.minusSeconds(20));

            assertThat(service.tick(NOW).projectsDrained()).isEqualTo(3);

            var order = reads.stream().map(Read::projectId).filter(java.util.Objects::nonNull).toList();
            // 2 + 2 + 1 for the large project, in three turns; the visiting order decides who leads.
            assertThat(order).isIn(
                    List.of(large.projectId(), b1.projectId(), large.projectId(), b2.projectId(),
                            large.projectId()),
                    List.of(b1.projectId(), large.projectId(), b2.projectId(), large.projectId(),
                            large.projectId()));
        }

        @Test
        @DisplayName("the gauges publish, per workspace, the projects still in backfill after the tick and the age of "
                + "the oldest of them")
        void undrainedGauges() {
            drained(WORKSPACE_ID);
            var done = undrained(WORKSPACE_ID, NOW.minusSeconds(10));
            var stuck = undrained(WORKSPACE_ID, NOW.minusSeconds(20));
            rows.put(stuck.projectId(), stuck.toBuilder().startedAt(NOW.minusSeconds(600)).build());
            trace(stuck, NOW.minusSeconds(3_600));
            failingProjects.add(stuck.projectId());

            assertThat(service.tick(NOW).projectsDrained()).isOne();

            assertThat(rows.get(done.projectId()).drainedAt()).isEqualTo(NOW);
            assertThat(meter.observeGauge("topics.vectors.backfill.undrained_projects"))
                    .isEqualTo(Map.of(WORKSPACE_ID, 1.0));
            assertThat(meter.observeGauge("topics.vectors.backfill.oldest_started_seconds"))
                    .isEqualTo(Map.of(WORKSPACE_ID, 600.0));

            failingProjects.clear();
            clock.advance(Duration.ofMinutes(1));
            assertThat(service.tick(NOW.plusSeconds(60)).projectsDrained()).isOne();
            assertThat(meter.observeGauge("topics.vectors.backfill.undrained_projects"))
                    .isEqualTo(Map.of(WORKSPACE_ID, 0.0));
            assertThat(meter.observeGauge("topics.vectors.backfill.oldest_started_seconds"))
                    .isEqualTo(Map.of(WORKSPACE_ID, 0.0));
            // Stale like the other gauge: not published by a pod that no longer ticks.
            clock.advance(Duration.ofMinutes(2).plusMillis(1));
            assertThat(meter.observeGauge("topics.vectors.backfill.undrained_projects")).isEmpty();
        }

        @Test
        @DisplayName("the oldest-unvectored gauge counts only the drained projects' traces")
        void oldestGaugeLeavesOutBackfill() {
            var drained = drained(WORKSPACE_ID);
            var backlog = undrained(WORKSPACE_ID, NOW.minusSeconds(10));
            trace(drained, NOW.minusSeconds(30));
            trace(backlog, NOW.minusSeconds(7_200));

            service.tick(NOW);

            assertThat(meter.observeGauge()).isEqualTo(Map.of(WORKSPACE_ID, 30.0));
        }

        private TopicVectorBackfill drained(String workspaceId) {
            var row = TopicVectorBackfill.builder().workspaceId(workspaceId).projectId(ID_GENERATOR.generateId())
                    .startedAt(APPLIES_FROM).checkedAt(NOW.minusSeconds(60)).drainedAt(APPLIES_FROM)
                    .lastDoneAt(NOW.minusSeconds(60)).build();
            rows.put(row.projectId(), row);
            return row;
        }

        private TopicVectorBackfill undrained(String workspaceId, Instant lastDoneAt) {
            var row = TopicVectorBackfill.builder().workspaceId(workspaceId).projectId(ID_GENERATOR.generateId())
                    .startedAt(NOW.minusSeconds(60)).checkedAt(NOW.minusSeconds(60)).lastDoneAt(lastDoneAt).build();
            rows.put(row.projectId(), row);
            return row;
        }

        /** A done trace of {@code project} without a vector, created a minute before its status. */
        private TopicVectorCandidate trace(TopicVectorBackfill project, Instant statusUpdatedAt) {
            return trace(project.workspaceId(), project.projectId(), statusUpdatedAt.minusSeconds(60),
                    statusUpdatedAt);
        }

        private TopicVectorCandidate trace(String workspaceId, UUID projectId, Instant createdAt,
                Instant statusUpdatedAt) {
            var trace = TopicVectorCandidate.builder().workspaceId(workspaceId).projectId(projectId)
                    .traceId(ID_GENERATOR.generateId()).traceCreatedAt(createdAt).taskVersion(1)
                    .statusUpdatedAt(statusUpdatedAt).build();
            unvectored.add(trace);
            return trace;
        }
    }

    /**
     * Several embed calls in flight. The calls run on other threads, so these tests hold a call with a latch or a
     * barrier until the calls they need have started; none depends on timing. One trace per chunk, so a chunk is a
     * call.
     */
    @Nested
    @DisplayName("embed concurrency")
    class EmbedConcurrency {

        private static final long WAIT_SECONDS = 10;

        private TopicsConfig config;
        private TopicVectorsDAO dao;
        private InferenceClient client;
        private TestClock clock;
        private TopicVectorsTickService service;
        private final List<TopicVectorCandidate> candidates = new ArrayList<>();
        private final List<UUID> inserted = Collections.synchronizedList(new ArrayList<>());
        private final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger mostInFlight = new java.util.concurrent.atomic.AtomicInteger();
        private final List<String> called = Collections.synchronizedList(new ArrayList<>());

        @BeforeEach
        void setUp() {
            config = TestConfigUtils.loadConfigTest().getTopics();
            config.getVectors().setEmbedBatchSize(1);
            dao = mock(TopicVectorsDAO.class);
            stubNoDiscovery(dao);
            client = mock(InferenceClient.class);
            var lockService = mock(LockService.class);
            var workspaces = mock(TopicWorkspaceService.class);
            var registry = mock(AiLabelTaskRegistry.class);
            clock = new TestClock(NOW);
            service = new TopicVectorsTickService(config, new AiLabelsConfig(), workspaces, dao, allDrained(), client,
                    lockService, registry, names(), noArrival(), new TestMeter().meter, clock);
            when(workspaces.eligibleWorkspaceIds()).thenReturn(List.of(WORKSPACE_ID));
            when(registry.find(TopicVectorsTickService.TASK)).thenReturn(java.util.Optional.of(task()));
            when(lockService.lockUsingToken(eq(TopicVectorsTickService.LONG_WINDOW_LOCK), any(Duration.class)))
                    .thenReturn(Mono.just(false));
            // The reads take any bounds and keys: these tests are about the calls between them.
            when(dao.findUnvectored(anyString(), eq(TopicVectorsTickService.TASK), any(Instant.class),
                    any(Instant.class), any(Instant.class), anyInt(), anyLong()))
                    .thenAnswer(invocation -> Flux.fromIterable(candidates));
            when(dao.findSummaries(anyString(), eq(TopicVectorsTickService.TASK), anyCollection(), anyLong()))
                    .thenAnswer(invocation -> Flux.fromIterable(candidates)
                            .map(candidate -> summary(candidate, 1, text(candidates.indexOf(candidate)))));
            when(dao.insert(anyList())).thenAnswer(invocation -> {
                List<TopicVector> vectors = invocation.getArgument(0);
                vectors.forEach(vector -> inserted.add(vector.traceId()));
                return Mono.just((long) vectors.size());
            });
            when(dao.findStatusKeys(anyString(), eq(TopicVectorsTickService.TASK), anyCollection(), anyLong()))
                    .thenAnswer(invocation -> {
                        Collection<TopicTraceKey> keys = invocation.getArgument(2);
                        return Flux.fromIterable(keys);
                    });
        }

        @Test
        @DisplayName("with embedConcurrency 3, three calls are in flight together and never a fourth")
        void neverMoreThanTheSetting() {
            config.getVectors().setEmbedConcurrency(3);
            traces(9);
            // Each call waits for two others: it only returns if three really are in flight at once.
            var together = new java.util.concurrent.CyclicBarrier(3);
            onEmbed(index -> {
                together.await(WAIT_SECONDS, TimeUnit.SECONDS);
                return embedded(List.of(text(index)));
            });

            var result = service.tick(NOW);

            assertThat(result.written()).isEqualTo(9);
            assertThat(mostInFlight.get()).isEqualTo(3);
            assertThat(inserted).containsExactlyElementsOf(traceIds());
        }

        @Test
        @DisplayName("vectors are inserted in chunk order when a later chunk's call answers first")
        void insertsInChunkOrder() {
            config.getVectors().setEmbedConcurrency(2);
            traces(4);
            var secondAnswered = new java.util.concurrent.CountDownLatch(1);
            onEmbed(index -> {
                if (index == 0) {
                    // The first chunk's call answers only after the second chunk's has.
                    assertThat(secondAnswered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
                }
                if (index == 1) {
                    secondAnswered.countDown();
                }
                return embedded(List.of(text(index)));
            });

            assertThat(service.tick(NOW).written()).isEqualTo(4);

            assertThat(inserted).containsExactlyElementsOf(traceIds());
        }

        @Test
        @DisplayName("after a back-off the calls in flight end and their vectors are inserted, and no new call starts")
        void backOffLetsCallsInFlightEnd() {
            config.getVectors().setEmbedConcurrency(2);
            traces(5);
            var secondStarted = new java.util.concurrent.CountDownLatch(1);
            var firstFailed = new java.util.concurrent.CountDownLatch(1);
            onEmbed(index -> {
                if (index == 0) {
                    // Fails while the second chunk's call is in flight.
                    assertThat(secondStarted.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
                    firstFailed.countDown();
                    throw inferenceException(503, "something_new", false);
                }
                secondStarted.countDown();
                assertThat(firstFailed.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
                return embedded(List.of(text(index)));
            });

            var result = service.tick(NOW);

            assertThat(called).containsExactlyInAnyOrder(text(0), text(1));
            assertThat(inserted).containsExactly(candidates.get(1).traceId());
            assertThat(result.written()).isOne();
            // The failed chunk and the three never called.
            assertThat(result.skipped()).isEqualTo(Map.of(TopicVectorsTickService.EMBED_FAILED, 4));
        }

        @Test
        @DisplayName("a rejected chunk is halved in its own turn: its halves are called one after the other, so the "
                + "calls in flight stay within the setting")
        void halvingStaysInItsTurn() {
            config.getVectors().setEmbedConcurrency(2);
            config.getVectors().setEmbedBatchSize(4);
            traces(16);
            var bad = text(5);
            // A call with the bad input is refused; any other answers after a moment, so calls overlap.
            when(client.embed(any(EmbedRequest.class), any(Instant.class))).thenAnswer(invocation -> {
                EmbedRequest request = invocation.getArgument(0);
                return Mono.fromCallable(() -> {
                    int now = inFlight.incrementAndGet();
                    mostInFlight.accumulateAndGet(now, Math::max);
                    try {
                        Thread.sleep(5);
                        if (request.inputs().contains(bad)) {
                            throw inferenceException(400, "invalid_request", false);
                        }
                        return embedded(request.inputs());
                    } finally {
                        inFlight.decrementAndGet();
                    }
                });
            });

            var result = service.tick(NOW);

            assertThat(result.written()).isEqualTo(15);
            assertThat(result.skipped()).isEqualTo(Map.of(TopicVectorsTickService.EMBED_FAILED, 1));
            assertThat(mostInFlight.get()).isLessThanOrEqualTo(2);
            var expected = new ArrayList<>(traceIds());
            expected.remove(5);
            assertThat(inserted).containsExactlyElementsOf(expected);
        }

        @Test
        @DisplayName("no call starts with less than the embed timeout left, and each call that starts gets all of it")
        void noCallNearTheBudgetEnd() {
            config.getVectors().setEmbedConcurrency(2);
            traces(4);
            // 15 s of the budget are left: two calls start together, each takes 10 s, none starts after them.
            clock.advance(Duration.ofSeconds(BUDGET_SECONDS - 15));
            var callStart = clock.instant();
            var deadlines = Collections.synchronizedList(new ArrayList<Instant>());
            var bothStarted = new java.util.concurrent.CyclicBarrier(2);
            when(client.embed(any(EmbedRequest.class), any(Instant.class))).thenAnswer(invocation -> {
                EmbedRequest request = invocation.getArgument(0);
                deadlines.add(invocation.getArgument(1));
                return Mono.fromCallable(() -> {
                    bothStarted.await(WAIT_SECONDS, TimeUnit.SECONDS);
                    // One at a time: the test clock's advance is a read and a write.
                    synchronized (clock) {
                        clock.advance(EMBED_TIMEOUT);
                    }
                    return embedded(request.inputs());
                });
            });

            var result = service.tick(NOW);

            assertThat(deadlines).containsExactly(callStart.plus(EMBED_TIMEOUT), callStart.plus(EMBED_TIMEOUT));
            assertThat(result.written()).isEqualTo(2);
            assertThat(result.skipped()).isEqualTo(Map.of(TopicVectorsTickService.OVER_BUDGET, 2));
        }

        @Test
        @DisplayName("an interrupt of the tick cancels the calls in flight and stops the tick")
        void interruptCancelsCallsInFlight() throws InterruptedException {
            config.getVectors().setEmbedConcurrency(2);
            traces(4);
            var started = new java.util.concurrent.CountDownLatch(2);
            var cancelled = new java.util.concurrent.CountDownLatch(2);
            // A call that never answers, whichever chunk it is.
            when(client.embed(any(EmbedRequest.class), any(Instant.class)))
                    .thenAnswer(invocation -> Mono.never().doOnSubscribe(subscription -> started.countDown())
                            .doOnCancel(cancelled::countDown));
            var failure = new AtomicReference<Throwable>();
            var tick = new Thread(() -> {
                try {
                    service.tick(NOW);
                } catch (RuntimeException exception) {
                    failure.set(exception);
                }
            });
            tick.start();
            assertThat(started.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

            tick.interrupt();

            tick.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            assertThat(tick.isAlive()).isFalse();
            assertThat(failure.get()).isNotNull();
            assertThat(cancelled.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(inserted).isEmpty();
        }

        /** What a call answers, by the index of its chunk's trace; may wait, and may throw the call's failure. */
        private interface Answer {
            EmbedResponse of(int index) throws Exception;
        }

        /** Every call, whichever its request and deadline, is counted while in flight and answered by {@code answer}. */
        private void onEmbed(Answer answer) {
            when(client.embed(any(EmbedRequest.class), any(Instant.class))).thenAnswer(invocation -> {
                EmbedRequest request = invocation.getArgument(0);
                var input = request.inputs().getFirst();
                return Mono.fromCallable(() -> {
                    called.add(input);
                    int now = inFlight.incrementAndGet();
                    mostInFlight.accumulateAndGet(now, Math::max);
                    try {
                        return answer.of(Integer.parseInt(input.substring("intent ".length())));
                    } finally {
                        inFlight.decrementAndGet();
                    }
                });
            });
        }

        private void traces(int count) {
            IntStream.range(0, count)
                    .forEach(i -> candidates.add(candidate(ID_GENERATOR.generateId(), 1, NOW.minusSeconds(600 - i))));
        }

        private List<UUID> traceIds() {
            return candidates.stream().map(TopicVectorCandidate::traceId).toList();
        }

        private static String text(int index) {
            return "intent " + index;
        }
    }

    /** A backfill table in which every project asked for has a drained row: the workspace reads serve them all. */
    static TopicVectorBackfillService allDrained() {
        var backfills = mock(TopicVectorBackfillService.class);
        when(backfills.findByKeys(anyString(), anyCollection())).thenAnswer(invocation -> {
            String workspaceId = invocation.getArgument(0);
            Collection<UUID> projectIds = invocation.getArgument(1);
            return projectIds.stream().collect(java.util.stream.Collectors.toMap(Function.identity(),
                    projectId -> TopicVectorBackfill.builder().workspaceId(workspaceId).projectId(projectId)
                            .startedAt(APPLIES_FROM).checkedAt(NOW).drainedAt(APPLIES_FROM).lastDoneAt(NOW).build()));
        });
        return backfills;
    }

    /** The discovery finds no project, whatever the workspace and the window. */
    static void stubNoDiscovery(TopicVectorsDAO dao) {
        when(dao.findBackfillProjects(anyString(), anyString(), any(Instant.class), any(Instant.class), anyLong()))
                .thenReturn(Flux.empty());
        when(dao.countTracesWithVectorsByProject(anyString(), any(Instant.class), anyLong()))
                .thenReturn(Mono.just(Map.of()));
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
        private final Map<String, Consumer<ObservableDoubleMeasurement>> gauges = new ConcurrentHashMap<>();

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
                String name = invocation.getArgument(0);
                // Keeps the callback the service registers, whichever it is, to observe it.
                doAnswer(build -> {
                    gauges.put(name, build.getArgument(0));
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

        /** One scrape of the oldest-unvectored gauge: its value per workspace. */
        Map<String, Double> observeGauge() {
            return observeGauge("topics.vectors.oldest_unvectored_seconds");
        }

        /** One scrape of the gauge {@code name}: its value per workspace. */
        Map<String, Double> observeGauge(String name) {
            var values = new HashMap<String, Double>();
            observe(name, (value, attributes) -> values.put(attributes.get(stringKey("workspace_id")), value));
            return values;
        }

        /** One scrape of the gauge: the attributes of its value per workspace. */
        Map<String, Attributes> observeGaugeAttributes() {
            var values = new HashMap<String, Attributes>();
            observe("topics.vectors.oldest_unvectored_seconds",
                    (value, attributes) -> values.put(attributes.get(stringKey("workspace_id")), attributes));
            return values;
        }

        private void observe(String name, java.util.function.BiConsumer<Double, Attributes> recorded) {
            var measurement = mock(ObservableDoubleMeasurement.class);
            // Hands on whatever the scrape publishes, so this takes any value and attributes.
            doAnswer(invocation -> {
                recorded.accept(invocation.getArgument(0), invocation.getArgument(1));
                return null;
            }).when(measurement).record(anyDouble(), any(Attributes.class));
            gauges.get(name).accept(measurement);
        }
    }

    /** Workspace names as the tests expect them: {@code name-} and the id, never failing. */
    /** Topic on arrival, turned off: the tests of the tick without it. */
    static TopicsArrivalService noArrival() {
        var topics = new TopicsConfig();
        var arrival = new TopicsConfig.ArrivalConfig();
        arrival.setEnabled(false);
        topics.setArrival(arrival);
        return new TopicsArrivalService(topics, mock(TransactionTemplate.class), mock(TraceTopicsDAO.class),
                new KMeansTopicAlgorithm(), OpenTelemetry.noop().getMeter("test"));
    }

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
