package com.comet.opik.domain.evaluation;

import com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import com.comet.opik.api.Source;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.VisibilityMode;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.SpanService;
import com.comet.opik.domain.SpanType;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.LlmProviderFactory.ResolvedModelInfo;
import com.comet.opik.domain.observability.ObservabilityTraceRecorder;
import com.comet.opik.infrastructure.ResponseFormattingConfig;
import com.comet.opik.utils.JsonUtils;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.googleai.GoogleAiGeminiTokenUsage;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * High-value unit test for the trace/span MAPPING and the fire-and-forget write contract — the only
 * part of the recorder reachable without a live LLM-judge run against a real provider. End-to-end
 * behavior (sampling, real persistence) is covered by the scorer integration tests. TraceService /
 * SpanService / LlmProviderFactory are external collaborators and are mocked; the IdGenerator is the
 * real time-ordered (UUID v7) implementation from the test factory, not a mock, so ids match prod.
 */
class OnlineEvaluationRecorderTest {

    private final TraceService traceService = mock(TraceService.class);
    private final SpanService spanService = mock(SpanService.class);
    private final LlmProviderFactory llmProviderFactory = mock(LlmProviderFactory.class);
    private final IdGenerator idGenerator = TestIdGeneratorFactory.create();
    private final ResponseFormattingConfig responseFormattingConfig = new ResponseFormattingConfig();
    private final OnlineEvaluationRecorder onlineEvaluationRecorder = new OnlineEvaluationRecorder(
            new ObservabilityTraceRecorder(traceService, spanService), llmProviderFactory, idGenerator,
            new EvaluationEntityFactory(idGenerator, responseFormattingConfig));

    @BeforeEach
    void setUp() {
        when(llmProviderFactory.getResolvedModelInfo(anyString()))
                .thenReturn(new ResolvedModelInfo("claude-actual", "anthropic"));
    }

    private EvaluationRecorder recorder() {
        return onlineEvaluationRecorder.begin(
                EvaluatedTrace.builder().id("trace-1").projectId(UUID.randomUUID()).projectName("proj")
                        .name("name").build(),
                UUID.randomUUID(), "rule", "model", "workspace", "user");
    }

    private void stubSpanWrites() {
        when(spanService.create(any(Span.class))).thenReturn(Mono.just(idGenerator.generateId()));
    }

    private void stubTraceWrites() {
        when(traceService.create(any(Trace.class))).thenReturn(Mono.just(idGenerator.generateId()));
    }

    // Writes are fire-and-forget on a separate scheduler; wait for the create call before capturing.
    private Span capturedSpan() {
        var captor = ArgumentCaptor.forClass(Span.class);
        verify(spanService, timeout(5_000)).create(captor.capture());
        return captor.getValue();
    }

    private Trace capturedTrace() {
        var captor = ArgumentCaptor.forClass(Trace.class);
        verify(traceService, timeout(5_000)).create(captor.capture());
        return captor.getValue();
    }

    private static ChatRequest request() {
        return ChatRequest.builder().messages(UserMessage.from("score this")).build();
    }

    private static ChatResponse responseWith(dev.langchain4j.model.output.TokenUsage usage) {
        return ChatResponse.builder().aiMessage(AiMessage.from("ok")).tokenUsage(usage).build();
    }

    @Test
    void capturesAnthropicCacheTokensInSpanUsage() {
        stubSpanWrites();
        var usage = AnthropicTokenUsage.builder()
                .inputTokenCount(100).outputTokenCount(20)
                .cacheCreationInputTokens(30).cacheReadInputTokens(40)
                .build();

        recorder().recordLlmCall(request(), Mono.just(responseWith(usage))).block();

        assertThat(capturedSpan().usage()).isEqualTo(Map.of(
                "prompt_tokens", 100, "completion_tokens", 20, "total_tokens", 120,
                "cache_creation_input_tokens", 30, "cache_read_input_tokens", 40));
    }

    @Test
    void capturesOpenAiCachedTokensInSpanUsage() {
        stubSpanWrites();
        var usage = OpenAiTokenUsage.builder()
                .inputTokenCount(200).outputTokenCount(50).totalTokenCount(250)
                .inputTokensDetails(OpenAiTokenUsage.InputTokensDetails.builder().cachedTokens(75).build())
                .build();

        recorder().recordLlmCall(request(), Mono.just(responseWith(usage))).block();

        assertThat(capturedSpan().usage()).isEqualTo(Map.of(
                "prompt_tokens", 200, "completion_tokens", 50, "total_tokens", 250,
                "cache_read_input_tokens", 75));
    }

    @Test
    void capturesGeminiCachedContentTokensInSpanUsage() {
        stubSpanWrites();
        var usage = GoogleAiGeminiTokenUsage.builder()
                .inputTokenCount(300).outputTokenCount(60).cachedContentTokenCount(120)
                .build();

        recorder().recordLlmCall(request(), Mono.just(responseWith(usage))).block();

        assertThat(capturedSpan().usage()).isEqualTo(Map.of(
                "prompt_tokens", 300, "completion_tokens", 60, "cache_read_input_tokens", 120));
    }

    @Test
    void omitsCacheKeysWhenProviderReportsNone() {
        stubSpanWrites();
        var usage = AnthropicTokenUsage.builder().inputTokenCount(10).outputTokenCount(5).build();

        recorder().recordLlmCall(request(), Mono.just(responseWith(usage))).block();

        assertThat(capturedSpan().usage()).isEqualTo(Map.of(
                "prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15));
    }

    @Test
    void recordsErrorOnSpanAndRepropagatesWhenLlmCallFails() {
        stubSpanWrites();
        var boom = new RuntimeException("provider down");

        StepVerifier.create(recorder().recordLlmCall(request(), Mono.error(boom)))
                .expectErrorMatches(error -> error == boom)
                .verify();

        var span = capturedSpan();
        assertThat(span.errorInfo()).isNotNull();
        assertThat(span.errorInfo().exceptionType()).isEqualTo("RuntimeException");
        assertThat(span.source()).isEqualTo(Source.EVALUATOR);
    }

    @Test
    void finalizesHiddenEvaluatorTraceWithErrorOnFail() {
        stubTraceWrites();
        var boom = new IllegalStateException("bad");

        // monitor() taps the scoring result: on error it finalizes the trace and re-propagates unchanged.
        StepVerifier.create(recorder().monitor(Mono.<List<FeedbackScoreBatchItem>>error(boom)))
                .expectErrorMatches(error -> error == boom)
                .verify();

        var trace = capturedTrace();
        assertThat(trace.errorInfo()).isNotNull();
        assertThat(trace.source()).isEqualTo(Source.EVALUATOR);
        assertThat(trace.visibilityMode()).isEqualTo(VisibilityMode.HIDDEN);
    }

    @Test
    void finalizesHiddenEvaluatorTraceWithScoresOnComplete() {
        stubTraceWrites();
        var scores = List.of(FeedbackScoreBatchItem.builder()
                .name("Relevance").value(BigDecimal.valueOf(0.8)).reason("relevant").build());

        // monitor() passes the scores through unchanged and finalizes the trace on success.
        var result = recorder().monitor(Mono.just(scores)).block();
        assertThat(result).isSameAs(scores);

        var trace = capturedTrace();
        assertThat(trace.source()).isEqualTo(Source.EVALUATOR);
        assertThat(trace.visibilityMode()).isEqualTo(VisibilityMode.HIDDEN);
        assertThat(trace.output().toString()).contains("Relevance").contains("relevant");
        // No wrap-up happened -> no budget tag.
        assertThat(trace.tags()).isNull();
    }

    @Test
    void tagsFinalizedTraceWhenBudgetExceededFlagged() {
        stubTraceWrites();
        var scores = List.of(FeedbackScoreBatchItem.builder()
                .name("Relevance").value(BigDecimal.valueOf(0.8)).reason("relevant").build());

        var recorder = recorder();
        // The scorer flags this once the spend budget trips and the agentic loop wraps up early.
        recorder.flagBudgetExceeded();
        var result = recorder.monitor(Mono.just(scores)).block();

        // Flagging the budget must not alter monitor()'s pass-through contract.
        assertThat(result).isSameAs(scores);
        assertThat(capturedTrace().tags()).containsExactly("budget_exceeded");
    }

    @Test
    void tagsFinalizedTraceWhenBudgetExceededFlaggedOnErrorPath() {
        stubTraceWrites();
        var boom = new IllegalStateException("bad");

        var recorder = recorder();
        // Budget can trip and the scoring Mono still error afterwards — the failed trace must carry
        // the tag too, since failedTrace() applies it.
        recorder.flagBudgetExceeded();
        StepVerifier.create(recorder.monitor(Mono.<List<FeedbackScoreBatchItem>>error(boom)))
                .expectErrorMatches(error -> error == boom)
                .verify();

        assertThat(capturedTrace().tags()).containsExactly("budget_exceeded");
    }

    @Test
    void prepareSpanCarriesEvaluatedInputOutputForPrettyRenderingAndMetadata() {
        stubSpanWrites();
        var projectId = UUID.randomUUID();
        var evaluatedInput = JsonUtils.createObjectNode();
        evaluatedInput.put("input", "What is 2+2?");
        var evaluatedOutput = JsonUtils.createObjectNode();
        evaluatedOutput.put("output", "4");
        var recorder = onlineEvaluationRecorder.begin(
                EvaluatedTrace.builder().id("trace-9").projectId(projectId).projectName("proj").name("Q&A")
                        .input(evaluatedInput).output(evaluatedOutput).build(),
                UUID.randomUUID(), "rule", "claude-x", "workspace", "user");

        recorder.recordPreparation(3, 1234, false);

        var span = capturedSpan();
        assertThat(span.name()).isEqualTo("prepare_evaluation");
        assertThat(span.type()).isEqualTo(SpanType.general);
        assertThat(span.source()).isEqualTo(Source.EVALUATOR);
        // Evaluated input/output land on the span's own input/output (so the UI pretty-renders them)
        // and stay structurally intact, not as an escaped blob.
        assertThat(span.input()).isEqualTo(evaluatedInput);
        assertThat(span.output()).isEqualTo(evaluatedOutput);
        // Bookkeeping lives in metadata, not in input/output — assert the whole metadata object.
        var expectedMetadata = JsonUtils.createObjectNode();
        expectedMetadata.put("evaluated_trace_id", "trace-9");
        expectedMetadata.put("evaluated_project_id", projectId.toString());
        expectedMetadata.put("evaluated_name", "Q&A");
        expectedMetadata.put("model", "claude-x");
        expectedMetadata.put("fetched_span_count", 3);
        expectedMetadata.put("estimated_tokens", 1234);
        expectedMetadata.put("mode", "inline");
        assertThat(span.metadata()).isEqualTo(expectedMetadata);
    }

    @Test
    void noopRecorderWritesNothingAndPassesResponseThrough() {
        var response = responseWith(AnthropicTokenUsage.builder().inputTokenCount(1).build());

        var result = EvaluationRecorder.NOOP.recordLlmCall(request(), Mono.just(response)).block();

        assertThat(result).isSameAs(response);
        verifyNoInteractions(spanService, traceService);
    }
}
