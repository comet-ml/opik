package com.comet.opik.domain;

import com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import com.comet.opik.api.Source;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.VisibilityMode;
import com.comet.opik.domain.OnlineScoringTracePersistence.EvaluatedSubject;
import com.comet.opik.domain.OnlineScoringTracePersistence.EvaluationRecorder;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.LlmProviderFactory.ResolvedModelInfo;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OnlineScoringTracePersistenceTest {

    private TraceService traceService;
    private SpanService spanService;
    private OnlineScoringTracePersistence persistence;

    @BeforeEach
    void setUp() {
        traceService = mock(TraceService.class);
        spanService = mock(SpanService.class);
        var llmProviderFactory = mock(LlmProviderFactory.class);
        var idGenerator = mock(IdGenerator.class);

        when(idGenerator.generateId()).thenAnswer(invocation -> UUID.randomUUID());
        when(llmProviderFactory.getResolvedModelInfo(anyString()))
                .thenReturn(new ResolvedModelInfo("claude-actual", "anthropic"));

        persistence = new OnlineScoringTracePersistence(traceService, spanService, llmProviderFactory, idGenerator);
    }

    private EvaluationRecorder recorder() {
        return persistence.begin(
                new EvaluatedSubject(EvaluatedSubject.Kind.TRACE, "trace-1", UUID.randomUUID(), "proj", "name", null,
                        null),
                UUID.randomUUID(), "rule", "model", "workspace", "user");
    }

    private void stubSpanWrites() {
        when(spanService.create(any(Span.class))).thenReturn(Mono.just(UUID.randomUUID()));
    }

    private void stubTraceWrites() {
        when(traceService.create(any(Trace.class))).thenReturn(Mono.just(UUID.randomUUID()));
    }

    private Span capturedSpan() {
        var captor = ArgumentCaptor.forClass(Span.class);
        verify(spanService).create(captor.capture());
        return captor.getValue();
    }

    private Trace capturedTrace() {
        var captor = ArgumentCaptor.forClass(Trace.class);
        verify(traceService).create(captor.capture());
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

        assertThat(capturedSpan().usage())
                .containsEntry("prompt_tokens", 100)
                .containsEntry("completion_tokens", 20)
                .containsEntry("cache_creation_input_tokens", 30)
                .containsEntry("cache_read_input_tokens", 40);
    }

    @Test
    void capturesOpenAiCachedTokensInSpanUsage() {
        stubSpanWrites();
        var usage = OpenAiTokenUsage.builder()
                .inputTokenCount(200).outputTokenCount(50).totalTokenCount(250)
                .inputTokensDetails(OpenAiTokenUsage.InputTokensDetails.builder().cachedTokens(75).build())
                .build();

        recorder().recordLlmCall(request(), Mono.just(responseWith(usage))).block();

        assertThat(capturedSpan().usage())
                .containsEntry("prompt_tokens", 200)
                .containsEntry("completion_tokens", 50)
                .containsEntry("cache_read_input_tokens", 75)
                .doesNotContainKey("cache_creation_input_tokens");
    }

    @Test
    void capturesGeminiCachedContentTokensInSpanUsage() {
        stubSpanWrites();
        var usage = GoogleAiGeminiTokenUsage.builder()
                .inputTokenCount(300).outputTokenCount(60).cachedContentTokenCount(120)
                .build();

        recorder().recordLlmCall(request(), Mono.just(responseWith(usage))).block();

        assertThat(capturedSpan().usage())
                .containsEntry("prompt_tokens", 300)
                .containsEntry("cache_read_input_tokens", 120);
    }

    @Test
    void omitsCacheKeysWhenProviderReportsNone() {
        stubSpanWrites();
        var usage = AnthropicTokenUsage.builder().inputTokenCount(10).outputTokenCount(5).build();

        recorder().recordLlmCall(request(), Mono.just(responseWith(usage))).block();

        assertThat(capturedSpan().usage())
                .containsEntry("prompt_tokens", 10)
                .doesNotContainKeys("cache_read_input_tokens", "cache_creation_input_tokens");
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

        StepVerifier.create(recorder().fail(new IllegalStateException("bad"))).verifyComplete();

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

        StepVerifier.create(recorder().complete(scores)).verifyComplete();

        var trace = capturedTrace();
        assertThat(trace.source()).isEqualTo(Source.EVALUATOR);
        assertThat(trace.visibilityMode()).isEqualTo(VisibilityMode.HIDDEN);
        assertThat(trace.output().toString()).contains("Relevance").contains("relevant");
    }

    @Test
    void noopRecorderWritesNothingAndPassesResponseThrough() {
        var response = responseWith(AnthropicTokenUsage.builder().inputTokenCount(1).build());

        var result = EvaluationRecorder.NOOP.recordLlmCall(request(), Mono.just(response)).block();

        assertThat(result).isSameAs(response);
        verifyNoInteractions(spanService, traceService);
    }
}
