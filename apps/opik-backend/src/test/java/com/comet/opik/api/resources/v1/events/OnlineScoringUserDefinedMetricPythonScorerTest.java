package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Trace;
import com.comet.opik.api.events.TraceToScoreUserDefinedMetricPython;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.evaluators.python.PythonEvaluatorService;
import com.comet.opik.domain.evaluators.python.PythonScoreResult;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonReactiveClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorUserDefinedMetricPython.UserDefinedMetricPythonCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OnlineScoringUserDefinedMetricPythonScorer Tests")
class OnlineScoringUserDefinedMetricPythonScorerTest {

    @Mock
    private OnlineScoringConfig onlineScoringConfig;

    @Mock
    private ServiceTogglesConfig serviceTogglesConfig;

    @Mock
    private RedissonReactiveClient redissonClient;

    @Mock
    private FeedbackScoreService feedbackScoreService;

    @Mock
    private TraceService traceService;

    @Mock
    private PythonEvaluatorService pythonEvaluatorService;

    private OnlineScoringUserDefinedMetricPythonScorer scorer;
    private MockedStatic<UserFacingLoggingFactory> mockedFactory;

    @BeforeEach
    void setUp() {
        mockedFactory = mockStatic(UserFacingLoggingFactory.class);
        mockedFactory.when(() -> UserFacingLoggingFactory.getLogger(any(Class.class)))
                .thenReturn(mock(org.slf4j.Logger.class));

        OnlineScoringConfig.StreamConfiguration streamConfig = new OnlineScoringConfig.StreamConfiguration();
        streamConfig.setScorer("user_defined_metric_python");
        streamConfig.setStreamName("stream_scoring_user_defined_metric_python");
        streamConfig.setCodec("java");
        streamConfig.setPoolingInterval(Duration.milliseconds(500));
        streamConfig.setLongPollingDuration(Duration.seconds(5));
        streamConfig.setConsumerBatchSize(10);
        streamConfig.setClaimIntervalRatio(10);
        streamConfig.setPendingMessageDuration(Duration.minutes(10));
        streamConfig.setMaxRetries(3);

        lenient().when(onlineScoringConfig.getStreams()).thenReturn(List.of(streamConfig));
        lenient().when(onlineScoringConfig.getConsumerGroupName()).thenReturn("online_scoring");
        lenient().when(onlineScoringConfig.getConsumerBatchSize()).thenReturn(10);
        lenient().when(onlineScoringConfig.getPoolingInterval()).thenReturn(Duration.milliseconds(500));
        lenient().when(onlineScoringConfig.getLongPollingDuration()).thenReturn(Duration.seconds(5));
        lenient().when(onlineScoringConfig.getClaimIntervalRatio()).thenReturn(10);
        lenient().when(onlineScoringConfig.getPendingMessageDuration()).thenReturn(Duration.minutes(10));
        lenient().when(onlineScoringConfig.getMaxRetries()).thenReturn(3);

        scorer = new OnlineScoringUserDefinedMetricPythonScorer(
                onlineScoringConfig,
                serviceTogglesConfig,
                redissonClient,
                feedbackScoreService,
                traceService,
                pythonEvaluatorService);
    }

    @AfterEach
    void tearDown() {
        if (mockedFactory != null) {
            mockedFactory.close();
        }
    }

    @Nested
    @DisplayName("Service Toggle Tests")
    class ServiceToggleTests {

        @Test
        @DisplayName("Should start when Python evaluator is enabled")
        void shouldStartWhenPythonEvaluatorEnabled() {
            // Given
            when(serviceTogglesConfig.isPythonEvaluatorEnabled()).thenReturn(true);
            org.redisson.api.RStreamReactive<Object, Object> mockStream = mock(org.redisson.api.RStreamReactive.class);
            lenient().when(redissonClient.getStream(any(org.redisson.api.options.PlainOptions.class)))
                    .thenReturn(mockStream);
            lenient().when(mockStream.createGroup(any())).thenReturn(reactor.core.publisher.Mono.empty());

            // When
            scorer.start();

            // Then - no exception means start was successful
        }

        @Test
        @DisplayName("Should not start when Python evaluator is disabled")
        void shouldNotStartWhenPythonEvaluatorDisabled() {
            // Given
            when(serviceTogglesConfig.isPythonEvaluatorEnabled()).thenReturn(false);

            // When
            scorer.start();

            // Then
            verify(redissonClient, never()).getStream(any(org.redisson.api.options.PlainOptions.class));
        }
    }

    @Nested
    @DisplayName("Scoring Tests")
    class ScoringTests {

        @Test
        @DisplayName("Should score trace and store results")
        void shouldScoreTraceAndStoreResults() {
            // Given
            UUID traceId = UUID.randomUUID();
            UUID projectId = UUID.randomUUID();
            UUID ruleId = UUID.randomUUID();

            Trace trace = Trace.builder()
                    .id(traceId)
                    .projectId(projectId)
                    .projectName("test-project")
                    .build();

            UserDefinedMetricPythonCode code = new UserDefinedMetricPythonCode(
                    "def score(input, output): return [ScoreResult(name='test_score', value=0.95, reason='test')]",
                    Map.of("input", "input.input", "output", "output.output"));

            TraceToScoreUserDefinedMetricPython message = TraceToScoreUserDefinedMetricPython.builder()
                    .trace(trace)
                    .ruleId(ruleId)
                    .ruleName("test-rule")
                    .code(code)
                    .workspaceId("workspace-123")
                    .userName("test-user")
                    .build();

            PythonScoreResult scoreResult = PythonScoreResult.builder()
                    .name("test_score")
                    .value(BigDecimal.valueOf(0.95))
                    .reason("test reason")
                    .build();

            when(pythonEvaluatorService.evaluate(any(String.class), any(Map.class)))
                    .thenReturn(List.of(scoreResult));
            when(feedbackScoreService.scoreBatchOfTraces(any(List.class)))
                    .thenReturn(reactor.core.publisher.Mono.empty());

            // When
            scorer.score(message);

            // Then
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<FeedbackScoreBatchItem>> scoresCaptor = ArgumentCaptor.forClass(List.class);
            verify(feedbackScoreService).scoreBatchOfTraces(scoresCaptor.capture());

            List<FeedbackScoreBatchItem> scores = scoresCaptor.getValue();
            assertThat(scores).hasSize(1);
            assertThat(scores.get(0).id()).isEqualTo(traceId);
            assertThat(scores.get(0).projectId()).isEqualTo(projectId);
            assertThat(scores.get(0).projectName()).isEqualTo("test-project");
            assertThat(scores.get(0).name()).isEqualTo("test_score");
            assertThat(scores.get(0).value()).isEqualByComparingTo(BigDecimal.valueOf(0.95));
            assertThat(scores.get(0).reason()).isEqualTo("test reason");
            assertThat(scores.get(0).source()).isEqualTo(ScoreSource.ONLINE_SCORING);
        }

        @Test
        @DisplayName("Should pass full trace sections as objects to Python evaluator")
        void shouldPassFullTraceSectionsAsObjects() {
            // Given
            JsonNode inputNode = JsonUtils.getMapper().valueToTree(Map.of("key", "value"));
            JsonNode outputNode = JsonUtils.getMapper().valueToTree(Map.of("result", "success"));
            JsonNode metadataNode = JsonUtils.getMapper().valueToTree(Map.of("meta", "data"));

            Trace trace = Trace.builder()
                    .id(UUID.randomUUID())
                    .projectId(UUID.randomUUID())
                    .projectName("test-project")
                    .input(inputNode)
                    .output(outputNode)
                    .metadata(metadataNode)
                    .build();

            UserDefinedMetricPythonCode code = new UserDefinedMetricPythonCode(
                    "def score(input, output): return [...]",
                    null);

            TraceToScoreUserDefinedMetricPython message = TraceToScoreUserDefinedMetricPython.builder()
                    .trace(trace)
                    .ruleId(UUID.randomUUID())
                    .ruleName("test-rule")
                    .code(code)
                    .workspaceId("workspace-123")
                    .userName("test-user")
                    .build();

            when(pythonEvaluatorService.evaluate(any(String.class), any(Map.class)))
                    .thenReturn(List.of());
            when(feedbackScoreService.scoreBatchOfTraces(any(List.class)))
                    .thenReturn(reactor.core.publisher.Mono.empty());

            // When
            scorer.score(message);

            // Then
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
            verify(pythonEvaluatorService).evaluate(any(String.class), dataCaptor.capture());

            Map<String, Object> data = dataCaptor.getValue();
            assertThat(data).containsKey("input");
            assertThat(data).containsKey("output");
            assertThat(data).containsKey("metadata");
            assertThat(data.get("input")).isEqualTo(Map.of("key", "value"));
            assertThat(data.get("output")).isEqualTo(Map.of("result", "success"));
            assertThat(data.get("metadata")).isEqualTo(Map.of("meta", "data"));
        }

        @Test
        @DisplayName("Should pass complex nested objects to Python evaluator")
        void shouldPassComplexNestedObjectsToPythonEvaluator() {
            // Given - complex nested structures that the Python evaluator should receive as-is
            var inputData = Map.of(
                    "messages", List.of(
                            Map.of("role", "user", "content", "hello"),
                            Map.of("role", "assistant", "content", "hi there")),
                    "config", Map.of("temperature", 0.7, "max_tokens", 100));
            var outputData = Map.of(
                    "choices", List.of(Map.of("text", "response", "score", 0.95)),
                    "usage", Map.of("prompt_tokens", 10, "completion_tokens", 20));

            JsonNode inputNode = JsonUtils.getMapper().valueToTree(inputData);
            JsonNode outputNode = JsonUtils.getMapper().valueToTree(outputData);

            Trace trace = Trace.builder()
                    .id(UUID.randomUUID())
                    .projectId(UUID.randomUUID())
                    .projectName("test-project")
                    .input(inputNode)
                    .output(outputNode)
                    .build();

            UserDefinedMetricPythonCode code = new UserDefinedMetricPythonCode(
                    "def score(input, output): return [...]",
                    null);

            TraceToScoreUserDefinedMetricPython message = TraceToScoreUserDefinedMetricPython.builder()
                    .trace(trace)
                    .ruleId(UUID.randomUUID())
                    .ruleName("test-rule")
                    .code(code)
                    .workspaceId("workspace-123")
                    .userName("test-user")
                    .build();

            when(pythonEvaluatorService.evaluate(any(String.class), any(Map.class)))
                    .thenReturn(List.of());
            when(feedbackScoreService.scoreBatchOfTraces(any(List.class)))
                    .thenReturn(reactor.core.publisher.Mono.empty());

            // When
            scorer.score(message);

            // Then
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
            verify(pythonEvaluatorService).evaluate(any(String.class), dataCaptor.capture());

            Map<String, Object> data = dataCaptor.getValue();
            assertThat(data).hasSize(2);

            // Verify nested structures are preserved as complex objects, not flattened to strings
            @SuppressWarnings("unchecked")
            var inputMap = (Map<String, Object>) data.get("input");
            assertThat(inputMap).containsKey("messages");
            assertThat(inputMap).containsKey("config");
            @SuppressWarnings("unchecked")
            var messages = (List<Map<String, Object>>) inputMap.get("messages");
            assertThat(messages).hasSize(2);
            assertThat(messages.get(0)).containsEntry("role", "user");

            @SuppressWarnings("unchecked")
            var outputMap = (Map<String, Object>) data.get("output");
            assertThat(outputMap).containsKey("choices");
            @SuppressWarnings("unchecked")
            var choices = (List<Map<String, Object>>) outputMap.get("choices");
            assertThat(choices).hasSize(1);
            assertThat(choices.get(0)).containsEntry("text", "response");
            assertThat(choices.get(0)).containsEntry("score", 0.95);
        }

        @Test
        @DisplayName("Should pass array values as objects to Python evaluator")
        void shouldPassArrayValuesAsObjectsToPythonEvaluator() {
            // Given - input/output are arrays at the top level
            JsonNode inputNode = JsonUtils.getMapper().valueToTree(List.of("item1", "item2", "item3"));
            JsonNode outputNode = JsonUtils.getMapper().valueToTree(
                    List.of(Map.of("label", "positive", "score", 0.9), Map.of("label", "negative", "score", 0.1)));

            Trace trace = Trace.builder()
                    .id(UUID.randomUUID())
                    .projectId(UUID.randomUUID())
                    .projectName("test-project")
                    .input(inputNode)
                    .output(outputNode)
                    .build();

            UserDefinedMetricPythonCode code = new UserDefinedMetricPythonCode(
                    "def score(input, output): return [...]",
                    null);

            TraceToScoreUserDefinedMetricPython message = TraceToScoreUserDefinedMetricPython.builder()
                    .trace(trace)
                    .ruleId(UUID.randomUUID())
                    .ruleName("test-rule")
                    .code(code)
                    .workspaceId("workspace-123")
                    .userName("test-user")
                    .build();

            when(pythonEvaluatorService.evaluate(any(String.class), any(Map.class)))
                    .thenReturn(List.of());
            when(feedbackScoreService.scoreBatchOfTraces(any(List.class)))
                    .thenReturn(reactor.core.publisher.Mono.empty());

            // When
            scorer.score(message);

            // Then
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
            verify(pythonEvaluatorService).evaluate(any(String.class), dataCaptor.capture());

            Map<String, Object> data = dataCaptor.getValue();
            assertThat(data).hasSize(2);
            assertThat(data.get("input")).isEqualTo(List.of("item1", "item2", "item3"));

            @SuppressWarnings("unchecked")
            var outputList = (List<Map<String, Object>>) data.get("output");
            assertThat(outputList).hasSize(2);
            assertThat(outputList.get(0)).containsEntry("label", "positive");
            assertThat(outputList.get(0)).containsEntry("score", 0.9);
        }
    }
}
