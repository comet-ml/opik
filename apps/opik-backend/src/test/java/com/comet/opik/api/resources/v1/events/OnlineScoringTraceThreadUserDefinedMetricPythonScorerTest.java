package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Project;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Span;
import com.comet.opik.api.SpanForLlm;
import com.comet.opik.api.Trace;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython;
import com.comet.opik.api.events.TraceThreadToScoreUserDefinedMetricPython;
import com.comet.opik.api.resources.v1.events.tools.ToolRegistry;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.SpanService;
import com.comet.opik.domain.SpanType;
import com.comet.opik.domain.TraceSearchCriteria;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.evaluators.AutomationRuleEvaluatorService;
import com.comet.opik.domain.evaluators.python.PythonEvaluatorService;
import com.comet.opik.domain.evaluators.python.PythonScoreResult;
import com.comet.opik.domain.evaluators.python.TraceThreadPythonEvaluatorRequest.ChatMessage;
import com.comet.opik.domain.threads.TraceThreadService;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
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
import org.redisson.api.RStreamReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.options.PlainOptions;
import org.slf4j.Logger;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItemThread;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.TraceThreadUserDefinedMetricPythonCode;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnlineScoringTraceThreadUserDefinedMetricPythonScorerTest {

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
    private PythonEvaluatorService pythonEvaluatorService;
    @Mock
    private TraceService traceService;
    @Mock
    private TraceThreadService traceThreadService;
    @Mock
    private ProjectService projectService;
    @Mock
    private AutomationRuleEvaluatorService automationRuleEvaluatorService;
    @Mock
    private SpanService spanService;

    private OnlineScoringTraceThreadUserDefinedMetricPythonScorer scorer;
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
                .scorer("trace_thread_user_defined_metric_python")
                .streamName("stream_scoring_trace_thread_user_defined_metric_python")
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

        // Real AgenticScoringServiceImpl (not a mock) so the bounded span preload runs for real over the
        // stubbed spanService.getByTraceIds Flux — toggle-on tests exercise the actual preload path.
        var agenticScoringService = new AgenticScoringServiceImpl(onlineScoringConfig, new ToolRegistry(Set.of()));

        scorer = new OnlineScoringTraceThreadUserDefinedMetricPythonScorer(
                onlineScoringConfig,
                serviceTogglesConfig,
                redissonClient,
                feedbackScoreService,
                pythonEvaluatorService,
                traceService,
                traceThreadService,
                projectService,
                automationRuleEvaluatorService,
                spanService,
                agenticScoringService);

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
    class ServiceToggleTests {

        @Test
        void startsConsumerWhenToggleIsEnabled() {
            when(serviceTogglesConfig.isTraceThreadPythonEvaluatorEnabled()).thenReturn(true);
            RStreamReactive<Object, Object> stream = mock(RStreamReactive.class);
            when(redissonClient.getStream(any(PlainOptions.class))).thenReturn(stream);
            when(stream.createGroup(any())).thenReturn(Mono.empty());

            scorer.start();
        }

        @Test
        void skipsStartWhenToggleIsDisabled() {
            when(serviceTogglesConfig.isTraceThreadPythonEvaluatorEnabled()).thenReturn(false);

            scorer.start();

            verify(redissonClient, never()).getStream(any(PlainOptions.class));
        }
    }

    @Nested
    class ScoringTests {

        // Common happy-path stubs shared by the scoring tests: everything except the agentic toggle, the
        // SpanService size/fetch, and the pythonEvaluatorService stub (captor vs plain), which each test
        // drives itself. Used only by tests that reach full scoring, not the short-circuit error cases.
        private void stubPythonScoringHappyPath(Trace trace, Project project) {
            when(traceService.search(anyInt(), any(TraceSearchCriteria.class)))
                    .thenReturn(Flux.just(trace), Flux.empty());
            when(traceThreadService.getThreadModelId(projectId, threadId)).thenReturn(Mono.just(threadModelId));
            when(automationRuleEvaluatorService.findById(ruleId, Set.of(projectId), workspaceId))
                    .thenReturn(ruleFor(ruleName));
            when(projectService.get(projectId, workspaceId)).thenReturn(project);
            when(feedbackScoreService.scoreBatchOfThreads(any())).thenReturn(Mono.empty());
        }

        @Test
        void scoresThreadAndPersistsScores() {
            var message = sampleMessage();
            var trace = sampleTrace();
            var project = Project.builder().id(projectId).name("test-project").build();
            var pythonScore = PythonScoreResult.builder()
                    .name("test_score")
                    .value(BigDecimal.valueOf(0.95))
                    .reason("test reason")
                    .build();
            stubPythonScoringHappyPath(trace, project);
            when(pythonEvaluatorService.evaluateThread(eq(message.code().metric()), any()))
                    .thenReturn(Mono.just(List.of(pythonScore)));

            scorer.score(message).block();

            var captor = ArgumentCaptor.forClass(List.class);
            verify(feedbackScoreService).scoreBatchOfThreads(captor.capture());
            assertThat(captor.getValue()).usingRecursiveComparison().isEqualTo(List.of(
                    threadScore("test_score", BigDecimal.valueOf(0.95), "test reason", project)));
        }

        @Test
        void skipsSpanFetchWhenAgenticToolsDisabled() {
            // Locks the toggle gate for the Python thread path: when isAgenticToolsEnabled
            // is false, the scorer must NOT call spanService.getByTraceIds — preserves
            // today's [{role, content}, ...] wire shape to the Python runner exactly.
            var message = sampleMessage();
            var trace = sampleTrace();
            var project = Project.builder().id(projectId).name("test-project").build();
            var pythonScore = PythonScoreResult.builder()
                    .name("test_score")
                    .value(BigDecimal.valueOf(0.95))
                    .reason("ok")
                    .build();
            stubPythonScoringHappyPath(trace, project);
            when(pythonEvaluatorService.evaluateThread(eq(message.code().metric()), any()))
                    .thenReturn(Mono.just(List.of(pythonScore)));
            // Toggle off — the scorer should not even ask the SpanService.
            when(serviceTogglesConfig.isAgenticToolsEnabled()).thenReturn(false);

            scorer.score(message).block();

            verifyNoInteractions(spanService);
        }

        @Test
        void fetchesSpansAndEnrichesConversationWhenAgenticToolsEnabled() {
            // Toggle on: scorer fetches every span across the thread and the captured
            // ChatMessage list sent to the Python evaluator carries the spans nested under
            // the assistant entry. Locks in the end-to-end enrichment contract — a future
            // refactor that quietly drops the SpanService fetch or routes through
            // fromTraceToThread (legacy) would break this test loudly.
            var message = sampleMessage();
            var trace = sampleTrace();
            var project = Project.builder().id(projectId).name("test-project").build();
            var pythonScore = PythonScoreResult.builder()
                    .name("tool_use_score")
                    .value(BigDecimal.valueOf(0.9))
                    .reason("ok")
                    .build();
            var toolSpan = Span.builder()
                    .id(UUID.randomUUID())
                    .name("fetch_weather")
                    .type(SpanType.tool)
                    .startTime(Instant.now())
                    .traceId(trace.id())
                    .projectId(projectId)
                    .build();

            stubPythonScoringHappyPath(trace, project);
            when(serviceTogglesConfig.isAgenticToolsEnabled()).thenReturn(true);
            when(onlineScoringConfig.getAgenticToolsMaxPreloadMb()).thenReturn(64);
            // Cheap size probe (route-before-fetch): under the cap → enrich → fetch spans.
            when(spanService.getSpansSizeByTraceIds(Set.of(trace.id()))).thenReturn(Mono.just(1_000L));
            when(spanService.getByTraceIds(Set.of(trace.id()))).thenReturn(Flux.just(toolSpan));
            ArgumentCaptor<List<ChatMessage>> contextCaptor = ArgumentCaptor.forClass(List.class);
            when(pythonEvaluatorService.evaluateThread(eq(message.code().metric()), contextCaptor.capture()))
                    .thenReturn(Mono.just(List.of(pythonScore)));

            scorer.score(message).block();

            verify(spanService).getByTraceIds(Set.of(trace.id()));
            var captured = contextCaptor.getValue();
            // Conversation contains user + assistant per trace (one trace here).
            assertThat(captured).hasSize(2);
            assertThat(captured.get(0).role()).isEqualTo("user");
            assertThat(captured.get(0).spans()).isNull(); // user entry never carries spans
            assertThat(captured.get(1).role()).isEqualTo("assistant");
            assertThat(captured.get(1).spans()).isNotNull();
            assertThat(captured.get(1).spans()).extracting(SpanForLlm::name)
                    .containsExactly("fetch_weather");
        }

        @Test
        void scoresWithUnenrichedContextWhenThreadExceedsPreloadCap() {
            // Toggle on, but the thread's span size exceeds the preload cap: the scorer must NOT bulk-fetch
            // spans (that is the heap-OOM path) — it degrades to the legacy unenriched {role, content}
            // context and still scores. This is the OPIK-7454 safeguard for the Python thread path.
            var message = sampleMessage();
            var trace = sampleTrace();
            var project = Project.builder().id(projectId).name("test-project").build();
            var pythonScore = PythonScoreResult.builder()
                    .name("tool_use_score")
                    .value(BigDecimal.valueOf(0.9))
                    .reason("ok")
                    .build();

            stubPythonScoringHappyPath(trace, project);
            when(serviceTogglesConfig.isAgenticToolsEnabled()).thenReturn(true);
            when(onlineScoringConfig.getAgenticToolsMaxPreloadMb()).thenReturn(64); // cap = 64 MiB
            // Size probe reports 200 MiB — over the cap → no bulk fetch, unenriched context.
            when(spanService.getSpansSizeByTraceIds(Set.of(trace.id())))
                    .thenReturn(Mono.just(200L * 1024 * 1024));
            ArgumentCaptor<List<ChatMessage>> contextCaptor = ArgumentCaptor.forClass(List.class);
            when(pythonEvaluatorService.evaluateThread(eq(message.code().metric()), contextCaptor.capture()))
                    .thenReturn(Mono.just(List.of(pythonScore)));

            scorer.score(message).block();

            // The OOM path is the bulk span fetch — it must not run when over the cap.
            verify(spanService, never()).getByTraceIds(any());
            var captured = contextCaptor.getValue();
            assertThat(captured).hasSize(2);
            assertThat(captured.get(1).role()).isEqualTo("assistant");
            assertThat(captured.get(1).spans()).isNull(); // unenriched — no per-turn span tree
        }

        @Test
        void warnsAndScoresUnenrichedWhenPreloadOverflowsDespiteFittingSizeEstimate() {
            // Toggle on and the cheap aggregate reports UNDER the cap, so the scorer chooses to enrich and
            // issues the bulk fetch — but the actual streamed spans exceed the byte cap, so the bounded
            // preload overflows and drops the buffer. The scorer must still score with the unenriched
            // context AND surface the estimate/actual mismatch with a warning (not fall back silently).
            var message = sampleMessage();
            var trace = sampleTrace();
            var project = Project.builder().id(projectId).name("test-project").build();
            var pythonScore = PythonScoreResult.builder()
                    .name("tool_use_score")
                    .value(BigDecimal.valueOf(0.9))
                    .reason("ok")
                    .build();
            var hugeSpan = Span.builder()
                    .id(UUID.randomUUID())
                    .name("huge-tool-call")
                    .type(SpanType.tool)
                    .startTime(Instant.now())
                    .traceId(trace.id())
                    .projectId(projectId)
                    .input(JsonUtils.readTree("{\"payload\":\"" + "x".repeat(2_000_000) + "\"}"))
                    .build();

            stubPythonScoringHappyPath(trace, project);
            when(serviceTogglesConfig.isAgenticToolsEnabled()).thenReturn(true);
            when(onlineScoringConfig.getAgenticToolsMaxPreloadMb()).thenReturn(1); // cap = 1 MiB
            // Aggregate under-counts (500 B, under the cap) → enrich is chosen...
            when(spanService.getSpansSizeByTraceIds(Set.of(trace.id()))).thenReturn(Mono.just(500L));
            // ...but the real span streams ~2 MiB, so the bounded preload overflows and drops the buffer.
            when(spanService.getByTraceIds(Set.of(trace.id()))).thenReturn(Flux.just(hugeSpan));
            ArgumentCaptor<List<ChatMessage>> contextCaptor = ArgumentCaptor.forClass(List.class);
            when(pythonEvaluatorService.evaluateThread(eq(message.code().metric()), contextCaptor.capture()))
                    .thenReturn(Mono.just(List.of(pythonScore)));

            scorer.score(message).block();

            // The fetch was attempted (enrich chosen from the estimate) but the buffer was dropped on
            // overflow, and the mismatch is surfaced rather than silent.
            verify(spanService).getByTraceIds(Set.of(trace.id()));
            verify(userFacingLogger).warn(contains("exceeded the enrichment cap"), eq(threadId), any(), any());
            var captured = contextCaptor.getValue();
            assertThat(captured).hasSize(2);
            assertThat(captured.get(1).role()).isEqualTo("assistant");
            assertThat(captured.get(1).spans()).isNull(); // unenriched — buffer dropped on overflow
        }

        @Test
        void skipsScoringWhenThreadHasNoTraces() {
            var message = sampleMessage();

            when(traceService.search(anyInt(), any(TraceSearchCriteria.class))).thenReturn(Flux.empty());

            scorer.score(message).block();

            verify(traceThreadService, never()).getThreadModelId(any(), any());
            verify(automationRuleEvaluatorService, never()).findById(any(), any(), any());
            verify(projectService, never()).get(any(), any());
            verify(pythonEvaluatorService, never()).evaluateThread(any(), any());
            verify(feedbackScoreService, never()).scoreBatchOfThreads(any());
        }

        @Test
        void skipsScoringWhenThreadModelIsMissing() {
            var message = sampleMessage();

            when(traceService.search(anyInt(), any(TraceSearchCriteria.class)))
                    .thenReturn(Flux.just(sampleTrace()), Flux.empty());
            when(traceThreadService.getThreadModelId(projectId, threadId)).thenReturn(Mono.empty());

            scorer.score(message).block();

            verify(automationRuleEvaluatorService, never()).findById(any(), any(), any());
            verify(projectService, never()).get(any(), any());
            verify(pythonEvaluatorService, never()).evaluateThread(any(), any());
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

            scorer.score(message).block();

            verify(projectService, never()).get(any(), any());
            verify(pythonEvaluatorService, never()).evaluateThread(any(), any());
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
            verify(pythonEvaluatorService, never()).evaluateThread(any(), any());
            verify(feedbackScoreService, never()).scoreBatchOfThreads(any());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("errorCauseScenarios")
        void unwrapsCauseMessageWhenLoggingEvaluatorFailure(String name, RuntimeException error,
                String loggedMessage) {
            var message = sampleMessage();

            when(traceService.search(anyInt(), any(TraceSearchCriteria.class)))
                    .thenReturn(Flux.just(sampleTrace()), Flux.empty());
            when(traceThreadService.getThreadModelId(projectId, threadId)).thenReturn(Mono.just(threadModelId));
            when(automationRuleEvaluatorService.findById(ruleId, Set.of(projectId), workspaceId))
                    .thenReturn(ruleFor(ruleName));
            when(projectService.get(projectId, workspaceId))
                    .thenReturn(Project.builder().id(projectId).name("test-project").build());
            when(pythonEvaluatorService.evaluateThread(eq(message.code().metric()), any()))
                    .thenReturn(Mono.error(error));

            assertThatThrownBy(() -> scorer.score(message).block()).isInstanceOf(RuntimeException.class);

            verify(userFacingLogger).error(
                    contains("Unexpected error while scoring threadId"),
                    eq(threadId),
                    eq(ruleName),
                    eq(loggedMessage));
            verify(feedbackScoreService, never()).scoreBatchOfThreads(any());
        }
    }

    private TraceThreadToScoreUserDefinedMetricPython sampleMessage() {
        return podamFactory.manufacturePojo(TraceThreadToScoreUserDefinedMetricPython.class).toBuilder()
                .ruleId(ruleId)
                .projectId(projectId)
                .threadIds(List.of(threadId))
                .code(new TraceThreadUserDefinedMetricPythonCode("def score(context): return []"))
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

    private static AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython ruleFor(String name) {
        return AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.builder()
                .name(name)
                .code(new TraceThreadUserDefinedMetricPythonCode("def score(context): return []"))
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
