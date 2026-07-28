package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.Span;
import com.comet.opik.api.attachment.AttachmentInfo;
import com.comet.opik.api.attachment.EntityType;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorSpanLlmAsJudge.SpanLlmAsJudgeCode;
import com.comet.opik.api.events.SpanToScoreLlmAsJudge;
import com.comet.opik.api.resources.v1.events.tools.GetAttachmentTool;
import com.comet.opik.api.resources.v1.events.tools.ReadTool;
import com.comet.opik.api.resources.v1.events.tools.ToolExecutor;
import com.comet.opik.api.resources.v1.events.tools.ToolRegistry;
import com.comet.opik.api.resources.v1.events.tools.TraceToolContext;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.attachment.AttachmentService;
import com.comet.opik.domain.evaluation.OnlineEvaluationRecorder;
import com.comet.opik.domain.llm.ChatCompletionService;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.structuredoutput.ToolCallingStrategy;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.dropwizard.util.Duration;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Mono;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnlineScoringSpanLlmAsJudgeScorerTest {

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
    private LlmProviderFactory llmProviderFactory;
    @Mock
    private AttachmentService attachmentService;
    @Mock
    private OnlineEvaluationRecorder onlineEvaluationRecorder;

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    private MockedStatic<UserFacingLoggingFactory> mockedFactory;
    private OnlineScoringSpanLlmAsJudgeScorer scorer;
    private AgenticScoringService agenticScoringService;

    // Evaluator whose prompt references {{span}} — the declarative agentic trigger for span scope. No
    // variable binds it, so the backend's implicit detection injects the span structure.
    private static final String EVALUATOR_JSON_WITH_SPAN = """
            {
              "model": { "name": "gpt-test", "temperature": 0.3 },
              "messages": [
                { "role": "USER", "content": "Score this span: {{span}}" }
              ],
              "schema": [
                { "name": "Quality", "type": "DOUBLE", "description": "Quality score" }
              ],
              "variables": {}
            }
            """;

    // Plain evaluator with no {{span}} reference → inline path.
    private static final String EVALUATOR_JSON_INLINE = """
            {
              "model": { "name": "gpt-test", "temperature": 0.3 },
              "messages": [
                { "role": "USER", "content": "Score this span: {{input}}" }
              ],
              "schema": [
                { "name": "Quality", "type": "DOUBLE", "description": "Quality score" }
              ],
              "variables": { "input": "input" }
            }
            """;

    private static final String LLM_RESPONSE = """
            {"Quality": {"score": 4.5, "reason": "good"}}
            """;

    @BeforeEach
    void setUp() {
        mockedFactory = mockStatic(UserFacingLoggingFactory.class);
        mockedFactory.when(() -> UserFacingLoggingFactory.getLogger(any(Class.class)))
                .thenReturn(mock(org.slf4j.Logger.class));

        OnlineScoringConfig.StreamConfiguration streamConfig = new OnlineScoringConfig.StreamConfiguration();
        streamConfig.setScorer("span_llm_as_judge");
        streamConfig.setStreamName("stream_scoring_span_llm_as_judge");
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
        lenient().when(onlineScoringConfig.getMaxPromptFieldChars()).thenReturn(4_000);
        lenient().when(onlineScoringConfig.getAttachmentFetchMaxRetries()).thenReturn(5);
        lenient().when(onlineScoringConfig.getAttachmentFetchRetryDelay()).thenReturn(Duration.milliseconds(300));

        ToolRegistry toolRegistry = new ToolRegistry(Set.of(
                stubTool(ReadTool.NAME, "{}"),
                stubTool(GetAttachmentTool.NAME, "{}")));
        agenticScoringService = new AgenticScoringServiceImpl(onlineScoringConfig, toolRegistry);

        scorer = new OnlineScoringSpanLlmAsJudgeScorer(
                onlineScoringConfig,
                serviceTogglesConfig,
                redissonClient,
                feedbackScoreService,
                aiProxyService,
                traceService,
                llmProviderFactory,
                agenticScoringService,
                attachmentService,
                onlineEvaluationRecorder);
    }

    @AfterEach
    void tearDown() {
        if (mockedFactory != null) {
            mockedFactory.close();
        }
    }

    @Test
    void spanVariableInjectsStructureWithSpanAttachment() {
        var code = JsonUtils.readValue(EVALUATOR_JSON_WITH_SPAN, SpanLlmAsJudgeCode.class);
        var span = createSpan();
        var message = buildMessage(span, code);

        // {{span}} injects the span's OWN attachment — the judge fetches it with
        // get_attachment(type=span, id=<span_id>, file_name=...).
        String fileName = "input-attachment-" + RandomUtils.secure().randomInt(1, 99999999) + "-"
                + RandomUtils.secure().randomLong(1L, 9999999999999L) + ".jpg";
        var spanAttachment = AttachmentInfo.builder()
                .entityId(span.id())
                .entityType(EntityType.SPAN)
                .fileName(fileName)
                .build();

        when(serviceTogglesConfig.isAgenticToolsEnabled()).thenReturn(true);
        when(llmProviderFactory.getLlmProvider("gpt-test")).thenReturn(LlmProvider.OPEN_AI);
        when(llmProviderFactory.getStructuredOutputStrategy("gpt-test")).thenReturn(new ToolCallingStrategy());
        when(attachmentService.getAttachmentInfoByEntity(
                any(), eq(EntityType.SPAN), any()))
                .thenReturn(Mono.just(List.of(spanAttachment)));
        // Plain (no tool calls) response so handleToolCalls returns immediately.
        ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        when(aiProxyService.scoreTrace(requestCaptor.capture(), any(), any()))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.aiMessage(LLM_RESPONSE)).build());
        when(feedbackScoreService.scoreBatchOfSpans(any())).thenReturn(Mono.empty());

        scorer.score(message).block();

        // {{span}} engaged the agentic-tools path: the scoring request carries tool specs.
        assertThat(requestCaptor.getValue().toolSpecifications()).isNotEmpty();
        // The injected structure carries the REAL span id and the span-level attachment file_name.
        String prompt = ((UserMessage) requestCaptor.getValue().messages().get(0)).singleText();
        assertThat(prompt).contains(span.id().toString());
        assertThat(prompt).contains(fileName);
        // The attachment entry is self-describing (carries owner type + id) — the exact JSON shape is
        // locked by ReadToolTest; here the {{span}} value is HTML-escaped by the renderer.
    }

    @Test
    void spanKeepsBodyReferencedTransientAttachmentAlongsideUnrelatedPersistentOne() {
        var code = JsonUtils.readValue(EVALUATOR_JSON_WITH_SPAN, SpanLlmAsJudgeCode.class);
        // A transient (auto-stripped) attachment the span body references — e.g. a REST-ingested image
        // whose only copy is auto-stripped and never gets an -sdk replacement.
        String transientFileName = "input-attachment-1-1699999999999.png";
        var span = createSpanReferencing(transientFileName);
        var message = buildMessage(span, code);
        var transientAttachment = AttachmentInfo.builder()
                .entityId(span.id()).entityType(EntityType.SPAN).fileName(transientFileName).build();
        // An UNRELATED persistent attachment on the same span.
        var persistentAttachment = AttachmentInfo.builder()
                .entityId(span.id()).entityType(EntityType.SPAN).fileName("diagram.png").build();

        when(serviceTogglesConfig.isAgenticToolsEnabled()).thenReturn(true);
        when(llmProviderFactory.getLlmProvider("gpt-test")).thenReturn(LlmProvider.OPEN_AI);
        when(llmProviderFactory.getStructuredOutputStrategy("gpt-test")).thenReturn(new ToolCallingStrategy());
        when(attachmentService.getAttachmentInfoByEntity(
                any(), eq(EntityType.SPAN), any()))
                .thenReturn(Mono.just(List.of(persistentAttachment, transientAttachment)));
        ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        when(aiProxyService.scoreTrace(requestCaptor.capture(), any(), any()))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.aiMessage(LLM_RESPONSE)).build());
        when(feedbackScoreService.scoreBatchOfSpans(any())).thenReturn(Mono.empty());

        scorer.score(message).block();

        // The unrelated persistent attachment must NOT cause the body-referenced transient to be dropped:
        // both file_names survive into the injected {{span}} structure.
        String prompt = ((UserMessage) requestCaptor.getValue().messages().get(0)).singleText();
        assertThat(prompt).contains(transientFileName);
        assertThat(prompt).contains("diagram.png");
    }

    @Test
    void spanAttachmentFetchErrorStillScoresWithStructure() {
        var code = JsonUtils.readValue(EVALUATOR_JSON_WITH_SPAN, SpanLlmAsJudgeCode.class);
        var span = createSpan();
        var message = buildMessage(span, code);

        when(serviceTogglesConfig.isAgenticToolsEnabled()).thenReturn(true);
        when(llmProviderFactory.getLlmProvider("gpt-test")).thenReturn(LlmProvider.OPEN_AI);
        when(llmProviderFactory.getStructuredOutputStrategy("gpt-test")).thenReturn(new ToolCallingStrategy());
        // Attachment listing fails — onErrorReturn(List.of()) degrades to a structure without attachment
        // entries rather than blocking scoring.
        when(attachmentService.getAttachmentInfoByEntity(
                any(), eq(EntityType.SPAN), any()))
                .thenReturn(Mono.error(new RuntimeException("DB unavailable")));
        ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        when(aiProxyService.scoreTrace(requestCaptor.capture(), any(), any()))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.aiMessage(LLM_RESPONSE)).build());
        when(feedbackScoreService.scoreBatchOfSpans(any())).thenReturn(Mono.empty());

        scorer.score(message).block();

        verify(aiProxyService, times(1)).scoreTrace(any(), any(), any());
        // Structure still injected (span id), just without attachment entries.
        String prompt = ((UserMessage) requestCaptor.getValue().messages().get(0)).singleText();
        assertThat(prompt).contains(span.id().toString());
    }

    @Test
    void spanAttachmentUploadRaceRetriesUntilPersistentCopyAppears() {
        var code = JsonUtils.readValue(EVALUATOR_JSON_WITH_SPAN, SpanLlmAsJudgeCode.class);
        // The span body references the attachment, so the scorer knows one is coming and retries the
        // listing until the persistent (-sdk) copy lands instead of giving up on the first empty result.
        String fileName = "input-attachment-86584937-1782579409975-sdk.jpg";
        var span = createSpanReferencing(fileName);
        var message = buildMessage(span, code);
        var spanAttachment = AttachmentInfo.builder()
                .entityId(span.id())
                .entityType(EntityType.SPAN)
                .fileName(fileName)
                .build();

        when(serviceTogglesConfig.isAgenticToolsEnabled()).thenReturn(true);
        when(llmProviderFactory.getLlmProvider("gpt-test")).thenReturn(LlmProvider.OPEN_AI);
        when(llmProviderFactory.getStructuredOutputStrategy("gpt-test")).thenReturn(new ToolCallingStrategy());
        // Cold lookup: first subscription sees the not-yet-uploaded state (empty), the retry sees it land.
        AtomicInteger subscriptions = new AtomicInteger();
        when(attachmentService.getAttachmentInfoByEntity(
                any(), eq(EntityType.SPAN), any()))
                .thenReturn(Mono.defer(() -> Mono.just(subscriptions.getAndIncrement() == 0
                        ? List.<AttachmentInfo>of()
                        : List.of(spanAttachment))));
        ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        when(aiProxyService.scoreTrace(requestCaptor.capture(), any(), any()))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.aiMessage(LLM_RESPONSE)).build());
        when(feedbackScoreService.scoreBatchOfSpans(any())).thenReturn(Mono.empty());

        scorer.score(message).block();

        // First listing was empty (upload not landed); the retry resubscribed and picked up the attachment.
        assertThat(subscriptions.get()).isGreaterThanOrEqualTo(2);
        String prompt = ((UserMessage) requestCaptor.getValue().messages().get(0)).singleText();
        assertThat(prompt).contains(fileName);
    }

    @Test
    void listAttachmentsKeepsBodyReferencedTransientCopyWhenPersistentCopyPresent() {
        // A body-referenced auto-stripped attachment is kept even when a persistent copy coexists: the
        // body reference cannot tell a superseded twin apart from an unrelated attachment, and a transient
        // coexisting with its own -sdk replacement is not a reachable state (the backend-strip and
        // SDK-extract paths are mutually exclusive per upload). So we err toward keeping a referenced
        // attachment rather than dropping a legitimate one. Only orphaned (unreferenced) auto-stripped
        // copies are dropped when a persistent copy is present.
        var id = UUID.randomUUID();
        var transientAttachment = AttachmentInfo.builder()
                .entityId(id).entityType(EntityType.SPAN)
                .fileName("input-attachment-1-1782581642301.jpg").build();
        var persistentAttachment = AttachmentInfo.builder()
                .entityId(id).entityType(EntityType.SPAN)
                .fileName("input-attachment-86584937-1782581642686-sdk.jpg").build();
        var body = JsonUtils.getJsonNodeFromString("{\"q\":\"see [input-attachment-1-1782581642301.jpg]\"}");

        var result = agenticScoringService.listAttachmentsToleratingUploadRace(
                Mono.just(List.of(transientAttachment, persistentAttachment)), "ws-1", id, body).block();

        assertThat(result).extracting(a -> a.fileName())
                .containsExactlyInAnyOrder("input-attachment-1-1782581642301.jpg",
                        "input-attachment-86584937-1782581642686-sdk.jpg");
    }

    @Test
    void listAttachmentsKeepsAutoStrippedCopyWhenItIsTheOnlyOne() {
        // A backend-/REST-ingested image's only copy is auto-stripped and never replaced — it is the real
        // attachment and must be kept. No body reference here, so no retry latency.
        var auto = AttachmentInfo.builder()
                .entityId(UUID.randomUUID()).entityType(EntityType.SPAN)
                .fileName("input-attachment-1-1782581642301.jpg").build();

        var result = agenticScoringService.listAttachmentsToleratingUploadRace(
                Mono.just(List.of(auto)), "ws-1", UUID.randomUUID()).block();

        assertThat(result).extracting(a -> a.fileName()).containsExactly("input-attachment-1-1782581642301.jpg");
    }

    @Test
    void listAttachmentsFallsBackToTransientCopyWhenNoPersistentCopyEverAppears() {
        // API/REST upload: the body references the attachment (so the retry engages), but only the
        // auto-stripped copy ever exists. After the retry budget is exhausted we must fall back to that
        // copy rather than drop it — otherwise the API user's attachment would never be surfaced.
        var auto = AttachmentInfo.builder()
                .entityId(UUID.randomUUID()).entityType(EntityType.SPAN)
                .fileName("input-attachment-1-1782581642301.jpg").build();
        var body = JsonUtils.getJsonNodeFromString("{\"q\":\"see [input-attachment-1-1782581642301.jpg]\"}");

        var result = agenticScoringService.listAttachmentsToleratingUploadRace(
                Mono.just(List.of(auto)), "ws-1", UUID.randomUUID(), body).block();

        assertThat(result).extracting(a -> a.fileName()).containsExactly("input-attachment-1-1782581642301.jpg");
    }

    @Test
    void spanVariableWithToggleOffRendersEmptyStructureWithoutToolsOrFetch() {
        var code = JsonUtils.readValue(EVALUATOR_JSON_WITH_SPAN, SpanLlmAsJudgeCode.class);
        var span = createSpan();
        var message = buildMessage(span, code);

        // Agentic tools OFF, but the prompt references {{span}}. We must NOT fetch attachments or attach
        // tools — yet {{span}} must still render (as "{}"), never the literal sentinel word "span".
        when(serviceTogglesConfig.isAgenticToolsEnabled()).thenReturn(false);
        when(llmProviderFactory.getLlmProvider("gpt-test")).thenReturn(LlmProvider.OPEN_AI);
        when(llmProviderFactory.getStructuredOutputStrategy("gpt-test")).thenReturn(new ToolCallingStrategy());
        ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        when(aiProxyService.scoreTrace(requestCaptor.capture(), any(), any()))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.aiMessage(LLM_RESPONSE)).build());
        when(feedbackScoreService.scoreBatchOfSpans(any())).thenReturn(Mono.empty());

        scorer.score(message).block();

        assertThat(requestCaptor.getValue().toolSpecifications()).isNullOrEmpty();
        verifyNoInteractions(attachmentService);
        String prompt = ((UserMessage) requestCaptor.getValue().messages().get(0)).singleText();
        assertThat(prompt).contains("Score this span: {}");
        assertThat(prompt).doesNotContain("{{span}}");
    }

    @Test
    void spanVariableOnNonToolProviderCapsInjectedStructure() {
        // {{span}} + agentic tools ON, but the provider can't call tools → inline fallback. The injected
        // span structure (built from the full span) must be CAPPED so a large span can't overflow the
        // model's context window; without the cap the whole span input/output would be inlined verbatim.
        var code = JsonUtils.readValue(EVALUATOR_JSON_WITH_SPAN, SpanLlmAsJudgeCode.class);
        String tailMarker = "TAILMARKER" + RandomStringUtils.secure().nextAlphanumeric(12);
        String hugeText = "x".repeat(8_000) + tailMarker;
        var span = Span.builder()
                .id(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .projectName("project-" + RandomStringUtils.secure().nextAlphanumeric(8))
                .traceId(UUID.randomUUID())
                .name("span-" + RandomStringUtils.secure().nextAlphanumeric(8))
                .startTime(Instant.now())
                .input(JsonUtils.getJsonNodeFromString("{\"messages\":\"" + hugeText + "\"}"))
                .build();
        var message = buildMessage(span, code);

        when(serviceTogglesConfig.isAgenticToolsEnabled()).thenReturn(true);
        // OLLAMA does not support tool-calling → the {{span}} rule falls back to the inline path.
        when(llmProviderFactory.getLlmProvider("gpt-test")).thenReturn(LlmProvider.OLLAMA);
        when(llmProviderFactory.getStructuredOutputStrategy("gpt-test")).thenReturn(new ToolCallingStrategy());
        when(attachmentService.getAttachmentInfoByEntity(
                any(), eq(EntityType.SPAN), any()))
                .thenReturn(Mono.just(List.of()));
        ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        when(aiProxyService.scoreTrace(requestCaptor.capture(), any(), any()))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.aiMessage(LLM_RESPONSE)).build());
        when(feedbackScoreService.scoreBatchOfSpans(any())).thenReturn(Mono.empty());

        scorer.score(message).block();

        // Non-tool provider → inline fallback: no tool specs attached.
        assertThat(requestCaptor.getValue().toolSpecifications()).isNullOrEmpty();
        String prompt = ((UserMessage) requestCaptor.getValue().messages().get(0)).singleText();
        // The structure is injected (real span id) but CAPPED: the truncation marker is present and the
        // tail of the oversized input was dropped, so the prompt can't grow unbounded with span size.
        assertThat(prompt).contains(span.id().toString());
        assertThat(prompt).contains("[TRUNCATED");
        assertThat(prompt).doesNotContain(tailMarker);
    }

    @Test
    void noSpanVariableUsesInlinePathWithoutToolsOrAttachmentFetch() {
        var code = JsonUtils.readValue(EVALUATOR_JSON_INLINE, SpanLlmAsJudgeCode.class);
        var span = createSpan();
        var message = buildMessage(span, code);

        when(serviceTogglesConfig.isAgenticToolsEnabled()).thenReturn(true);
        lenient().when(llmProviderFactory.getLlmProvider("gpt-test")).thenReturn(LlmProvider.OPEN_AI);
        when(llmProviderFactory.getStructuredOutputStrategy("gpt-test")).thenReturn(new ToolCallingStrategy());
        ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        when(aiProxyService.scoreTrace(requestCaptor.capture(), any(), any()))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.aiMessage(LLM_RESPONSE)).build());
        when(feedbackScoreService.scoreBatchOfSpans(any())).thenReturn(Mono.empty());

        scorer.score(message).block();

        // No {{span}} → inline path: no tool specs, no attachment lookup.
        assertThat(requestCaptor.getValue().toolSpecifications()).isNullOrEmpty();
        verifyNoInteractions(attachmentService);
    }

    // Podam manufactures a fully-populated Span; toBuilder then pins only the fields these tests assert on
    // (id for attachment lookups, input/output for the injected {{span}} structure). Other fields keep
    // their random Podam values.
    private Span createSpan() {
        return podamFactory.manufacturePojo(Span.class).toBuilder()
                .id(UUID.randomUUID())
                .input(JsonUtils.getJsonNodeFromString("{\"messages\":\"hi\"}"))
                .output(JsonUtils.getJsonNodeFromString("{\"reply\":\"hello\"}"))
                .build();
    }

    private Span createSpanReferencing(String fileName) {
        return podamFactory.manufacturePojo(Span.class).toBuilder()
                .id(UUID.randomUUID())
                .input(JsonUtils.getJsonNodeFromString("{\"messages\":\"see [" + fileName + "]\"}"))
                .output(null)
                .build();
    }

    private static SpanToScoreLlmAsJudge buildMessage(Span span, SpanLlmAsJudgeCode code) {
        return SpanToScoreLlmAsJudge.builder()
                .span(span)
                .ruleId(UUID.randomUUID())
                .ruleName("rule")
                .llmAsJudgeCode(code)
                .workspaceId("ws-1")
                .userName("user-1")
                .build();
    }

    private static ToolExecutor stubTool(String name, String result) {
        return new ToolExecutor() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public dev.langchain4j.agent.tool.ToolSpecification spec() {
                return dev.langchain4j.agent.tool.ToolSpecification.builder()
                        .name(name)
                        .parameters(dev.langchain4j.model.chat.request.json.JsonObjectSchema.builder().build())
                        .build();
            }

            @Override
            public Mono<String> execute(String arguments,
                    TraceToolContext ctx) {
                return Mono.just(result);
            }
        };
    }
}
