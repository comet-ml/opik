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
import com.comet.opik.domain.evaluation.EvaluationRecorder;
import com.comet.opik.domain.evaluation.OnlineEvaluationRecorder;
import com.comet.opik.domain.evaluators.AutomationRuleEvaluatorService;
import com.comet.opik.domain.llm.ChatCompletionService;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.structuredoutput.ToolCallingStrategy;
import com.comet.opik.domain.threads.TraceThreadService;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
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
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonReactiveClient;
import org.slf4j.Logger;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnlineScoringTraceThreadLlmAsJudgeScorerTest {

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    @Mock
    private OnlineScoringConfig onlineScoringConfig;
    @Mock
    private com.comet.opik.infrastructure.ServiceTogglesConfig serviceTogglesConfig;
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
    @Mock
    private com.comet.opik.api.resources.v1.events.tools.ToolRegistry toolRegistry;
    @Mock
    private com.comet.opik.domain.SpanService spanService;
    @Mock
    private OnlineEvaluationRecorder onlineEvaluationRecorder;
    @Mock
    private com.comet.opik.domain.attachment.AttachmentService attachmentService;

    private OnlineScoringTraceThreadLlmAsJudgeScorer scorer;
    private AgenticScoringService agenticScoringService;
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
        // Defaults for the size-based agentic-tools branch — under the threshold so the
        // existing ScoringTests stay on the inline path. Routing-gate-specific tests
        // override these per-case.
        Mockito.lenient().when(onlineScoringConfig.getAgenticToolsCharsPerToken()).thenReturn(4);
        Mockito.lenient().when(onlineScoringConfig.getAgenticToolsThresholdTokens()).thenReturn(50_000);

        agenticScoringService = new AgenticScoringServiceImpl(onlineScoringConfig, toolRegistry);

        scorer = new OnlineScoringTraceThreadLlmAsJudgeScorer(
                onlineScoringConfig,
                serviceTogglesConfig,
                redissonClient,
                feedbackScoreService,
                aiProxyService,
                llmProviderFactory,
                traceService,
                traceThreadService,
                projectService,
                automationRuleEvaluatorService,
                agenticScoringService,
                spanService,
                onlineEvaluationRecorder,
                attachmentService);

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
    class RoutingGateTests {

        // Threads don't have an experimentId-driven branch (no test-suite-assertion equivalent),
        // so the truth table is toggle × tokens-vs-threshold × provider-supports-tools × hasAttachments.
        // Rows mirror the trace-side gate's design for symmetry.
        @org.junit.jupiter.params.ParameterizedTest(name = "toggle={0}, tokens={1}, threshold={2}, provider={3}, hasAttachments={4} → expected useTools={5}")
        @org.junit.jupiter.params.provider.CsvSource({
                // size-based path — all three preconditions must hold
                "true,  60000, 50000, OPEN_AI, false, true",
                "true,  50000, 50000, OPEN_AI, false, true",
                // below threshold → inline
                "true,  49999, 50000, OPEN_AI, false, false",
                // toggle off → inline even on huge contexts
                "false, 60000, 50000, OPEN_AI, false, false",
                // provider doesn't support tool calling → inline + warn
                "true,  60000, 50000, OLLAMA,  false, false",
                // no preconditions met
                "false, 0,     50000, OPEN_AI, false, false",
                // attachment-driven path (OPIK-6555): toggle on + attachments + below size threshold → tools
                "true,  0,     50000, OPEN_AI, true,  true",
                // attachments but toggle off → inline (whole agentic feature is gated by the toggle)
                "false, 0,     50000, OPEN_AI, true,  false",
                // attachments but provider can't do tools → inline + warn
                "true,  0,     50000, OLLAMA,  true,  false",
        })
        void gateMatchesTruthTable(
                boolean toggleEnabled, int estimatedTokens, int thresholdTokens,
                com.comet.opik.api.LlmProvider provider, boolean hasAttachments, boolean expectedUseTools) {
            String modelName = "gpt-test";
            Mockito.when(serviceTogglesConfig.isAgenticToolsEnabled()).thenReturn(toggleEnabled);
            Mockito.lenient().when(onlineScoringConfig.getAgenticToolsThresholdTokens())
                    .thenReturn(thresholdTokens);
            Mockito.lenient().when(llmProviderFactory.getLlmProvider(modelName)).thenReturn(provider);

            boolean useTools = scorer.shouldUseAgenticTools(estimatedTokens, hasAttachments, modelName, "thread-x",
                    List.of(stringContentMessage()));

            org.assertj.core.api.Assertions.assertThat(useTools).isEqualTo(expectedUseTools);
        }

        @org.junit.jupiter.api.Test
        void multimodalTemplateForcesInlineFallbackEvenWhenAllOtherPreconditionsHold() {
            // Every other gate is satisfied (toggle on, over threshold, provider supports tools)
            // but the template carries a non-string-content message. The agentic-tools render
            // path can't substitute into multimodal templates, so we must downgrade to inline
            // rather than letting renderThreadMessagesWithReplacement throw downstream.
            String modelName = "gpt-test";
            Mockito.when(serviceTogglesConfig.isAgenticToolsEnabled()).thenReturn(true);
            Mockito.lenient().when(onlineScoringConfig.getAgenticToolsThresholdTokens())
                    .thenReturn(50_000);
            Mockito.lenient().when(llmProviderFactory.getLlmProvider(modelName))
                    .thenReturn(com.comet.opik.api.LlmProvider.OPEN_AI);

            boolean useTools = scorer.shouldUseAgenticTools(60_000, false, modelName, "thread-x",
                    List.of(multimodalContentMessage()));

            org.assertj.core.api.Assertions.assertThat(useTools).isFalse();
        }

        private com.comet.opik.api.evaluators.LlmAsJudgeMessage stringContentMessage() {
            return com.comet.opik.api.evaluators.LlmAsJudgeMessage.builder()
                    .role(ChatMessageType.USER)
                    .content("evaluate {{context}}")
                    .build();
        }

        private com.comet.opik.api.evaluators.LlmAsJudgeMessage multimodalContentMessage() {
            // Non-null contentArray => isStringContent() == false (the multimodal branch
            // hasMultimodalTemplate looks for). An empty list is enough for the predicate;
            // we don't exercise the renderer in this test.
            return com.comet.opik.api.evaluators.LlmAsJudgeMessage.builder()
                    .role(ChatMessageType.USER)
                    .contentArray(List.of())
                    .build();
        }
    }

    @Nested
    class ToolLoopTests {

        // Real evaluator code so the message mock can return a model that scoreTrace
        // calls accept. We mirror the trace-side ToolLoopTests pattern.
        private TraceThreadToScoreLlmAsJudge newMessage() {
            var msg = Mockito.mock(TraceThreadToScoreLlmAsJudge.class);
            var code = Mockito.mock(
                    com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge.TraceThreadLlmAsJudgeCode.class);
            var modelParams = Mockito.mock(
                    com.comet.opik.api.evaluators.LlmAsJudgeModelParameters.class);
            Mockito.lenient().when(code.model()).thenReturn(modelParams);
            Mockito.lenient().when(modelParams.name()).thenReturn("gpt-test");
            Mockito.lenient().when(msg.code()).thenReturn(code);
            Mockito.lenient().when(msg.workspaceId()).thenReturn("ws-1");
            Mockito.lenient().when(msg.userName()).thenReturn("user-1");
            Mockito.lenient().when(msg.ruleId()).thenReturn(UUID.randomUUID());
            // Required by TraceToolContext.forThread, which handleToolCalls builds before the loop.
            Mockito.lenient().when(msg.projectId()).thenReturn(UUID.randomUUID());
            return msg;
        }

        private static ToolSpecification stubSpec(String name) {
            return ToolSpecification.builder()
                    .name(name)
                    .parameters(JsonObjectSchema.builder().build())
                    .build();
        }

        @org.junit.jupiter.api.Test
        void handleToolCallsReturnsImmediatelyWhenNoToolRequests() {
            var plainResponse = ChatResponse.builder()
                    .aiMessage(AiMessage.from("done"))
                    .build();
            var toolRequest = ChatRequest.builder()
                    .messages(UserMessage.from("score"))
                    .build();
            var structuredRequest = ChatRequest.builder()
                    .messages(UserMessage.from("score"))
                    .build();
            var message = Mockito.mock(TraceThreadToScoreLlmAsJudge.class);

            var result = scorer.handleToolCalls(
                    plainResponse, toolRequest, structuredRequest, message, Map.of(),
                    EvaluationRecorder.NOOP, BudgetGuard.UNLIMITED).block();

            org.assertj.core.api.Assertions.assertThat(result).isSameAs(plainResponse);
            Mockito.verifyNoInteractions(aiProxyService);
            Mockito.verifyNoInteractions(toolRegistry);
        }

        @org.junit.jupiter.api.Test
        void handleToolCallsAccumulatesResultsAndFinalizesWithStructuredRequestShape() {
            var message = newMessage();
            var toolReq = ToolExecutionRequest.builder()
                    .id("call-1")
                    .name("read")
                    .arguments("{}")
                    .build();
            var initialResponse = ChatResponse.builder()
                    .aiMessage(AiMessage.from(List.of(toolReq)))
                    .build();
            // Round-1 follow-up returns no more tool calls — loop exits.
            var roundOneResponse = ChatResponse.builder()
                    .aiMessage(AiMessage.from("ok"))
                    .build();
            // Final structured wrap-up — handleToolCalls re-scores with the forcing user message.
            var finalResponse = ChatResponse.builder()
                    .aiMessage(AiMessage.from("{\"thread_coherence\":4}"))
                    .build();

            Mockito.when(aiProxyService.scoreTrace(
                    ArgumentMatchers.any(ChatRequest.class),
                    ArgumentMatchers.any(),
                    ArgumentMatchers.any()))
                    .thenReturn(roundOneResponse, finalResponse);
            // Tool execution returns a small JSON blob — exercises the messages.add(result) path.
            Mockito.when(toolRegistry.execute(
                    ArgumentMatchers.eq("read"),
                    ArgumentMatchers.anyString(),
                    ArgumentMatchers.any()))
                    .thenReturn(reactor.core.publisher.Mono.just("{\"trace\":\"...\"}"));

            var toolRequest = ChatRequest.builder()
                    .messages(UserMessage.from("score the thread"))
                    .toolSpecifications(stubSpec("read"))
                    .build();
            var structuredRequest = ChatRequest.builder()
                    .messages(UserMessage.from("score the thread"))
                    .build();

            var result = scorer.handleToolCalls(
                    initialResponse, toolRequest, structuredRequest, message, Map.of(),
                    EvaluationRecorder.NOOP, BudgetGuard.UNLIMITED).block();

            org.assertj.core.api.Assertions.assertThat(result).isSameAs(finalResponse);

            // 2 scoreTrace calls: round-1 follow-up (with tool specs) + final structured re-issue.
            var requests = ArgumentCaptor.forClass(ChatRequest.class);
            Mockito.verify(aiProxyService, Mockito.times(2)).scoreTrace(
                    requests.capture(),
                    ArgumentMatchers.any(),
                    ArgumentMatchers.any());

            var sent = requests.getAllValues();
            // Round 1: 3 messages — UserMessage + AiMessage(tool calls) + ToolExecutionResultMessage.
            org.assertj.core.api.Assertions.assertThat(sent.get(0).messages()).hasSize(3);
            org.assertj.core.api.Assertions.assertThat(sent.get(0).toolSpecifications())
                    .extracting(ToolSpecification::name)
                    .containsExactly("read");
            // Final: no tool specs + the round-1 terminal AiMessage (appended by ToolCallLoop's
            // no-tool-calls early return) + the forcing UserMessage at the end.
            // 5 messages: UserMessage + AiMessage(tool calls) + ToolResult + AiMessage(terminal)
            // + UserMessage(forcing).
            var finalSent = sent.get(1);
            org.assertj.core.api.Assertions.assertThat(finalSent.toolSpecifications()).isNullOrEmpty();
            org.assertj.core.api.Assertions.assertThat(finalSent.messages()).hasSize(5);
            org.assertj.core.api.Assertions.assertThat(finalSent.messages().get(3))
                    .isInstanceOf(AiMessage.class);
            org.assertj.core.api.Assertions.assertThat(finalSent.messages().get(4))
                    .isInstanceOf(UserMessage.class);
            org.assertj.core.api.Assertions.assertThat(
                    ((UserMessage) finalSent.messages().get(4)).singleText())
                    .contains("Now respond with ONLY the JSON object");
        }

        @org.junit.jupiter.api.Test
        void handleToolCallsPropagatesScoreTraceFailureMidLoop() {
            var message = newMessage();
            var toolReq = ToolExecutionRequest.builder()
                    .id("call-1")
                    .name("read")
                    .arguments("{}")
                    .build();
            var initialResponse = ChatResponse.builder()
                    .aiMessage(AiMessage.from(List.of(toolReq)))
                    .build();

            // Follow-up scoreTrace blows up (transient provider 503 after the per-LLM retry
            // policy is exhausted). The contract: failure escapes handleToolCalls, the message
            // returns un-ACKed, Redis Streams redelivers — same as the trace scorer's contract.
            var providerFailure = new RuntimeException("provider 503");
            Mockito.when(aiProxyService.scoreTrace(
                    ArgumentMatchers.any(ChatRequest.class),
                    ArgumentMatchers.any(),
                    ArgumentMatchers.any()))
                    .thenThrow(providerFailure);
            Mockito.when(toolRegistry.execute(
                    ArgumentMatchers.eq("read"),
                    ArgumentMatchers.anyString(),
                    ArgumentMatchers.any()))
                    .thenReturn(reactor.core.publisher.Mono.just("{}"));

            var toolRequest = ChatRequest.builder()
                    .messages(UserMessage.from("score"))
                    .toolSpecifications(stubSpec("read"))
                    .build();
            var structuredRequest = ChatRequest.builder()
                    .messages(UserMessage.from("score"))
                    .build();

            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> scorer.handleToolCalls(
                            initialResponse, toolRequest, structuredRequest, message, Map.of(),
                            EvaluationRecorder.NOOP, BudgetGuard.UNLIMITED).block())
                    .isSameAs(providerFailure);

            // Exactly one provider call attempted — the loop didn't swallow + continue.
            Mockito.verify(aiProxyService, Mockito.times(1)).scoreTrace(
                    ArgumentMatchers.any(ChatRequest.class),
                    ArgumentMatchers.any(),
                    ArgumentMatchers.any());
        }

        @org.junit.jupiter.api.Test
        void handleToolCallsCapsAtMaxRoundsAndStillFiresWrapUpStructuredCall() {
            var message = newMessage();
            var toolReq = ToolExecutionRequest.builder()
                    .id("call")
                    .name("read")
                    .arguments("{}")
                    .build();
            // Every response keeps emitting tool calls — the loop runs MAX_TOOL_CALL_ROUNDS=10
            // times before bailing. After the cap, handleToolCalls still fires the wrap-up
            // structured call. Total: 10 in-loop + 1 wrap-up = 11 scoreTrace calls.
            var toolCallingResponse = ChatResponse.builder()
                    .aiMessage(AiMessage.from(List.of(toolReq)))
                    .build();
            var finalResponse = ChatResponse.builder()
                    .aiMessage(AiMessage.from("{\"thread_coherence\":3}"))
                    .build();

            Mockito.when(aiProxyService.scoreTrace(
                    ArgumentMatchers.any(ChatRequest.class),
                    ArgumentMatchers.any(),
                    ArgumentMatchers.any()))
                    // 10 in-loop returns (rounds 1..10), then the wrap-up structured call.
                    .thenReturn(toolCallingResponse,
                            toolCallingResponse, toolCallingResponse, toolCallingResponse, toolCallingResponse,
                            toolCallingResponse, toolCallingResponse, toolCallingResponse, toolCallingResponse,
                            toolCallingResponse, finalResponse);
            Mockito.when(toolRegistry.execute(
                    ArgumentMatchers.eq("read"),
                    ArgumentMatchers.anyString(),
                    ArgumentMatchers.any()))
                    .thenReturn(reactor.core.publisher.Mono.just("{}"));

            var toolRequest = ChatRequest.builder()
                    .messages(UserMessage.from("score"))
                    .toolSpecifications(stubSpec("read"))
                    .build();
            var structuredRequest = ChatRequest.builder()
                    .messages(UserMessage.from("score"))
                    .build();

            var result = scorer.handleToolCalls(
                    toolCallingResponse, toolRequest, structuredRequest, message, Map.of(),
                    EvaluationRecorder.NOOP, BudgetGuard.UNLIMITED).block();

            org.assertj.core.api.Assertions.assertThat(result).isSameAs(finalResponse);

            // 11 total scoreTrace calls: 10 in-loop + 1 wrap-up structured. The wrap-up still
            // fires when the cap is hit — that's the safety net that prevents the model from
            // continuing prose ("Now let me check…") past the loop boundary.
            var requests = ArgumentCaptor.forClass(ChatRequest.class);
            Mockito.verify(aiProxyService, Mockito.times(11)).scoreTrace(
                    requests.capture(),
                    ArgumentMatchers.any(),
                    ArgumentMatchers.any());

            var finalSent = requests.getAllValues().get(10);
            org.assertj.core.api.Assertions.assertThat(finalSent.toolSpecifications()).isNullOrEmpty();
            var lastMessage = finalSent.messages().get(finalSent.messages().size() - 1);
            org.assertj.core.api.Assertions.assertThat(lastMessage)
                    .isInstanceOf(UserMessage.class);
            org.assertj.core.api.Assertions.assertThat(
                    ((UserMessage) lastMessage).singleText())
                    .contains("Now respond with ONLY the JSON object");
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

            scorer.score(message).block();

            var captor = ArgumentCaptor.forClass(List.class);
            verify(feedbackScoreService).scoreBatchOfThreads(captor.capture());

            assertThat(captor.getValue()).usingRecursiveComparison().isEqualTo(List.of(
                    threadScore("Relevance", BigDecimal.valueOf(4), "on-topic", project),
                    threadScore("Conciseness", new BigDecimal("3.5"), "could be tighter", project)));
        }

        @Test
        void skipsSpanFetchWhenAgenticToolsDisabled() {
            // Locks in the toggle gate: when isAgenticToolsEnabled=false, the scorer must NOT
            // call spanService.getByTraceIds — that's how thread-scope evaluations preserve
            // today's wire shape exactly (the enriched serializer omits the `spans` field on
            // an empty list, falling back to [{role, content}, ...]).
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
            // Toggle off — the scorer should not even ask the SpanService.
            Mockito.when(serviceTogglesConfig.isAgenticToolsEnabled()).thenReturn(false);

            scorer.score(message).block();

            verifyNoInteractions(spanService);
            verifyNoInteractions(attachmentService);
        }

        @Test
        void skipsScoringWhenThreadHasNoTraces() {
            var message = sampleMessage();

            when(traceService.search(anyInt(), any(TraceSearchCriteria.class))).thenReturn(Flux.empty());

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
