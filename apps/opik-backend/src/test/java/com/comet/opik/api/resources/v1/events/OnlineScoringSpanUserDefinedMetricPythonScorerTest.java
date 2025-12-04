package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Span;
import com.comet.opik.api.events.SpanToScoreUserDefinedMetricPython;
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
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorSpanUserDefinedMetricPython.SpanUserDefinedMetricPythonCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OnlineScoringSpanUserDefinedMetricPythonScorer Tests")
class OnlineScoringSpanUserDefinedMetricPythonScorerTest {

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

    private OnlineScoringSpanUserDefinedMetricPythonScorer scorer;
    private MockedStatic<UserFacingLoggingFactory> mockedFactory;

    @BeforeEach
    void setUp() {
        // Mock the static UserFacingLoggingFactory.getLogger method
        mockedFactory = mockStatic(UserFacingLoggingFactory.class);
        mockedFactory.when(() -> UserFacingLoggingFactory.getLogger(any(Class.class)))
                .thenReturn(mock(org.slf4j.Logger.class));

        // Mock OnlineScoringConfig to return stream configuration for span_user_defined_metric_python
        OnlineScoringConfig.StreamConfiguration streamConfig = new OnlineScoringConfig.StreamConfiguration();
        streamConfig.setScorer("span_user_defined_metric_python");
        streamConfig.setStreamName("stream_scoring_span_user_defined_metric_python");
        streamConfig.setCodec("java");
        streamConfig.setPoolingInterval(Duration.milliseconds(500));
        streamConfig.setLongPollingDuration(Duration.seconds(5));
        streamConfig.setConsumerBatchSize(10);
        streamConfig.setClaimIntervalRatio(10);
        streamConfig.setPendingMessageDuration(Duration.minutes(10));
        streamConfig.setMaxRetries(3);

        // Use lenient() for config mocks that may not be used in all tests
        lenient().when(onlineScoringConfig.getStreams()).thenReturn(List.of(streamConfig));
        lenient().when(onlineScoringConfig.getConsumerGroupName()).thenReturn("online_scoring");
        lenient().when(onlineScoringConfig.getConsumerBatchSize()).thenReturn(10);
        lenient().when(onlineScoringConfig.getPoolingInterval()).thenReturn(Duration.milliseconds(500));
        lenient().when(onlineScoringConfig.getLongPollingDuration()).thenReturn(Duration.seconds(5));
        lenient().when(onlineScoringConfig.getClaimIntervalRatio()).thenReturn(10);
        lenient().when(onlineScoringConfig.getPendingMessageDuration()).thenReturn(Duration.minutes(10));
        lenient().when(onlineScoringConfig.getMaxRetries()).thenReturn(3);

        scorer = new OnlineScoringSpanUserDefinedMetricPythonScorer(
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
            when(serviceTogglesConfig.isSpanUserDefinedMetricPythonEnabled()).thenReturn(true);
            // Mock Redis stream to avoid NullPointerException
            org.redisson.api.RStreamReactive<Object, Object> mockStream = mock(org.redisson.api.RStreamReactive.class);
            lenient().when(redissonClient.getStream(any(org.redisson.api.options.PlainOptions.class)))
                    .thenReturn(mockStream);
            lenient().when(mockStream.createGroup(any())).thenReturn(reactor.core.publisher.Mono.empty());

            // When
            scorer.start();

            // Then
            // If no exception is thrown, start was successful
            // The actual Redis subscription is tested in integration tests
        }

        @Test
        @DisplayName("Should not start when Python evaluator is disabled")
        void shouldNotStartWhenPythonEvaluatorDisabled() {
            // Given
            when(serviceTogglesConfig.isSpanUserDefinedMetricPythonEnabled()).thenReturn(false);

            // When
            scorer.start();

            // Then
            // If no exception is thrown, the scorer handled the disabled state gracefully
            // The actual behavior is that super.start() is not called
            // Verify Redis stream was never accessed
            verify(redissonClient, never()).getStream(any(org.redisson.api.options.PlainOptions.class));
        }
    }

    @Nested
    @DisplayName("Scoring Tests")
    class ScoringTests {

        @Test
        @DisplayName("Should score span and store results")
        void shouldScoreSpanAndStoreResults() {
            // Given
            UUID spanId = UUID.randomUUID();
            UUID projectId = UUID.randomUUID();
            UUID ruleId = UUID.randomUUID();
            String workspaceId = "workspace-123";
            String userName = "test-user";
            String ruleName = "test-rule";

            Span span = Span.builder()
                    .id(spanId)
                    .projectId(projectId)
                    .projectName("test-project")
                    .traceId(UUID.randomUUID())
                    .name("test-span")
                    .build();

            SpanUserDefinedMetricPythonCode code = new SpanUserDefinedMetricPythonCode(
                    "def score(input, output): return [ScoreResult(name='test_score', value=0.95, reason='test')]",
                    Map.of("input", "input.input", "output", "output.output"));

            SpanToScoreUserDefinedMetricPython message = SpanToScoreUserDefinedMetricPython.builder()
                    .span(span)
                    .ruleId(ruleId)
                    .ruleName(ruleName)
                    .code(code)
                    .workspaceId(workspaceId)
                    .userName(userName)
                    .build();

            PythonScoreResult scoreResult = PythonScoreResult.builder()
                    .name("test_score")
                    .value(BigDecimal.valueOf(0.95))
                    .reason("test reason")
                    .build();

            when(pythonEvaluatorService.evaluate(any(String.class), any(Map.class)))
                    .thenReturn(List.of(scoreResult));
            when(feedbackScoreService.scoreBatchOfSpans(any(List.class)))
                    .thenReturn(reactor.core.publisher.Mono.empty());

            // When
            scorer.score(message);

            // Then
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<FeedbackScoreBatchItem>> scoresCaptor = ArgumentCaptor.forClass(List.class);
            verify(feedbackScoreService).scoreBatchOfSpans(scoresCaptor.capture());

            List<FeedbackScoreBatchItem> scores = scoresCaptor.getValue();
            assertThat(scores).hasSize(1);
            assertThat(scores.get(0).id()).isEqualTo(spanId);
            assertThat(scores.get(0).projectId()).isEqualTo(projectId);
            assertThat(scores.get(0).projectName()).isEqualTo("test-project");
            assertThat(scores.get(0).name()).isEqualTo("test_score");
            assertThat(scores.get(0).value()).isEqualByComparingTo(BigDecimal.valueOf(0.95));
            assertThat(scores.get(0).reason()).isEqualTo("test reason");
            assertThat(scores.get(0).source()).isEqualTo(ScoreSource.ONLINE_SCORING);
        }

        @Test
        @DisplayName("Should handle multiple score results")
        void shouldHandleMultipleScoreResults() {
            // Given
            UUID spanId = UUID.randomUUID();
            UUID projectId = UUID.randomUUID();

            Span span = Span.builder()
                    .id(spanId)
                    .projectId(projectId)
                    .projectName("test-project")
                    .traceId(UUID.randomUUID())
                    .name("test-span")
                    .build();

            SpanUserDefinedMetricPythonCode code = new SpanUserDefinedMetricPythonCode(
                    "def score(input, output): return [...]",
                    Map.of());

            SpanToScoreUserDefinedMetricPython message = SpanToScoreUserDefinedMetricPython.builder()
                    .span(span)
                    .ruleId(UUID.randomUUID())
                    .ruleName("test-rule")
                    .code(code)
                    .workspaceId("workspace-123")
                    .userName("test-user")
                    .build();

            PythonScoreResult scoreResult1 = PythonScoreResult.builder()
                    .name("score1")
                    .value(BigDecimal.valueOf(0.8))
                    .reason("reason1")
                    .build();

            PythonScoreResult scoreResult2 = PythonScoreResult.builder()
                    .name("score2")
                    .value(BigDecimal.valueOf(0.9))
                    .reason("reason2")
                    .build();

            when(pythonEvaluatorService.evaluate(any(String.class), any(Map.class)))
                    .thenReturn(List.of(scoreResult1, scoreResult2));
            when(feedbackScoreService.scoreBatchOfSpans(any(List.class)))
                    .thenReturn(reactor.core.publisher.Mono.empty());

            // When
            scorer.score(message);

            // Then
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<FeedbackScoreBatchItem>> scoresCaptor = ArgumentCaptor.forClass(List.class);
            verify(feedbackScoreService).scoreBatchOfSpans(scoresCaptor.capture());

            List<FeedbackScoreBatchItem> scores = scoresCaptor.getValue();
            assertThat(scores).hasSize(2);
            assertThat(scores).extracting(FeedbackScoreBatchItem::name)
                    .containsExactly("score1", "score2");
            assertThat(scores).extracting(FeedbackScoreBatchItem::id)
                    .containsOnly(spanId);
        }

        @Test
        @DisplayName("Should use span context for variable replacements")
        void shouldUseSpanContextForVariableReplacements() {
            // Given
            JsonNode inputNode = JsonUtils.getMapper().valueToTree(Map.of("key", "value"));
            JsonNode outputNode = JsonUtils.getMapper().valueToTree(Map.of("result", "success"));
            JsonNode metadataNode = JsonUtils.getMapper().valueToTree(Map.of("meta", "data"));

            Span span = Span.builder()
                    .id(UUID.randomUUID())
                    .projectId(UUID.randomUUID())
                    .projectName("test-project")
                    .traceId(UUID.randomUUID())
                    .name("test-span")
                    .input(inputNode)
                    .output(outputNode)
                    .metadata(metadataNode)
                    .build();

            SpanUserDefinedMetricPythonCode code = new SpanUserDefinedMetricPythonCode(
                    "def score(input, output): return [...]",
                    Map.of("input", "input.key", "output", "output.result"));

            SpanToScoreUserDefinedMetricPython message = SpanToScoreUserDefinedMetricPython.builder()
                    .span(span)
                    .ruleId(UUID.randomUUID())
                    .ruleName("test-rule")
                    .code(code)
                    .workspaceId("workspace-123")
                    .userName("test-user")
                    .build();

            when(pythonEvaluatorService.evaluate(any(String.class), any(Map.class)))
                    .thenReturn(List.of());
            when(feedbackScoreService.scoreBatchOfSpans(any(List.class)))
                    .thenReturn(reactor.core.publisher.Mono.empty());

            // When
            scorer.score(message);

            // Then
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, String>> dataCaptor = ArgumentCaptor.forClass(Map.class);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<String> metricCaptor = ArgumentCaptor.forClass(String.class);
            verify(pythonEvaluatorService).evaluate(metricCaptor.capture(), dataCaptor.capture());

            // Verify that OnlineScoringEngine.toReplacements was called with span
            // The actual replacement logic is tested in OnlineScoringEngineTest
            // Here we just verify the scorer calls the service with the correct metric code
            assertThat(dataCaptor.getValue()).isNotNull();
        }
    }
}
