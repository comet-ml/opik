package com.comet.opik.domain.topics;

import com.comet.opik.domain.ailabels.AiLabelEndpoint;
import com.comet.opik.domain.ailabels.AiLabelTask;
import com.comet.opik.domain.ailabels.AiLabelTaskRegistry;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.inference.EmbedRequest;
import com.comet.opik.infrastructure.inference.EmbedResponse;
import com.comet.opik.infrastructure.inference.EmbedResult;
import com.comet.opik.infrastructure.inference.InferenceClient;
import com.comet.opik.infrastructure.lock.LockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import ru.vyarus.guicey.jdbi3.tx.TxConfig;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
            var template = mock(TransactionTemplate.class);
            var registry = mock(AiLabelTaskRegistry.class);
            when(template.inTransaction(any(TxConfig.class), any())).thenReturn(List.of(WORKSPACE_ID));
            when(registry.find(TopicVectorsTickService.TASK)).thenReturn(java.util.Optional.of(task()));
            when(lockService.bestEffortLock(any(), any(), any(), any(), any(), anyBoolean()))
                    .thenReturn(Mono.empty());
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
            service = new TopicVectorsTickService(config, template, dao, client, lockService, registry);
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
        @DisplayName("the short and the long window never start before applies_from")
        void windowBounds() {
            stub(List.of(), candidate -> "");
            config.getVectors().setLongWindow(io.dropwizard.util.Duration.days(30));

            service.tick(NOW);
            verify(dao).findUnvectored(eq(WORKSPACE_ID), eq(TopicVectorsTickService.TASK),
                    eq(NOW.minus(Duration.ofHours(24))), eq(NOW), eq(5_000), anyLong());

            when(lockService.bestEffortLock(any(), any(), any(), any(), any(), anyBoolean()))
                    .thenReturn(Mono.just(true));
            var result = service.tick(NOW);
            assertThat(result.longWindow()).isTrue();
            verify(dao).findUnvectored(eq(WORKSPACE_ID), eq(TopicVectorsTickService.TASK), eq(APPLIES_FROM),
                    eq(NOW), eq(5_000), anyLong());
        }

        @Test
        @DisplayName("before the task applies, nothing is read")
        void beforeAppliesFrom() {
            var result = service.tick(APPLIES_FROM.minusSeconds(1));

            assertThat(result.written()).isZero();
            verify(dao, never()).findUnvectored(any(), any(), any(), any(), anyInt(), anyLong());
        }

        private void stub(List<TopicVectorCandidate> candidates,
                java.util.function.Function<TopicVectorCandidate, String> text) {
            when(dao.findUnvectored(anyString(), anyString(), any(), any(), anyInt(), anyLong()))
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
}
