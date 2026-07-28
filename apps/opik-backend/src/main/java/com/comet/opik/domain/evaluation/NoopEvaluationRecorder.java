package com.comet.opik.domain.evaluation;

import com.comet.opik.api.FeedbackScoreItem;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * No-op {@link EvaluationRecorder} used when online-scoring tracing is disabled: writes nothing and
 * passes every call through unchanged, so the scoring path runs exactly as it would with no recorder.
 */
final class NoopEvaluationRecorder implements EvaluationRecorder {

    @Override
    public Mono<ChatResponse> recordLlmCall(ChatRequest request, Mono<ChatResponse> call) {
        return call;
    }

    @Override
    public Mono<String> recordToolCall(String toolName, String arguments, Mono<String> execution) {
        return execution;
    }

    @Override
    public void recordPreparation(int fetchedSpanCount, int estimatedTokens, boolean agentic) {
    }

    @Override
    public void flagBudgetExceeded() {
    }

    @Override
    public <T extends FeedbackScoreItem> Mono<List<T>> monitor(Mono<List<T>> scoring) {
        return scoring;
    }
}
