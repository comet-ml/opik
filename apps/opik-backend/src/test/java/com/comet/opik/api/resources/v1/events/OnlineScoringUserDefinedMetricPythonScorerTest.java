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
import com.comet.opik.podam.PodamFactoryUtils;
import io.dropwizard.util.Duration;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RStreamReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.options.PlainOptions;
import org.slf4j.Logger;
import reactor.core.publisher.Mono;
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorUserDefinedMetricPython.UserDefinedMetricPythonCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnlineScoringUserDefinedMetricPythonScorerTest {

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

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
    private Logger userFacingLogger;

    private UUID projectId;
    private UUID traceId;
    private UUID ruleId;
    private String workspaceId;
    private String userName;
    private String ruleName;

    @BeforeEach
    void setUp() {
        userFacingLogger = mock(Logger.class);
        mockedFactory = mockStatic(UserFacingLoggingFactory.class);
        mockedFactory.when(() -> UserFacingLoggingFactory.getLogger(any(Class.class)))
                .thenReturn(userFacingLogger);

        var streamConfig = OnlineScoringConfig.StreamConfiguration.builder()
                .scorer("user_defined_metric_python")
                .streamName("stream_scoring_user_defined_metric_python")
                .codec("java")
                .poolingInterval(Duration.milliseconds(500))
                .longPollingDuration(Duration.seconds(5))
                .consumerBatchSize(10)
                .claimIntervalRatio(10)
                .pendingMessageDuration(Duration.minutes(10))
                .maxRetries(3)
                .build();

        when(onlineScoringConfig.getStreams()).thenReturn(List.of(streamConfig));
        when(onlineScoringConfig.getConsumerGroupName()).thenReturn("online_scoring");

        scorer = new OnlineScoringUserDefinedMetricPythonScorer(
                onlineScoringConfig,
                serviceTogglesConfig,
                redissonClient,
                feedbackScoreService,
                traceService,
                pythonEvaluatorService);

        projectId = UUID.randomUUID();
        traceId = UUID.randomUUID();
        ruleId = UUID.randomUUID();
        workspaceId = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
        userName = "user-" + RandomStringUtils.secure().nextAlphanumeric(32);
        ruleName = "rule-" + RandomStringUtils.secure().nextAlphanumeric(32);
    }

    @AfterEach
    void tearDown() {
        if (mockedFactory != null) {
            mockedFactory.close();
        }
    }

    @Nested
    class ServiceToggleTests {

        @Test
        void startsConsumerWhenToggleIsEnabled() {
            when(serviceTogglesConfig.isPythonEvaluatorEnabled()).thenReturn(true);
            RStreamReactive<Object, Object> stream = mock(RStreamReactive.class);
            when(redissonClient.getStream(any(PlainOptions.class))).thenReturn(stream);
            when(stream.createGroup(any())).thenReturn(Mono.empty());

            scorer.start();
        }

        @Test
        void skipsStartWhenToggleIsDisabled() {
            when(serviceTogglesConfig.isPythonEvaluatorEnabled()).thenReturn(false);

            scorer.start();

            verify(redissonClient, never()).getStream(any(PlainOptions.class));
        }
    }

    @Nested
    class ScoringTests {

        @Test
        void scoresTraceAndPersistsScores() {
            var message = sampleMessage();
            var pythonScore = PythonScoreResult.builder()
                    .name("test_score")
                    .value(BigDecimal.valueOf(0.95))
                    .reason("test reason")
                    .build();

            when(pythonEvaluatorService.evaluate(eq(message.code().metric()), any()))
                    .thenReturn(Mono.just(List.of(pythonScore)));
            when(feedbackScoreService.scoreBatchOfTraces(any())).thenReturn(Mono.empty());

            scorer.score(message).block();

            var captor = ArgumentCaptor.forClass(List.class);
            verify(feedbackScoreService).scoreBatchOfTraces(captor.capture());

            assertThat(captor.getValue()).usingRecursiveComparison().isEqualTo(List.of(
                    traceScore("test_score", BigDecimal.valueOf(0.95), "test reason", message.trace())));
        }

        @Test
        void propagatesEvaluatorErrorAndLogsMessage() {
            var message = sampleMessage();
            var error = new RuntimeException("Python BE timeout");

            when(pythonEvaluatorService.evaluate(eq(message.code().metric()), any()))
                    .thenReturn(Mono.error(error));

            assertThatThrownBy(() -> scorer.score(message).block()).isInstanceOf(RuntimeException.class);

            verify(userFacingLogger).error(
                    contains("Unexpected error while scoring traceId"),
                    eq(traceId),
                    eq(ruleName),
                    eq("Python BE timeout"));
            verify(feedbackScoreService, never()).scoreBatchOfTraces(any());
        }
    }

    private TraceToScoreUserDefinedMetricPython sampleMessage() {
        var trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                .id(traceId)
                .projectId(projectId)
                .build();
        return podamFactory.manufacturePojo(TraceToScoreUserDefinedMetricPython.class).toBuilder()
                .trace(trace)
                .ruleId(ruleId)
                .ruleName(ruleName)
                .code(new UserDefinedMetricPythonCode(
                        "def score(input, output): return []",
                        Map.of("input", "input.question", "output", "output.answer")))
                .workspaceId(workspaceId)
                .userName(userName)
                .build();
    }

    private FeedbackScoreBatchItem traceScore(String name, BigDecimal value, String reason, Trace trace) {
        return FeedbackScoreBatchItem.builder()
                .id(trace.id())
                .projectId(trace.projectId())
                .projectName(trace.projectName())
                .name(name)
                .value(value)
                .reason(reason)
                .source(ScoreSource.ONLINE_SCORING)
                .build();
    }
}
