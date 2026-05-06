package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.PromptType;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.api.events.TraceToScoreLlmAsJudge;
import com.comet.opik.api.resources.v1.events.tools.GetTraceSpansTool;
import com.comet.opik.api.resources.v1.events.tools.ReadTool;
import com.comet.opik.api.resources.v1.events.tools.ToolRegistry;
import com.comet.opik.api.resources.v1.events.tools.TraceCompressor;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.SpanService;
import com.comet.opik.domain.TestSuiteAssertionCounterService;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.WorkspaceNameService;
import com.comet.opik.domain.llm.ChatCompletionService;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

        ToolRegistry toolRegistry = new ToolRegistry(Set.of(
                stubTool(GetTraceSpansTool.NAME, "{}"),
                stubTool(ReadTool.NAME, "{}")));
        TraceCompressor traceCompressor = new TraceCompressor();

        scorer = new OnlineScoringLlmAsJudgeScorer(
                onlineScoringConfig,
                redissonClient,
                feedbackScoreService,
                aiProxyService,
                traceService,
                testSuiteAssertionCounterService,
                llmProviderFactory,
                spanService,
                toolRegistry,
                traceCompressor,
                workspaceNameService,
                opikConfiguration);
    }

    @AfterEach
    void tearDown() {
        if (mockedFactory != null) {
            mockedFactory.close();
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

        ChatRequest withTools = scorer.addToolSpecs(original, ToolChoice.REQUIRED);

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

        ChatResponse result = scorer.handleToolCalls(plainResponse, toolRequest, structuredRequest, message);

        assertThat(result).isSameAs(plainResponse);
        verifyNoInteractions(aiProxyService);
        verifyNoInteractions(spanService);
    }

    @Test
    void handleToolCallsAccumulatesResultsAndFinalizesWithStructuredRequestShape() {
        UUID traceId = UUID.randomUUID();
        TraceToScoreLlmAsJudge message = newMessage(traceId);

        // Spans for the active trace (empty list is fine — the pre-seed just caches an empty composite).
        when(spanService.getByTraceIds(Set.of(traceId))).thenReturn(Flux.empty());

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

        ChatResponse result = scorer.handleToolCalls(initialResponse, toolRequest, structuredRequest, message);

        assertThat(result).isSameAs(finalResponse);

        // Two scoreTrace calls: round-1 follow-up (with tool specs) + final structured re-issue (no tool specs).
        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(aiProxyService, times(2)).scoreTrace(requests.capture(), any(), any());

        List<ChatRequest> sent = requests.getAllValues();

        // Round-1 follow-up: keeps tool specs (the distinguishing feature of in-loop calls).
        // Note on size: handleToolCalls reuses the same `messages` ArrayList for the round-1
        // follow-up and the final re-issue, then appends the forcing UserMessage after the
        // loop. ArgumentCaptor captures the request by reference, so by assertion time the
        // captured round-1 messages list reflects the final 4-element state (original
        // UserMessage + AiMessage + ToolExecutionResultMessage + forcing UserMessage). We
        // assert the shape that's still distinguishable: tool specs presence + ordering of
        // the first three message types.
        ChatRequest roundOne = sent.getFirst();
        assertThat(roundOne.toolSpecifications())
                .extracting(ToolSpecification::name)
                .containsExactly(GetTraceSpansTool.NAME);
        assertThat(roundOne.messages()).hasSize(4);
        assertThat(roundOne.messages().get(0)).isInstanceOf(UserMessage.class);
        assertThat(roundOne.messages().get(1)).isInstanceOf(AiMessage.class);
        assertThat(roundOne.messages().get(2)).isInstanceOf(ToolExecutionResultMessage.class);

        // Final re-issue: uses structuredRequest's shape (no tool specs). The forcing
        // UserMessage is the last accumulated message — soft signal "stop calling tools,
        // emit only JSON now" that complements the provider-native structured output.
        ChatRequest finalSent = sent.get(1);
        assertThat(finalSent.toolSpecifications()).isNullOrEmpty();
        assertThat(finalSent.messages()).hasSize(4);
        assertThat(finalSent.messages()).isEqualTo(roundOne.messages());
        assertThat(finalSent.messages().get(3)).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) finalSent.messages().get(3)).singleText())
                .contains("Now respond with ONLY the JSON object");
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
            public String execute(String arguments,
                    com.comet.opik.api.resources.v1.events.tools.TraceToolContext ctx) {
                return result;
            }
        };
    }

    private static TraceToScoreLlmAsJudge newMessage(UUID traceId) {
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
                UUID.randomUUID());
    }

    /** Silences "unused import" on Span — used implicitly through Flux<Span>. */
    @SuppressWarnings("unused")
    private static Span unused() {
        return null;
    }
}
