package com.comet.opik.domain.topics;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.WorkspaceNameService;
import com.comet.opik.domain.topics.TopicProject.RunError;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.inference.GenerateRequest;
import com.comet.opik.infrastructure.inference.InferenceClient;
import com.comet.opik.infrastructure.inference.InferenceException;
import com.comet.opik.infrastructure.inference.InferenceJson;
import com.comet.opik.infrastructure.inference.ItemResponse.GenerateResponse;
import com.comet.opik.infrastructure.inference.ItemResponse.GenerateResult;
import com.comet.opik.infrastructure.inference.Usage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.comet.opik.domain.topics.TopicsMetricsReader.sum;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The rebuild service driving a nightly update's {@link NightStep}s, with opik-inference stubbed: the calls in the
 * order the algorithm asks, each with its kind's deadline and its own request id, a bad answer handed on as null, a
 * failed call ending the night.
 */
@DisplayName("topics rebuild service: nightly update steps")
class TopicsRebuildServiceNightTest {

    private static final TimeBasedEpochGenerator IDS = Generators.timeBasedEpochGenerator();
    private static final LocalDate NIGHT = LocalDate.parse("2026-10-20");

    private final String workspaceId = IDS.generate().toString();
    private final String workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
    private final UUID projectId = IDS.generate();
    private final UUID runId = IDS.generate();

    private InMemoryMetricReader metricReader;
    private InferenceClient client;
    private TopicsConfig config;
    private TopicRun run;
    private TopicsRebuildService.RunStats stats;
    /** What the client got, in order: the request and its deadline. */
    private final List<Map.Entry<GenerateRequest, Instant>> calls = Collections.synchronizedList(new ArrayList<>());
    /** What the stub algorithm's steps got, in order; an empty Optional for a null answer. */
    private final List<Optional<JsonNode>> answers = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() {
        metricReader = InMemoryMetricReader.create();
        client = mock(InferenceClient.class);
        config = TestConfigUtils.loadConfigTest().getTopics();
        run = TopicRun.of(runId, workspaceId, workspaceName, projectId, NIGHT, config.getBuild().getWindowDays());
        stats = TopicsRebuildService.RunStats.of(run);
    }

    @Test
    @DisplayName("each call the night asks for is made in turn with its kind's deadline and its tag in the request id; "
            + "an answer that is not an object reaches the algorithm as null and counts bad_answer")
    void callsInTurn() {
        answer(Map.of(
                "naming", Mono.just(object("{\"step\": 1}")),
                "verify_naming", Mono.just(TextNode.valueOf("not an object")),
                "naming_new", Mono.just(object("{\"step\": 3}"))));
        var started = Instant.now();

        var update = service().update(run, stats, state(), window()).block();
        var ended = Instant.now();

        assertThat(calls).extracting(call -> call.getKey().requestId())
                .containsExactly(runId + ":naming", runId + ":verify_naming", runId + ":naming_new");
        assertThat(calls).extracting(call -> call.getKey().maxTokens()).containsExactly(11, 12, 13);
        deadline(calls.getFirst().getValue(), started, ended, config.getBuild().getNamingDeadline().toJavaDuration());
        deadline(calls.get(1).getValue(), started, ended, config.getBuild().getVerifyDeadline().toJavaDuration());
        deadline(calls.get(2).getValue(), started, ended, config.getBuild().getNamingDeadline().toJavaDuration());
        assertThat(answers).containsExactly(Optional.of(object("{\"step\": 1}")), Optional.empty(),
                Optional.of(object("{\"step\": 3}")));
        assertThat(update.labels()).containsExactly(0, TopicAlgorithm.OTHER);
        assertThat(sum(metricReader, "topics.llm.calls", "call", "naming", "outcome", "ok")).isEqualTo(2);
        assertThat(sum(metricReader, "topics.llm.calls", "call", "verify", "outcome", "bad_answer")).isEqualTo(1);
        assertThat(sum(metricReader, "topics.llm.calls")).isEqualTo(3);
        assertThat(stats.stats().get("night_stat").asInt()).isEqualTo(3);
        assertThat(stats.stats().get("llm_calls").asInt()).isEqualTo(3);
        assertThat(stats.stats().get("llm_input_tokens").asInt()).isEqualTo(3_000);
        assertThat(stats.seconds().has("llm")).isTrue();
    }

    @Test
    @DisplayName("a failed call errors with its code and the night asks nothing more")
    void failedCall() {
        var failure = mock(InferenceException.class);
        when(failure.getCode()).thenReturn("upstream_error");
        answer(Map.of(
                "naming", Mono.just(object("{\"step\": 1}")),
                "verify_naming", Mono.error(failure),
                "naming_new", Mono.just(object("{\"step\": 3}"))));

        assertThatThrownBy(() -> service().update(run, stats, state(), window()).block())
                .isInstanceOfSatisfying(TopicsRunException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(RunError.LLM_ERROR));
        assertThat(calls).extracting(call -> call.getKey().requestId())
                .containsExactly(runId + ":naming", runId + ":verify_naming");
        assertThat(answers).containsExactly(Optional.of(object("{\"step\": 1}")));
        assertThat(sum(metricReader, "topics.llm.calls", "call", "verify", "outcome", "error")).isEqualTo(1);
        assertThat(stats.stats().has("night_stat")).isFalse();
    }

    @Test
    @DisplayName("a call past its deadline fails with llm_timeout")
    void timedOutCall() {
        var failure = mock(InferenceException.class);
        when(failure.getCode()).thenReturn(InferenceException.OUT_OF_TIME);
        when(failure.isOutOfTime()).thenReturn(true);
        answer(Map.of("naming", Mono.error(failure)));

        assertThatThrownBy(() -> service().update(run, stats, state(), window()).block())
                .isInstanceOfSatisfying(TopicsRunException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(RunError.LLM_TIMEOUT));
        assertThat(answers).isEmpty();
        assertThat(sum(metricReader, "topics.llm.calls", "call", "naming", "outcome", "timeout")).isEqualTo(1);
    }

    /** The client answers each call by the tag at the end of its request id. */
    private void answer(Map<String, Mono<JsonNode>> byTag) {
        when(client.generate(any(), any())).thenAnswer(invocation -> {
            GenerateRequest request = invocation.getArgument(0);
            calls.add(Map.entry(request, invocation.getArgument(1)));
            var tag = request.requestId().substring(request.requestId().indexOf(':') + 1);
            return byTag.get(tag).map(TopicsRebuildServiceNightTest::response);
        });
    }

    private static void deadline(Instant deadline, Instant started, Instant ended, Duration expected) {
        assertThat(deadline).isBetween(started.plus(expected), ended.plus(expected));
    }

    private TopicsRebuildService service() {
        var metrics = TopicsMetricsReader.metrics(metricReader);
        return new TopicsRebuildService(config, mock(TopicWindowReader.class), new TopicsLlm(config, client, metrics),
                mock(TopicRunStore.class), mock(IdGenerator.class), metrics, mock(WorkspaceNameService.class),
                new ThreeCalls());
    }

    /** A night of three calls, naming, verify, naming_new, whatever they answer; it records each answer. */
    private final class ThreeCalls implements TopicAlgorithm {

        @Override
        public Clustering cluster(TopicWindow window, int maxTopics) {
            throw new UnsupportedOperationException();
        }

        @Override
        public NightStep<TopicUpdate> update(NightState state, NightWindow window, int maxTopics) {
            return ask(LlmCall.NAMING, ForcedBirth.NAMING_TAG, 11)
                    .then(__ -> ask(LlmCall.VERIFY, ForcedBirth.VERIFY_TAG, 12))
                    .then(__ -> ask(LlmCall.NAMING, ForcedBirth.NAMING_NEW_TAG, 13))
                    .map(__ -> TopicUpdate.builder()
                            .before(new int[]{TopicAlgorithm.OTHER, TopicAlgorithm.OTHER})
                            .labels(new int[]{0, TopicAlgorithm.OTHER})
                            .stats(InferenceJson.MAPPER.createObjectNode().put("night_stat", answers.size()))
                            .build());
        }

        private NightStep<Boolean> ask(LlmCall call, String tag, int maxTokens) {
            var request = LlmRequest.builder().call(call).tag(tag).prompt("prompt " + tag)
                    .schema(InferenceJson.MAPPER.createObjectNode()).maxTokens(maxTokens).build();
            return NightStep.ask(request, answer -> {
                answers.add(Optional.ofNullable(answer));
                return NightStep.done(true);
            });
        }

        @Override
        public TopicLabeller labeller(List<Topic> topics, PrototypeVectors otherVectors) {
            return vectors -> new UUID[vectors.size()];
        }
    }

    private static NightState state() {
        return NightState.builder()
                .topics(List.of())
                .otherPrototypes(List.of())
                .rebuilds(0)
                .night(NIGHT)
                .ids(IDS::generate)
                .build();
    }

    private static NightWindow window() {
        var night = NIGHT.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        return NightWindow.builder()
                .texts(new String[0])
                .vectors(new float[0])
                .createdAt(new Instant[0])
                .from(night.minus(Duration.ofDays(30)))
                .night(night)
                .dimensions(2)
                .build();
    }

    private static JsonNode object(String json) {
        try {
            return InferenceJson.MAPPER.readTree(json);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static GenerateResponse response(JsonNode output) {
        return GenerateResponse.builder().requestId("r-1").usage(new Usage(1_000, 200)).latencyMs(1_500)
                .result(new GenerateResult(output)).build();
    }
}
