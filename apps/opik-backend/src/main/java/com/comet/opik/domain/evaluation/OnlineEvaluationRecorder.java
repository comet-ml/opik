package com.comet.opik.domain.evaluation;

import com.comet.opik.api.FeedbackScoreItem;
import com.comet.opik.api.Source;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.VisibilityMode;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.observability.ObservabilityContext;
import com.comet.opik.domain.observability.ObservabilityTraceRecorder;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Online-evaluation adapter on top of the {@link ObservabilityTraceRecorder} core: maps the
 * LLM-as-judge loop into the monitoring trace/span model (OPIK-6994). One {@link Trace} is created per
 * evaluation and one {@code llm} {@link Span} per LLM round-trip; spans carry token usage and the
 * resolved model/provider, so the existing span→trace cost aggregation computes the cost for free.
 * <p>
 * Traces/spans carry {@link Source#EVALUATOR} and the trace is {@link VisibilityMode#HIDDEN}, so they
 * are kept out of the default traces view and the online-scoring samplers skip them automatically
 * ({@code Source.isLoggingSource(EVALUATOR)} is false), so the engine never evaluates its own
 * monitoring traces.
 * <p>
 * This class only orchestrates: {@link #begin} opens a recorder and {@link RealRecorder} taps the
 * scoring calls and hands the outcome to the observability core. Building the actual trace/span
 * objects is delegated to {@link EvaluationEntityFactory}; the {@link EvaluationRecorder} contract
 * exposes the lifecycle as {@code void} fire-and-forget calls and the per-round wrappers as
 * pass-through taps.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class OnlineEvaluationRecorder {

    private final @NonNull ObservabilityTraceRecorder observabilityRecorder;
    private final @NonNull LlmProviderFactory llmProviderFactory;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull EvaluationEntityFactory entityFactory;

    /** Real recorder backed by a per-evaluation {@link EvaluationContext}; writes the trace and spans. */
    @RequiredArgsConstructor
    private final class RealRecorder implements EvaluationRecorder {
        private final EvaluationContext eval;
        // Set once from the scorer's reactive chain (before monitor's finalize runs on the same
        // sequence), read once at finalize — sequential access, no synchronization needed.
        private boolean budgetExceeded;

        @Override
        public Mono<ChatResponse> recordLlmCall(ChatRequest request, Mono<ChatResponse> call) {
            return Mono.defer(() -> {
                var start = Instant.now();
                return observabilityRecorder.recordSpan(call, eval.observabilityContext(),
                        response -> entityFactory.llmSpan(eval, request, response, null, start),
                        error -> entityFactory.llmSpan(eval, request, null, error, start));
            });
        }

        @Override
        public Mono<String> recordToolCall(String toolName, String arguments, Mono<String> execution) {
            return Mono.defer(() -> {
                var start = Instant.now();
                return observabilityRecorder.recordSpan(execution, eval.observabilityContext(),
                        result -> entityFactory.toolSpan(eval, toolName, arguments, result, null, start),
                        error -> entityFactory.toolSpan(eval, toolName, arguments, null, error, start));
            });
        }

        @Override
        public void recordPreparation(int fetchedSpanCount, int estimatedTokens, boolean agentic) {
            observabilityRecorder.recordSpan(eval.observabilityContext(),
                    () -> entityFactory.preparationSpan(eval, fetchedSpanCount, estimatedTokens, agentic));
        }

        @Override
        public void flagBudgetExceeded() {
            this.budgetExceeded = true;
        }

        @Override
        public <T extends FeedbackScoreItem> Mono<List<T>> monitor(Mono<List<T>> scoring) {
            return scoring
                    .doOnNext(scores -> observabilityRecorder.recordTrace(eval.observabilityContext(),
                            () -> entityFactory.completedTrace(eval, scores, budgetExceeded)))
                    .doOnError(error -> observabilityRecorder.recordTrace(eval.observabilityContext(),
                            () -> entityFactory.failedTrace(eval, error, budgetExceeded)));
        }
    }

    /** Opens a recorder for a trace evaluation. */
    public EvaluationRecorder begin(@NonNull Trace trace, @NonNull UUID ruleId, String ruleName,
            @NonNull String modelName, @NonNull String workspaceId, @NonNull String userName) {
        return begin(EvaluatedTrace.from(trace), ruleId, ruleName, modelName, workspaceId, userName);
    }

    /** Opens a recorder for a span evaluation. */
    public EvaluationRecorder begin(@NonNull Span span, @NonNull UUID ruleId, String ruleName,
            @NonNull String modelName, @NonNull String workspaceId, @NonNull String userName) {
        return begin(EvaluatedSpan.from(span), ruleId, ruleName, modelName, workspaceId, userName);
    }

    /**
     * Opens an evaluation recorder for any {@link EvaluatedSubject} (used directly for threads, which
     * have no single domain object). Pure (no I/O): generates the parent trace id, resolves the
     * model/provider once and captures the start time; the spans and parent trace are written later,
     * fire-and-forget, by the {@link RealRecorder}.
     */
    public EvaluationRecorder begin(@NonNull EvaluatedSubject subject, @NonNull UUID ruleId, String ruleName,
            @NonNull String modelName, @NonNull String workspaceId, @NonNull String userName) {
        var resolvedModelInfo = llmProviderFactory.getResolvedModelInfo(modelName);
        var eval = EvaluationContext.builder()
                .traceId(idGenerator.generateId())
                .evaluatedIdKey(subject.evaluatedIdKey())
                .evaluatedId(subject.id())
                .evaluatedProjectId(subject.projectId())
                .projectName(subject.projectName())
                .evaluatedName(subject.name())
                .evaluatedInput(subject.input())
                .evaluatedOutput(subject.output())
                .ruleId(ruleId)
                .ruleName(ruleName)
                .modelName(modelName)
                .actualModel(resolvedModelInfo.actualModel())
                .provider(resolvedModelInfo.provider())
                .observabilityContext(new ObservabilityContext(workspaceId, userName))
                .startTime(Instant.now())
                .build();
        return new RealRecorder(eval);
    }
}
