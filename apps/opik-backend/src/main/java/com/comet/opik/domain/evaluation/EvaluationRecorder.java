package com.comet.opik.domain.evaluation;

import com.comet.opik.api.FeedbackScoreItem;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Sink for the telemetry of one online evaluation. The scorer holds a recorder and calls it
 * unconditionally, so the scoring path carries no {@code if (tracing) ...} branching. When monitoring
 * is disabled the scorer uses {@link #NOOP}, which writes nothing and passes calls through unchanged.
 * <p>
 * The contract enforces the safe fire-and-forget protocol structurally:
 * <ul>
 *   <li>{@link #recordPreparation} returns {@code void} — there is no {@link Mono} for a caller to
 *       compose into its business chain, so it can never gate a value or alter an error.</li>
 *   <li>{@link #recordLlmCall}/{@link #recordToolCall} wrap an in-flight call and return exactly what
 *       that call emits — the span is written as a side effect, never by reactive glue at the call
 *       site.</li>
 *   <li>{@link #monitor} is the single finalize entry point: it taps the scoring result and writes the
 *       parent trace on success or failure. The scorer never wires the success/error finalize itself,
 *       so it cannot place it wrong or forget a branch.</li>
 * </ul>
 */
public interface EvaluationRecorder {

    /** Writes nothing; passes calls through. Used when monitoring is disabled. */
    EvaluationRecorder NOOP = new NoopEvaluationRecorder();

    /** Records one LLM round as a span; returns the response unchanged (or the error, unchanged). */
    Mono<ChatResponse> recordLlmCall(ChatRequest request, Mono<ChatResponse> call);

    /** Records one agentic tool execution as a {@code tool} span; returns the result unchanged. */
    Mono<String> recordToolCall(String toolName, String arguments, Mono<String> execution);

    /**
     * Records the upfront retrieval + context-assembly phase (span fetch, size estimate, mode
     * decision) as a {@code general} span preceding the first LLM round. Fire-and-forget.
     */
    void recordPreparation(int fetchedSpanCount, int estimatedTokens, boolean agentic);

    /**
     * Marks that the per-evaluation spend budget was reached and the agentic loop wrapped up early.
     * The finalized parent trace is tagged so users can filter for evaluations that hit their budget.
     * Fire-and-forget; no-op when monitoring is disabled.
     */
    void flagBudgetExceeded();

    /**
     * Taps the scoring computation to finalize the parent trace: on success with the produced scores,
     * on error with the error. Returns the scoring result unchanged (same value, same error), so the
     * scorer composes it transparently and the trace write stays fire-and-forget.
     */
    <T extends FeedbackScoreItem> Mono<List<T>> monitor(Mono<List<T>> scoring);
}
