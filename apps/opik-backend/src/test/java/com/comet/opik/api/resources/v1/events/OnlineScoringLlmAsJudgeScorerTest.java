package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.PromptType;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.api.events.TraceToScoreLlmAsJudge;
import com.comet.opik.api.resources.v1.events.tools.GetTraceSpansTool;
import com.comet.opik.api.resources.v1.events.tools.MediaCategory;
import com.comet.opik.api.resources.v1.events.tools.MediaPayload;
import com.comet.opik.api.resources.v1.events.tools.ReadTool;
import com.comet.opik.api.resources.v1.events.tools.ToolRegistry;
import com.comet.opik.api.resources.v1.events.tools.TraceCompressor;
import com.comet.opik.api.resources.v1.events.tools.TraceToolContext;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.SpanService;
import com.comet.opik.domain.TestSuiteAssertionCounterService;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.WorkspaceNameService;
import com.comet.opik.domain.evaluation.EvaluationRecorder;
import com.comet.opik.domain.evaluation.OnlineEvaluationRecorder;
import com.comet.opik.domain.llm.ChatCompletionService;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.structuredoutput.ToolCallingStrategy;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import com.comet.opik.utils.JsonUtils;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.dropwizard.util.Duration;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonReactiveClient;
import org.slf4j.Logger;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnlineScoringLlmAsJudgeScorerTest {

    @Mock
    private OnlineScoringConfig onlineScoringConfig;
    @Mock
    private ServiceTogglesConfig serviceTogglesConfig;
    @Mock
    private RedissonReactiveClient redissonClient;
    @Mock
    private FeedbackScoreService feedbackScoreService;
    @Mock
    private ChatCompletionService aiProxyService;
    @Mock
    private TraceService traceService;
    @Mock
    private TestSuiteAssertionCounterService testSuiteAssertionCounterService;
    @Mock
    private LlmProviderFactory llmProviderFactory;
    @Mock
    private SpanService spanService;
    @Mock
    private WorkspaceNameService workspaceNameService;
    @Mock
    private OpikConfiguration opikConfiguration;
    @Mock
    private OnlineEvaluationRecorder onlineEvaluationRecorder;
    @Mock
    private com.comet.opik.domain.attachment.AttachmentService attachmentService;

    private MockedStatic<UserFacingLoggingFactory> mockedFactory;
    private OnlineScoringLlmAsJudgeScorer scorer;

    @BeforeEach
    void setUp() {
        mockedFactory = mockStatic(UserFacingLoggingFactory.class);
        mockedFactory.when(() -> UserFacingLoggingFactory.getLogger(any(Class.class)))
                .thenReturn(mock(org.slf4j.Logger.class));

        OnlineScoringConfig.StreamConfiguration streamConfig = new OnlineScoringConfig.StreamConfiguration();
        streamConfig.setScorer("llm_as_judge");
        streamConfig.setStreamName("stream_scoring_llm_as_judge");
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
        lenient().when(onlineScoringConfig.getAgenticToolsCharsPerToken()).thenReturn(4);
        lenient().when(onlineScoringConfig.getMaxPromptFieldChars()).thenReturn(4_000);
        lenient().when(onlineScoringConfig.getAttachmentFetchMaxRetries()).thenReturn(5);
        lenient().when(onlineScoringConfig.getAttachmentFetchRetryDelay()).thenReturn(Duration.milliseconds(300));

        ToolRegistry toolRegistry = new ToolRegistry(Set.of(
                stubTool(GetTraceSpansTool.NAME, "{}"),
                stubTool(ReadTool.NAME, "{}")));
        AgenticScoringService agenticScoringService = new AgenticScoringServiceImpl(onlineScoringConfig,
                toolRegistry);
        TraceCompressor traceCompressor = new TraceCompressor();

        scorer = new OnlineScoringLlmAsJudgeScorer(
                onlineScoringConfig,
                serviceTogglesConfig,
                redissonClient,
                feedbackScoreService,
                aiProxyService,
                traceService,
                testSuiteAssertionCounterService,
                llmProviderFactory,
                spanService,
                agenticScoringService,
                traceCompressor,
                workspaceNameService,
                opikConfiguration,
                onlineEvaluationRecorder,
                attachmentService);
    }

    @AfterEach
    void tearDown() {
        if (mockedFactory != null) {
            mockedFactory.close();
        }
    }

    @Nested
    class ShouldFetchSpansTests {

        // Truth table for the spans-fetch gate. Spans are fetched when EITHER:
        //   (a) the provider supports tool-calling, so the agentic-tools path is possible, OR
        //   (b) the inline {{spans}} / {{trace}} template path applies.
        // Goal of the gate: skip the SpanService.getByTraceIds I/O on rules that don't need spans.
        // experimentId is inert for THIS gate — it no longer widens the agentic-tools branch (it
        // still drives shouldUseAgenticTools); both values are exercised to lock that in.
        @ParameterizedTest(name = "expId={0}, provider={1}, sentinelInVars={2}, templateHasSpans={3} → expected={4}")
        @CsvSource({
                // Tool-calling provider → agentic-tools path possible → fetch to seed the read-tool
                // cache, whatever the template says.
                "true,  OPEN_AI, false, false, true",
                "false, OPEN_AI, false, false, true",
                "false, OPEN_AI, true,  true,  true",
                // Non-tool-calling provider → only the template path can trigger a fetch.
                "true,  OLLAMA,  false, false, false",
                "false, OLLAMA,  false, false, false",
                // Template references {{spans}} implicitly → fetch via the template path.
                "false, OLLAMA,  false, true,  true",
                // Sentinel-mapped variable → fetch via the template path.
                "false, OLLAMA,  true,  false, true",
                // Both branches on → still just one fetch (idempotent OR).
                "true,  OPEN_AI, true,  true,  true",
        })
        void gateMatchesTruthTable(
                boolean hasExperimentId, LlmProvider provider,
                boolean sentinelInVariables, boolean templateHasSpans, boolean expected) {
            String modelName = "gpt-test";
            TraceToScoreLlmAsJudge message = buildSpansFetchMessage(
                    hasExperimentId, sentinelInVariables, templateHasSpans);
            lenient().when(llmProviderFactory.getLlmProvider(modelName)).thenReturn(provider);

            assertThat(scorer.shouldFetchSpans(message)).isEqualTo(expected);
        }

        private TraceToScoreLlmAsJudge buildSpansFetchMessage(
                boolean hasExperimentId, boolean sentinelInVariables, boolean templateHasSpans) {
            Trace trace = Trace.builder()
                    .id(UUID.randomUUID())
                    .projectId(UUID.randomUUID())
                    .name("test-trace")
                    .startTime(Instant.now())
                    .build();
            LlmAsJudgeCode code = mock(LlmAsJudgeCode.class);
            LlmAsJudgeModelParameters modelParams = mock(LlmAsJudgeModelParameters.class);
            lenient().when(code.model()).thenReturn(modelParams);
            lenient().when(modelParams.name()).thenReturn("gpt-test");
            lenient().when(code.variables()).thenReturn(sentinelInVariables
                    ? Map.of("mySpans", "spans")
                    : Map.of());
            lenient().when(code.messages()).thenReturn(templateHasSpans
                    ? List.of(com.comet.opik.api.evaluators.LlmAsJudgeMessage.builder()
                            .role(dev.langchain4j.data.message.ChatMessageType.USER)
                            .content("Spans: {{spans}}")
                            .build())
                    : List.of());
            return new TraceToScoreLlmAsJudge(
                    trace,
                    UUID.randomUUID(),
                    "rule",
                    code,
                    "ws-1",
                    "user-1",
                    null,
                    Map.of(),
                    PromptType.MUSTACHE,
                    hasExperimentId ? UUID.randomUUID() : null,
                    "ws-name-1");
        }
    }

    @Nested
    class RoutingGateTests {

        // Truth table: experimentId × tokens >= threshold × {{trace}} × provider supports tools → useTools.
        // Tools fire when ANY of (a) the experimentId branch, (b) the size branch, or (c) the {{trace}}
        // branch is on, AND the provider supports tool-calling. Without the provider check, a
        // non-tool-calling model selected via test_suite_model metadata would crash inside the chat call
        // when the request carries tool specs (Logical bug surfaced by review of the experimentId path).
        @ParameterizedTest(name = "expId={0}, tokens={1}, threshold={2}, provider={3}, attachments={4} → expected useTools={5}")
        @CsvSource({
                // experimentId path → tools when provider supports them
                "true,  0,     50000, OPEN_AI, false, true",
                "true,  60000, 50000, OPEN_AI, false, true",
                // experimentId set BUT provider doesn't support tools → fall back to inline with a warn
                // (assertions that depend on tool-driven span inspection won't be reliable for this model;
                // we surface the misconfig loudly rather than crash inside the chat call).
                "true,  60000, 50000, OLLAMA,  false, false",
                // size-based path — both preconditions must hold
                "false, 60000, 50000, OPEN_AI, false, true",
                "false, 50000, 50000, OPEN_AI, false, true",
                // below threshold → inline
                "false, 49999, 50000, OPEN_AI, false, false",
                // provider doesn't support tool calling → inline (operator must pick a different model)
                "false, 60000, 50000, OLLAMA,  false, false",
                // no preconditions met
                "false, 0,     50000, OPEN_AI, false, false",
                // {{trace}}-driven path: provider supports tools + below size threshold → tools fire so
                // the judge can call get_attachment
                "false, 0,     50000, OPEN_AI, true,  true",
                // references {{trace}} but provider can't do tools → inline (internal warn)
                "false, 0,     50000, OLLAMA,  true,  false",
        })
        void gateMatchesTruthTable(
                boolean hasExperimentId, int estimatedTokens,
                int thresholdTokens, LlmProvider provider, boolean referencesTrace, boolean expectedUseTools) {
            String modelName = "gpt-test";
            TraceToScoreLlmAsJudge message = hasExperimentId
                    ? newMessage(UUID.randomUUID())
                    : newMessageWithoutExperimentId(UUID.randomUUID());
            lenient().when(onlineScoringConfig.getAgenticToolsThresholdTokens()).thenReturn(thresholdTokens);
            lenient().when(llmProviderFactory.getLlmProvider(modelName)).thenReturn(provider);

            boolean useTools = scorer.shouldUseAgenticTools(message, estimatedTokens, modelName, referencesTrace);

            assertThat(useTools).isEqualTo(expectedUseTools);
        }
    }

    @Test
    void addToolSpecsPreservesOriginalRequestFields() {
        // Original request carries non-message, non-tool fields (parameters here stand in for any
        // fields prepareLlmRequest may set; the fix must not drop them).
        ChatRequestParameters params = ChatRequestParameters.builder()
                .temperature(0.42)
                .maxOutputTokens(123)
                .build();
        ChatRequest original = ChatRequest.builder()
                .messages(UserMessage.from("score this"))
                .parameters(params)
                .build();

        AgenticScoringService agenticScoringService = new AgenticScoringServiceImpl(onlineScoringConfig,
                new ToolRegistry(Set.of(
                        stubTool(GetTraceSpansTool.NAME, "{}"),
                        stubTool(ReadTool.NAME, "{}"))));
        ChatRequest withTools = agenticScoringService.addToolSpecs(original, ToolChoice.REQUIRED);

        // Tool specs come from the registry (sorted alphabetically by ToolRegistry).
        assertThat(withTools.toolSpecifications())
                .extracting(ToolSpecification::name)
                .containsExactly(GetTraceSpansTool.NAME, ReadTool.NAME);
        // Original messages survive.
        assertThat(withTools.messages()).containsExactlyElementsOf(original.messages());
        // Original parameters survive — this is the regression guard for the addToolSpecs fix.
        assertThat(withTools.parameters().temperature()).isEqualTo(0.42);
        assertThat(withTools.parameters().maxOutputTokens()).isEqualTo(123);
        // Tool choice is propagated.
        assertThat(withTools.parameters().toolChoice()).isEqualTo(ToolChoice.REQUIRED);
    }

    @Test
    void handleToolCallsReturnsImmediatelyWhenNoToolRequests() {
        ChatResponse plainResponse = ChatResponse.builder()
                .aiMessage(AiMessage.from("done"))
                .build();
        ChatRequest toolRequest = ChatRequest.builder()
                .messages(UserMessage.from("score"))
                .toolSpecifications(stubSpec("any"))
                .build();
        ChatRequest structuredRequest = ChatRequest.builder()
                .messages(UserMessage.from("score"))
                .build();
        TraceToScoreLlmAsJudge message = newMessage(UUID.randomUUID());

        ChatResponse result = scorer
                .handleToolCalls(plainResponse, toolRequest, structuredRequest, message, List.of(), null, Map.of(),
                        EvaluationRecorder.NOOP, BudgetGuard.UNLIMITED)
                .block();

        assertThat(result).isSameAs(plainResponse);
        verifyNoInteractions(aiProxyService);
        verifyNoInteractions(spanService);
    }

    @Test
    void handleToolCallsAccumulatesResultsAndFinalizesWithStructuredRequestShape() {
        UUID traceId = UUID.randomUUID();
        TraceToScoreLlmAsJudge message = newMessage(traceId);

        // Initial response: model wants to call get_trace_spans.
        ToolExecutionRequest toolReq = ToolExecutionRequest.builder()
                .id("call-1")
                .name(GetTraceSpansTool.NAME)
                .arguments("{}")
                .build();
        ChatResponse initialResponse = ChatResponse.builder()
                .aiMessage(AiMessage.from(List.of(toolReq)))
                .build();
        // Round-1 response: model finishes (no more tool calls).
        ChatResponse roundOneResponse = ChatResponse.builder()
                .aiMessage(AiMessage.from("ok"))
                .build();
        // Final structured response (post-loop re-issue against structuredRequest shape).
        ChatResponse finalResponse = ChatResponse.builder()
                .aiMessage(AiMessage.from("{\"score\": true}"))
                .build();

        when(aiProxyService.scoreTrace(any(ChatRequest.class), any(), any()))
                .thenReturn(roundOneResponse, finalResponse);

        ChatRequest toolRequest = ChatRequest.builder()
                .messages(UserMessage.from("score"))
                .toolSpecifications(stubSpec(GetTraceSpansTool.NAME))
                .build();
        ChatRequest structuredRequest = ChatRequest.builder()
                .messages(UserMessage.from("score"))
                .build();

        ChatResponse result = scorer.handleToolCalls(initialResponse, toolRequest, structuredRequest, message,
                List.of(), null, Map.of(), EvaluationRecorder.NOOP, BudgetGuard.UNLIMITED).block();

        assertThat(result).isSameAs(finalResponse);

        // Two scoreTrace calls: round-1 follow-up (with tool specs) + final structured re-issue (no tool specs).
        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(aiProxyService, times(2)).scoreTrace(requests.capture(), any(), any());

        List<ChatRequest> sent = requests.getAllValues();

        // Round-1 follow-up: keeps tool specs (the distinguishing feature of in-loop calls)
        // and is a 3-element snapshot at send time (original UserMessage + the AiMessage
        // carrying tool calls + the tool-execution result). handleToolCalls defensive-copies
        // the `messages` list when building each round's request, so ArgumentCaptor's by-
        // reference capture reflects the state at THE TIME OF THAT ROUND, not the final
        // accumulated state. That copy is intentional: ChatRequestBuilder stores the list by
        // reference, so without it any async chat client reading messages after the call
        // returns would see later mutations bleed in.
        ChatRequest roundOne = sent.getFirst();
        assertThat(roundOne.toolSpecifications())
                .extracting(ToolSpecification::name)
                .containsExactly(GetTraceSpansTool.NAME);
        assertThat(roundOne.messages()).hasSize(3);
        assertThat(roundOne.messages().get(0)).isInstanceOf(UserMessage.class);
        assertThat(roundOne.messages().get(1)).isInstanceOf(AiMessage.class);
        assertThat(roundOne.messages().get(2)).isInstanceOf(ToolExecutionResultMessage.class);

        // Final re-issue: uses structuredRequest's shape (no tool specs) and includes the
        // forcing UserMessage as the last accumulated message — soft signal "stop calling
        // tools, emit only JSON now" that complements the provider-native structured output.
        // 5 elements: round-1's three + the terminal AiMessage from round-1's response (the
        // loop's no-tool-calls early return now appends it so the wrap-up keeps the assistant's
        // last turn in the conversation history) + the forcing UserMessage appended after.
        ChatRequest finalSent = sent.get(1);
        assertThat(finalSent.toolSpecifications()).isNullOrEmpty();
        assertThat(finalSent.messages()).hasSize(5);
        assertThat(finalSent.messages().get(3)).isInstanceOf(AiMessage.class);
        assertThat(((AiMessage) finalSent.messages().get(3)).text()).isEqualTo("ok");
        assertThat(finalSent.messages().get(4)).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) finalSent.messages().get(4)).singleText())
                .contains("Now respond with ONLY the JSON object");
    }

    @Test
    void handleToolCallsPropagatesScoreTraceFailureMidLoop() {
        UUID traceId = UUID.randomUUID();
        TraceToScoreLlmAsJudge message = newMessage(traceId);

        ToolExecutionRequest toolReq = ToolExecutionRequest.builder()
                .id("call-1")
                .name(GetTraceSpansTool.NAME)
                .arguments("{}")
                .build();
        ChatResponse initialResponse = ChatResponse.builder()
                .aiMessage(AiMessage.from(List.of(toolReq)))
                .build();

        // Round-1 a follow-up call fails (transient blip after the per-LLM RetryUtils policy
        // exhausted its budget). The contract: failure escapes handleToolCalls so the message
        // returns un-ACKed and Redis Streams can redeliver. Crucially, we must NOT swallow the
        // error and emit empty/partial scores — score writes are upsert-via-ReplacingMergeTree,
        // so silent failure here would mask a problem instead of triggering the retry path.
        RuntimeException providerFailure = new RuntimeException("provider 503");
        when(aiProxyService.scoreTrace(any(ChatRequest.class), any(), any()))
                .thenThrow(providerFailure);

        ChatRequest toolRequest = ChatRequest.builder()
                .messages(UserMessage.from("score"))
                .toolSpecifications(stubSpec(GetTraceSpansTool.NAME))
                .build();
        ChatRequest structuredRequest = ChatRequest.builder()
                .messages(UserMessage.from("score"))
                .build();

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> scorer.handleToolCalls(
                        initialResponse, toolRequest, structuredRequest, message, List.of(), null, Map.of(),
                        EvaluationRecorder.NOOP, BudgetGuard.UNLIMITED).block())
                .isSameAs(providerFailure);

        // Exactly one provider call attempted; the loop did not swallow + continue.
        verify(aiProxyService, times(1)).scoreTrace(any(ChatRequest.class), any(), any());
    }

    @Test
    void handleToolCallsCapsAtMaxRoundsAndStillFiresWrapUpStructuredCall() {
        UUID traceId = UUID.randomUUID();
        TraceToScoreLlmAsJudge message = newMessage(traceId);

        // Initial response (passed in by the caller) carries a tool call — round 0's tools.
        ToolExecutionRequest toolReq = ToolExecutionRequest.builder()
                .id("call")
                .name(GetTraceSpansTool.NAME)
                .arguments("{}")
                .build();
        ChatResponse toolCallingResponse = ChatResponse.builder()
                .aiMessage(AiMessage.from(List.of(toolReq)))
                .build();
        ChatResponse finalResponse = ChatResponse.builder()
                .aiMessage(AiMessage.from("{\"score\": true}"))
                .build();

        // MAX_TOOL_CALL_ROUNDS = 10 (private in the scorer; inlined here intentionally —
        // if the constant changes, this test should fail loudly to force a deliberate update).
        // Loop trace: for (round = 0; round < 10; round++) { execute tools; chatResponse =
        // scoreTrace(...) }. Since every chatResponse keeps emitting tool calls, all 10
        // iterations run, producing 10 in-loop scoreTrace calls. Then the wrap-up appends
        // the forcing UserMessage and issues 1 final structured call → 11 total invocations.
        when(aiProxyService.scoreTrace(any(ChatRequest.class), any(), any()))
                .thenReturn(toolCallingResponse, // round 1 → still tools
                        toolCallingResponse, // round 2
                        toolCallingResponse, // round 3
                        toolCallingResponse, // round 4
                        toolCallingResponse, // round 5
                        toolCallingResponse, // round 6
                        toolCallingResponse, // round 7
                        toolCallingResponse, // round 8
                        toolCallingResponse, // round 9
                        toolCallingResponse, // round 10 (still tools — cap reached)
                        finalResponse); // wrap-up structured call

        ChatRequest toolRequest = ChatRequest.builder()
                .messages(UserMessage.from("score"))
                .toolSpecifications(stubSpec(GetTraceSpansTool.NAME))
                .build();
        ChatRequest structuredRequest = ChatRequest.builder()
                .messages(UserMessage.from("score"))
                .build();

        ChatResponse result = scorer.handleToolCalls(
                toolCallingResponse, toolRequest, structuredRequest, message, List.of(), null, Map.of(),
                EvaluationRecorder.NOOP, BudgetGuard.UNLIMITED).block();

        // Result is the wrap-up structured response — wrap-up still fires when the cap is hit.
        assertThat(result).isSameAs(finalResponse);

        // 10 in-loop calls + 1 wrap-up structured call = 11 total provider invocations.
        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(aiProxyService, times(11)).scoreTrace(requests.capture(), any(), any());

        // The final (11th) call uses structuredRequest's shape — no tool specs — and the
        // forcing UserMessage is its last message. This is the contract that prevents the
        // model from continuing prose ("Now let me check...") after the cap.
        ChatRequest finalSent = requests.getAllValues().get(10);
        assertThat(finalSent.toolSpecifications()).isNullOrEmpty();
        var lastMessage = finalSent.messages().getLast();
        assertThat(lastMessage).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) lastMessage).singleText())
                .contains("Now respond with ONLY the JSON object");
    }

    @Nested
    class SurfaceInjectedMediaFailureTests {

        @Test
        void passesErrorThroughWithoutLoggingWhenNoMediaWasInjected() {
            Trace trace = Trace.builder()
                    .id(UUID.randomUUID()).projectId(UUID.randomUUID())
                    .name(UUID.randomUUID().toString()).startTime(Instant.now()).build();
            TraceToolContext ctx = TraceToolContext.forActiveTrace(
                    trace, List.of(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
            var error = new RuntimeException("original error");
            var logger = mock(Logger.class);

            assertThatThrownBy(() -> AgenticScoringServiceImpl.surfaceInjectedMediaFailure(
                    error, ctx, UUID.randomUUID().toString(), logger, Map.of()).block())
                    .isSameAs(error);
            verifyNoInteractions(logger);
        }

        @Test
        void logsUserFacingErrorAndPassesThroughWhenMediaWasInjected() {
            Trace trace = Trace.builder()
                    .id(UUID.randomUUID()).projectId(UUID.randomUUID())
                    .name(UUID.randomUUID().toString()).startTime(Instant.now()).build();
            TraceToolContext ctx = TraceToolContext.forActiveTrace(
                    trace, List.of(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
            ctx.stageMedia(MediaPayload.ofBase64(
                    UUID.randomUUID() + ".png", "image/png", MediaCategory.IMAGE, 0L, "dGVzdA=="));
            var error = new RuntimeException("model rejected media");
            var logger = mock(Logger.class);

            assertThatThrownBy(() -> AgenticScoringServiceImpl.surfaceInjectedMediaFailure(
                    error, ctx, UUID.randomUUID().toString(), logger, Map.of()).block())
                    .isSameAs(error);
            verify(logger, times(1)).error(anyString(), any(), any(), any(), any());
        }
    }

    @Nested
    class ScoringTests {

        // Minimal evaluator JSON with one scored field — valid for OnlineScoringEngine parsing.
        private static final String EVALUATOR_JSON = """
                {
                  "model": { "name": "gpt-test", "temperature": 0.3 },
                  "messages": [
                    { "role": "USER", "content": "Score this trace: {{context}}" }
                  ],
                  "schema": [
                    { "name": "Quality", "type": "DOUBLE", "description": "Quality score" }
                  ],
                  "variables": {}
                }
                """;

        // Evaluator whose prompt references {{trace}} — the declarative agentic trigger. No variable
        // binds it, so the backend's implicit detection (messagesReferenceTraceDirectly) injects the
        // trace structure.
        private static final String EVALUATOR_JSON_WITH_TRACE = """
                {
                  "model": { "name": "gpt-test", "temperature": 0.3 },
                  "messages": [
                    { "role": "USER", "content": "Score this trace: {{trace}}" }
                  ],
                  "schema": [
                    { "name": "Quality", "type": "DOUBLE", "description": "Quality score" }
                  ],
                  "variables": {}
                }
                """;

        private static final String LLM_RESPONSE = """
                {"Quality": {"score": 4.5, "reason": "good"}}
                """;

        @Test
        void traceVariableInjectsStructureWithTraceAndSpanAttachments() {
            var code = JsonUtils.readValue(EVALUATOR_JSON_WITH_TRACE, LlmAsJudgeCode.class);
            var message = buildScoringMessage(code);

            UUID spanId = UUID.randomUUID();
            // The {{trace}} structure surfaces attachments at BOTH levels: the trace's own and each
            // span's, so the judge can call get_attachment for any of them.
            String traceFileName = "trace-doc-" + RandomUtils.secure().randomInt(1, 99999999) + ".pdf";
            String spanFileName = "input-attachment-" + RandomUtils.secure().randomInt(1, 99999999)
                    + "-1782579409975-sdk.jpg";
            Span span = Span.builder()
                    .id(spanId)
                    .projectId(message.trace().projectId())
                    .traceId(message.trace().id())
                    .name("span-" + RandomStringUtils.secure().nextAlphanumeric(8))
                    .startTime(Instant.now())
                    .input(JsonUtils.getJsonNodeFromString("{\"messages\":\"hi\"}"))
                    .build();
            var traceAttachment = com.comet.opik.api.attachment.AttachmentInfo.builder()
                    .entityId(message.trace().id())
                    .entityType(com.comet.opik.api.attachment.EntityType.TRACE)
                    .fileName(traceFileName)
                    .build();
            var spanAttachment = com.comet.opik.api.attachment.AttachmentInfo.builder()
                    .entityId(spanId)
                    .entityType(com.comet.opik.api.attachment.EntityType.SPAN)
                    .fileName(spanFileName)
                    .build();

            lenient().when(onlineScoringConfig.getAgenticToolsThresholdTokens()).thenReturn(1_000_000);
            when(llmProviderFactory.getLlmProvider("gpt-test")).thenReturn(LlmProvider.OPEN_AI);
            when(llmProviderFactory.getStructuredOutputStrategy("gpt-test"))
                    .thenReturn(new ToolCallingStrategy());
            when(spanService.getByTraceIds(any())).thenReturn(Flux.just(span));
            when(attachmentService.getAttachmentInfoByEntity(
                    any(), eq(com.comet.opik.api.attachment.EntityType.TRACE), any()))
                    .thenReturn(Mono.just(List.of(traceAttachment)));
            when(attachmentService.getAttachmentInfoByEntityIds(
                    eq(com.comet.opik.api.attachment.EntityType.SPAN), any()))
                    .thenReturn(Mono.just(List.of(spanAttachment)));
            // Plain (no tool calls) response so handleToolCalls returns immediately.
            ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
            when(aiProxyService.scoreTrace(requestCaptor.capture(), any(), any()))
                    .thenReturn(ChatResponse.builder().aiMessage(AiMessage.aiMessage(LLM_RESPONSE)).build());
            when(feedbackScoreService.scoreBatchOfTraces(any())).thenReturn(Mono.empty());

            scorer.score(message).block();

            // {{trace}} engaged the agentic-tools path: the scoring request carries tool specs.
            assertThat(requestCaptor.getValue().toolSpecifications()).isNotEmpty();
            // The injected structure carries the REAL trace id, span id, and BOTH attachment file_names
            // (trace-level + span-level), so the judge can call get_attachment with correct values.
            String prompt = ((UserMessage) requestCaptor.getValue().messages().get(0)).singleText();
            assertThat(prompt).contains(message.trace().id().toString());
            assertThat(prompt).contains(spanId.toString());
            assertThat(prompt).contains(traceFileName);
            assertThat(prompt).contains(spanFileName);
        }

        @Test
        void traceStructureKeepsBodyReferencedTransientSpanAttachmentAlongsideUnrelatedPersistentOne() {
            var code = JsonUtils.readValue(EVALUATOR_JSON_WITH_TRACE, LlmAsJudgeCode.class);
            var message = buildScoringMessage(code);

            UUID spanId = UUID.randomUUID();
            // The span body references a transient (auto-stripped) attachment; the span also carries an
            // UNRELATED persistent attachment. The persistent one must not cause the referenced transient
            // to be dropped from the {{trace}} structure (regression for the entity-wide-gate bug).
            String transientFileName = "input-attachment-1-1699999999999.png";
            Span span = Span.builder()
                    .id(spanId)
                    .projectId(message.trace().projectId())
                    .traceId(message.trace().id())
                    .name("span-" + RandomStringUtils.secure().nextAlphanumeric(8))
                    .startTime(Instant.now())
                    .input(JsonUtils.getJsonNodeFromString("{\"messages\":\"see [" + transientFileName + "]\"}"))
                    .build();
            var transientAttachment = com.comet.opik.api.attachment.AttachmentInfo.builder()
                    .entityId(spanId)
                    .entityType(com.comet.opik.api.attachment.EntityType.SPAN)
                    .fileName(transientFileName)
                    .build();
            var persistentAttachment = com.comet.opik.api.attachment.AttachmentInfo.builder()
                    .entityId(spanId)
                    .entityType(com.comet.opik.api.attachment.EntityType.SPAN)
                    .fileName("diagram.png")
                    .build();

            lenient().when(onlineScoringConfig.getAgenticToolsThresholdTokens()).thenReturn(1_000_000);
            when(llmProviderFactory.getLlmProvider("gpt-test")).thenReturn(LlmProvider.OPEN_AI);
            when(llmProviderFactory.getStructuredOutputStrategy("gpt-test"))
                    .thenReturn(new ToolCallingStrategy());
            when(spanService.getByTraceIds(any())).thenReturn(Flux.just(span));
            when(attachmentService.getAttachmentInfoByEntity(
                    any(), eq(com.comet.opik.api.attachment.EntityType.TRACE), any()))
                    .thenReturn(Mono.just(List.of()));
            when(attachmentService.getAttachmentInfoByEntityIds(
                    eq(com.comet.opik.api.attachment.EntityType.SPAN), any()))
                    .thenReturn(Mono.just(List.of(persistentAttachment, transientAttachment)));
            ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
            when(aiProxyService.scoreTrace(requestCaptor.capture(), any(), any()))
                    .thenReturn(ChatResponse.builder().aiMessage(AiMessage.aiMessage(LLM_RESPONSE)).build());
            when(feedbackScoreService.scoreBatchOfTraces(any())).thenReturn(Mono.empty());

            scorer.score(message).block();

            // Both file_names survive into the injected {{trace}} structure — the unrelated persistent
            // attachment does not evict the body-referenced transient.
            String prompt = ((UserMessage) requestCaptor.getValue().messages().get(0)).singleText();
            assertThat(prompt).contains(transientFileName);
            assertThat(prompt).contains("diagram.png");
        }

        @Test
        void traceVariableAttachmentFetchErrorStillScoresWithStructure() {
            var code = JsonUtils.readValue(EVALUATOR_JSON_WITH_TRACE, LlmAsJudgeCode.class);
            var message = buildScoringMessage(code);

            UUID spanId = UUID.randomUUID();
            Span span = Span.builder()
                    .id(spanId)
                    .projectId(message.trace().projectId())
                    .traceId(message.trace().id())
                    .name("span-" + RandomStringUtils.secure().nextAlphanumeric(8))
                    .startTime(Instant.now())
                    .input(JsonUtils.getJsonNodeFromString("{\"messages\":\"hi\"}"))
                    .build();

            lenient().when(onlineScoringConfig.getAgenticToolsThresholdTokens()).thenReturn(1_000_000);
            when(llmProviderFactory.getLlmProvider("gpt-test")).thenReturn(LlmProvider.OPEN_AI);
            when(llmProviderFactory.getStructuredOutputStrategy("gpt-test"))
                    .thenReturn(new ToolCallingStrategy());
            when(spanService.getByTraceIds(any())).thenReturn(Flux.just(span));
            // Trace-attachment listing fails — onErrorReturn(List.of()) degrades to a structure without
            // attachment entries rather than blocking scoring.
            when(attachmentService.getAttachmentInfoByEntity(
                    any(), eq(com.comet.opik.api.attachment.EntityType.TRACE), any()))
                    .thenReturn(Mono.error(new RuntimeException("DB unavailable")));
            when(attachmentService.getAttachmentInfoByEntityIds(
                    eq(com.comet.opik.api.attachment.EntityType.SPAN), any()))
                    .thenReturn(Mono.just(List.of()));
            ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
            when(aiProxyService.scoreTrace(requestCaptor.capture(), any(), any()))
                    .thenReturn(ChatResponse.builder().aiMessage(AiMessage.aiMessage(LLM_RESPONSE)).build());
            when(feedbackScoreService.scoreBatchOfTraces(any())).thenReturn(Mono.empty());

            scorer.score(message).block();

            verify(aiProxyService, times(1)).scoreTrace(any(), any(), any());
            // Structure still injected (trace id + span id), just without attachment entries.
            String prompt = ((UserMessage) requestCaptor.getValue().messages().get(0)).singleText();
            assertThat(prompt).contains(message.trace().id().toString());
            assertThat(prompt).contains(spanId.toString());
        }

        @Test
        void traceAttachmentUploadRaceRetriesUntilPersistentCopyAppears() {
            var code = JsonUtils.readValue(EVALUATOR_JSON_WITH_TRACE, LlmAsJudgeCode.class);
            // Trace body references the attachment, so the scorer retries the listing until the persistent
            // (-sdk) copy lands instead of giving up on the first empty result.
            String fileName = "input-attachment-86584937-1782579409975-sdk.jpg";
            Trace trace = Trace.builder()
                    .id(UUID.randomUUID())
                    .projectId(UUID.randomUUID())
                    .name(UUID.randomUUID().toString())
                    .startTime(Instant.now())
                    .input(JsonUtils.getJsonNodeFromString("{\"q\":\"see [" + fileName + "]\"}"))
                    .build();
            var message = new TraceToScoreLlmAsJudge(
                    trace, UUID.randomUUID(), UUID.randomUUID().toString(), code,
                    UUID.randomUUID().toString(), UUID.randomUUID().toString(), null, Map.of(),
                    PromptType.MUSTACHE, null, null);
            var traceAttachment = com.comet.opik.api.attachment.AttachmentInfo.builder()
                    .entityId(trace.id())
                    .entityType(com.comet.opik.api.attachment.EntityType.TRACE)
                    .fileName(fileName)
                    .build();

            lenient().when(onlineScoringConfig.getAgenticToolsThresholdTokens()).thenReturn(1_000_000);
            when(llmProviderFactory.getLlmProvider("gpt-test")).thenReturn(LlmProvider.OPEN_AI);
            when(llmProviderFactory.getStructuredOutputStrategy("gpt-test"))
                    .thenReturn(new ToolCallingStrategy());
            when(spanService.getByTraceIds(any())).thenReturn(Flux.empty());
            // Cold lookup: first subscription sees the not-yet-uploaded state (empty), the retry sees it land.
            AtomicInteger subscriptions = new AtomicInteger();
            when(attachmentService.getAttachmentInfoByEntity(
                    any(), eq(com.comet.opik.api.attachment.EntityType.TRACE), any()))
                    .thenReturn(Mono.defer(() -> Mono.just(subscriptions.getAndIncrement() == 0
                            ? List.<com.comet.opik.api.attachment.AttachmentInfo>of()
                            : List.of(traceAttachment))));
            ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
            when(aiProxyService.scoreTrace(requestCaptor.capture(), any(), any()))
                    .thenReturn(ChatResponse.builder().aiMessage(AiMessage.aiMessage(LLM_RESPONSE)).build());
            when(feedbackScoreService.scoreBatchOfTraces(any())).thenReturn(Mono.empty());

            scorer.score(message).block();

            // First listing was empty (upload not landed); the retry resubscribed and picked up the attachment.
            assertThat(subscriptions.get()).isGreaterThanOrEqualTo(2);
            String prompt = ((UserMessage) requestCaptor.getValue().messages().get(0)).singleText();
            assertThat(prompt).contains(fileName);
        }

        @Test
        void spanAttachmentUploadRaceInTraceStructureRetriesUntilSpanCopyAppears() {
            var code = JsonUtils.readValue(EVALUATOR_JSON_WITH_TRACE, LlmAsJudgeCode.class);
            var message = buildScoringMessage(code);

            UUID spanId = UUID.randomUUID();
            String spanFileName = "input-attachment-86584937-1782579409975-sdk.jpg";
            // Span body references the attachment, so the trace structure waits for the span's persistent
            // copy to become visible before it's built (the batched lookup races attachment visibility).
            Span span = Span.builder()
                    .id(spanId)
                    .projectId(message.trace().projectId())
                    .traceId(message.trace().id())
                    .name("span-" + RandomStringUtils.secure().nextAlphanumeric(8))
                    .startTime(Instant.now())
                    .input(JsonUtils.getJsonNodeFromString("{\"q\":\"see [" + spanFileName + "]\"}"))
                    .build();
            var spanAttachment = com.comet.opik.api.attachment.AttachmentInfo.builder()
                    .entityId(spanId)
                    .entityType(com.comet.opik.api.attachment.EntityType.SPAN)
                    .fileName(spanFileName)
                    .build();

            lenient().when(onlineScoringConfig.getAgenticToolsThresholdTokens()).thenReturn(1_000_000);
            when(llmProviderFactory.getLlmProvider("gpt-test")).thenReturn(LlmProvider.OPEN_AI);
            when(llmProviderFactory.getStructuredOutputStrategy("gpt-test"))
                    .thenReturn(new ToolCallingStrategy());
            when(spanService.getByTraceIds(any())).thenReturn(Flux.just(span));
            when(attachmentService.getAttachmentInfoByEntity(
                    any(), eq(com.comet.opik.api.attachment.EntityType.TRACE), any()))
                    .thenReturn(Mono.just(List.of()));
            // Cold batched span lookup: first subscription empty (not yet persisted), the retry sees it land.
            AtomicInteger subscriptions = new AtomicInteger();
            when(attachmentService.getAttachmentInfoByEntityIds(
                    eq(com.comet.opik.api.attachment.EntityType.SPAN), any()))
                    .thenReturn(Mono.defer(() -> Mono.just(subscriptions.getAndIncrement() == 0
                            ? List.<com.comet.opik.api.attachment.AttachmentInfo>of()
                            : List.of(spanAttachment))));
            ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
            when(aiProxyService.scoreTrace(requestCaptor.capture(), any(), any()))
                    .thenReturn(ChatResponse.builder().aiMessage(AiMessage.aiMessage(LLM_RESPONSE)).build());
            when(feedbackScoreService.scoreBatchOfTraces(any())).thenReturn(Mono.empty());

            scorer.score(message).block();

            // First batched listing was empty; the retry resubscribed and the span's attachment surfaced.
            assertThat(subscriptions.get()).isGreaterThanOrEqualTo(2);
            String prompt = ((UserMessage) requestCaptor.getValue().messages().get(0)).singleText();
            assertThat(prompt).contains(spanFileName);
        }

        private TraceToScoreLlmAsJudge buildScoringMessage(LlmAsJudgeCode code) {
            Trace trace = Trace.builder()
                    .id(UUID.randomUUID())
                    .projectId(UUID.randomUUID())
                    .name(UUID.randomUUID().toString())
                    .startTime(Instant.now())
                    .build();
            return new TraceToScoreLlmAsJudge(
                    trace, UUID.randomUUID(), UUID.randomUUID().toString(), code,
                    UUID.randomUUID().toString(), UUID.randomUUID().toString(), null, Map.of(),
                    PromptType.MUSTACHE, null, null);
        }
    }

    private static ToolSpecification stubSpec(String name) {
        return ToolSpecification.builder()
                .name(name)
                .parameters(JsonObjectSchema.builder().build())
                .build();
    }

    private static com.comet.opik.api.resources.v1.events.tools.ToolExecutor stubTool(String name, String result) {
        return new com.comet.opik.api.resources.v1.events.tools.ToolExecutor() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ToolSpecification spec() {
                return stubSpec(name);
            }

            @Override
            public Mono<String> execute(String arguments,
                    com.comet.opik.api.resources.v1.events.tools.TraceToolContext ctx) {
                return Mono.just(result);
            }
        };
    }

    private static TraceToScoreLlmAsJudge newMessage(UUID traceId) {
        return buildMessage(traceId, UUID.randomUUID());
    }

    private static TraceToScoreLlmAsJudge newMessageWithoutExperimentId(UUID traceId) {
        return buildMessage(traceId, null);
    }

    private static TraceToScoreLlmAsJudge buildMessage(UUID traceId, UUID experimentId) {
        Trace trace = Trace.builder()
                .id(traceId)
                .projectId(UUID.randomUUID())
                .name("test-trace")
                .startTime(Instant.now())
                .build();
        LlmAsJudgeCode code = mock(LlmAsJudgeCode.class);
        LlmAsJudgeModelParameters modelParams = mock(LlmAsJudgeModelParameters.class);
        lenient().when(code.model()).thenReturn(modelParams);
        lenient().when(modelParams.name()).thenReturn("gpt-test");
        return new TraceToScoreLlmAsJudge(
                trace,
                UUID.randomUUID(),
                "rule",
                code,
                "ws-1",
                "user-1",
                null,
                Map.of(),
                PromptType.MUSTACHE,
                experimentId,
                "ws-name-1");
    }

    /** Silences "unused import" on Span — used implicitly through Flux<Span>. */
    @SuppressWarnings("unused")
    private static Span unused() {
        return null;
    }
}
