package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Project;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Trace;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge;
import com.comet.opik.api.events.TraceThreadToScoreLlmAsJudge;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.TraceSearchCriteria;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.evaluators.AutomationRuleEvaluatorService;
import com.comet.opik.domain.llm.ChatCompletionService;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.structuredoutput.ToolCallingStrategy;
import com.comet.opik.domain.threads.TraceThreadService;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.dropwizard.util.Duration;
import jakarta.ws.rs.NotFoundException;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonReactiveClient;
import org.slf4j.Logger;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItemThread;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge.TraceThreadLlmAsJudgeCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnlineScoringTraceThreadLlmAsJudgeScorerTest {

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    @Mock
    private OnlineScoringConfig onlineScoringConfig;
    @Mock
    private RedissonReactiveClient redissonClient;
    @Mock
    private FeedbackScoreService feedbackScoreService;
    @Mock
    private ChatCompletionService aiProxyService;
    @Mock
    private LlmProviderFactory llmProviderFactory;
    @Mock
    private TraceService traceService;
    @Mock
    private TraceThreadService traceThreadService;
    @Mock
    private ProjectService projectService;
    @Mock
    private AutomationRuleEvaluatorService automationRuleEvaluatorService;

    private OnlineScoringTraceThreadLlmAsJudgeScorer scorer;
    private MockedStatic<UserFacingLoggingFactory> mockedFactory;
    private Logger userFacingLogger;

    private UUID projectId;
    private UUID ruleId;
    private UUID threadModelId;
    private String threadId;
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
                .scorer("trace_thread_llm_as_judge")
                .streamName("stream_scoring_trace_thread_llm_as_judge")
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

        scorer = new OnlineScoringTraceThreadLlmAsJudgeScorer(
                onlineScoringConfig,
                redissonClient,
                feedbackScoreService,
                aiProxyService,
                llmProviderFactory,
                traceService,
                traceThreadService,
                projectService,
                automationRuleEvaluatorService);

        projectId = UUID.randomUUID();
        ruleId = UUID.randomUUID();
        threadModelId = UUID.randomUUID();
        threadId = "thread-" + RandomStringUtils.secure().nextAlphanumeric(32);
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
    class ScoringTests {

        // Real evaluator code so prepareThreadLlmRequest renders against a valid schema.
        private static final String EVALUATOR_JSON = """
                {
                  "model": { "name": "gpt-4o", "temperature": 0.3 },
                  "messages": [
                    { "role": "USER", "content": "Score the conversation: {{context}}" },
                    { "role": "SYSTEM", "content": "You are a helpful AI." }
                  ],
                  "schema": [
                    { "name": "Relevance",   "type": "INTEGER", "description": "Relevance of the answer" },
                    { "name": "Conciseness", "type": "DOUBLE",  "description": "How concise the answer is" }
                  ]
                }
                """;

        // Matches the schema above; toFeedbackScores parses this into two FeedbackScoreBatchItem entries.
        private static final String LLM_RESPONSE = """
                {
                  "Relevance":   { "score": 4,   "reason": "on-topic" },
                  "Conciseness": { "score": 3.5, "reason": "could be tighter" }
                }
                """;

        @Test
        void scoresThreadAndPersistsScores() {
            var code = JsonUtils.readValue(EVALUATOR_JSON, TraceThreadLlmAsJudgeCode.class);
            var message = sampleMessage().toBuilder().code(code).build();
            var trace = sampleTrace();
            var project = Project.builder().id(projectId).name("test-project").build();
            var rule = AutomationRuleEvaluatorTraceThreadLlmAsJudge.builder()
                    .name(ruleName)
                    .code(code)
                    .build();

            when(traceService.search(anyInt(), any(TraceSearchCriteria.class)))
                    .thenReturn(Flux.just(trace), Flux.empty());
            when(traceThreadService.getThreadModelId(projectId, threadId))
                    .thenReturn(Mono.just(threadModelId));
            when(automationRuleEvaluatorService.findById(ruleId, Set.of(projectId), workspaceId))
                    .thenReturn(rule);
            when(projectService.get(projectId, workspaceId)).thenReturn(project);
            when(llmProviderFactory.getStructuredOutputStrategy("gpt-4o"))
                    .thenReturn(new ToolCallingStrategy());
            when(aiProxyService.scoreTrace(any(ChatRequest.class), eq(code.model()), eq(workspaceId)))
                    .thenReturn(ChatResponse.builder().aiMessage(AiMessage.aiMessage(LLM_RESPONSE)).build());
            when(feedbackScoreService.scoreBatchOfThreads(any())).thenReturn(Mono.empty());
            when(traceThreadService.setScoredAt(eq(projectId), eq(List.of(threadId)), any()))
                    .thenReturn(Mono.empty());

            scorer.score(message).block();

            var captor = ArgumentCaptor.forClass(List.class);
            verify(feedbackScoreService).scoreBatchOfThreads(captor.capture());
            verify(traceThreadService).setScoredAt(eq(projectId), eq(List.of(threadId)), any());

            assertThat(captor.getValue()).usingRecursiveComparison().isEqualTo(List.of(
                    threadScore("Relevance", BigDecimal.valueOf(4), "on-topic", project),
                    threadScore("Conciseness", new BigDecimal("3.5"), "could be tighter", project)));
        }

        @Test
        void skipsScoringWhenThreadHasNoTraces() {
            var message = sampleMessage();

            when(traceService.search(anyInt(), any(TraceSearchCriteria.class))).thenReturn(Flux.empty());
            when(traceThreadService.setScoredAt(eq(projectId), eq(List.of(threadId)), any()))
                    .thenReturn(Mono.empty());

            scorer.score(message).block();

            verify(traceThreadService, never()).getThreadModelId(any(), any());
            verify(automationRuleEvaluatorService, never()).findById(any(), any(), any());
            verify(projectService, never()).get(any(), any());
            verify(aiProxyService, never()).scoreTrace(any(), any(), any());
            verify(feedbackScoreService, never()).scoreBatchOfThreads(any());
        }

        @Test
        void skipsScoringWhenThreadModelIsMissing() {
            var message = sampleMessage();

            when(traceService.search(anyInt(), any(TraceSearchCriteria.class)))
                    .thenReturn(Flux.just(sampleTrace()), Flux.empty());
            when(traceThreadService.getThreadModelId(projectId, threadId)).thenReturn(Mono.empty());
            when(traceThreadService.setScoredAt(eq(projectId), eq(List.of(threadId)), any()))
                    .thenReturn(Mono.empty());

            scorer.score(message).block();

            verify(automationRuleEvaluatorService, never()).findById(any(), any(), any());
            verify(projectService, never()).get(any(), any());
            verify(aiProxyService, never()).scoreTrace(any(), any(), any());
            verify(feedbackScoreService, never()).scoreBatchOfThreads(any());
        }

        @Test
        void skipsSilentlyWhenRuleHasBeenDeleted() {
            var message = sampleMessage();

            when(traceService.search(anyInt(), any(TraceSearchCriteria.class)))
                    .thenReturn(Flux.just(sampleTrace()), Flux.empty());
            when(traceThreadService.getThreadModelId(projectId, threadId)).thenReturn(Mono.just(threadModelId));
            when(automationRuleEvaluatorService.findById(ruleId, Set.of(projectId), workspaceId))
                    .thenThrow(new NotFoundException("rule not found"));
            when(traceThreadService.setScoredAt(eq(projectId), eq(List.of(threadId)), any()))
                    .thenReturn(Mono.empty());

            scorer.score(message).block();

            verify(projectService, never()).get(any(), any());
            verify(aiProxyService, never()).scoreTrace(any(), any(), any());
            verify(feedbackScoreService, never()).scoreBatchOfThreads(any());
        }

        static Stream<Arguments> errorCauseScenarios() {
            return Stream.of(
                    Arguments.of("without cause -> uses error message",
                            new RuntimeException("DB unreachable"), "DB unreachable"),
                    Arguments.of("with cause -> uses cause message",
                            new RuntimeException("wrapper", new IllegalStateException("inner cause")), "inner cause"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("errorCauseScenarios")
        void unwrapsCauseMessageWhenLoggingRuleLookupFailure(String name, RuntimeException error,
                String loggedMessage) {
            var message = sampleMessage();

            when(traceService.search(anyInt(), any(TraceSearchCriteria.class)))
                    .thenReturn(Flux.just(sampleTrace()), Flux.empty());
            when(traceThreadService.getThreadModelId(projectId, threadId)).thenReturn(Mono.just(threadModelId));
            when(automationRuleEvaluatorService.findById(ruleId, Set.of(projectId), workspaceId)).thenThrow(error);

            assertThatThrownBy(() -> scorer.score(message).block()).isInstanceOf(RuntimeException.class);

            verify(userFacingLogger).error(
                    contains("Unexpected error while looking up rule for threadId"),
                    eq(threadId),
                    eq(loggedMessage));
            verify(aiProxyService, never()).scoreTrace(any(), any(), any());
            verify(feedbackScoreService, never()).scoreBatchOfThreads(any());
        }
    }

    private TraceThreadToScoreLlmAsJudge sampleMessage() {
        return podamFactory.manufacturePojo(TraceThreadToScoreLlmAsJudge.class).toBuilder()
                .ruleId(ruleId)
                .projectId(projectId)
                .threadIds(List.of(threadId))
                .code(new TraceThreadLlmAsJudgeCode(null, List.of(), List.of()))
                .workspaceId(workspaceId)
                .userName(userName)
                .build();
    }

    private Trace sampleTrace() {
        return podamFactory.manufacturePojo(Trace.class).toBuilder()
                .id(UUID.randomUUID())
                .projectId(projectId)
                .threadId(threadId)
                .build();
    }

    private FeedbackScoreBatchItemThread threadScore(String name, BigDecimal value, String reason, Project project) {
        return FeedbackScoreBatchItemThread.builder()
                .id(threadModelId)
                .threadId(threadId)
                .projectId(projectId)
                .projectName(project.name())
                .name(name)
                .value(value)
                .reason(reason)
                .source(ScoreSource.ONLINE_SCORING)
                .build();
    }
}
