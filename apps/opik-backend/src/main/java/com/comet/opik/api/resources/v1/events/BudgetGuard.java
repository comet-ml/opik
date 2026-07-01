package com.comet.opik.api.resources.v1.events;

import com.comet.opik.domain.cost.CostService;
import com.comet.opik.domain.evaluation.LlmUsageExtractor;
import com.comet.opik.domain.llm.LlmProviderFactory;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;

/**
 * Per-evaluation spend guard for the LLM-as-judge online scorers. Owns the budget limit, the running
 * USD spend, and all the cost math, so neither the scorers nor {@link ToolCallLoop} touch pricing —
 * they only {@link #track(Mono)} each LLM call and ask {@link #shouldWrapUp()} between agent turns.
 *
 * <p>The budget is a <b>soft wrap-up trigger, not a hard ceiling</b>: {@link #shouldWrapUp()} turns
 * true the moment cumulative spend reaches the limit, signalling the loop to stop starting new turns
 * and do its wrap-up call. That wrap-up call is charged too, so actual spend is expected to overshoot
 * the limit slightly — by design. There is no per-call pre-estimation and no hard kill.
 *
 * <p>Cost is computed fully in-process via {@link CostService#calculateCost} (a static price table
 * loaded once at class-load) from the {@link ChatResponse}'s own token usage — no DB / network /
 * provider round-trip. Everything is fail-open: a resolution or pricing failure degrades to
 * "no limit" and never disturbs the scoring call.
 *
 * <p>A limited guard is created once per evaluation and mutated sequentially on the reactive chain
 * (one LLM call completes before the next starts), so the plain {@code spentUsd} field is safe —
 * same reasoning as {@link ToolCallLoop.Budget}. {@link #UNLIMITED} is a shared immutable singleton:
 * {@link #track(Mono)} short-circuits before charging and {@link #shouldWrapUp()} is always false, so
 * it is never mutated.
 */
@Slf4j
public final class BudgetGuard {

    /** No-limit guard: passes calls through untouched and never triggers wrap-up. */
    public static final BudgetGuard UNLIMITED = new BudgetGuard(null, null, null);

    private final BigDecimal limitUsd;
    private final String model;
    private final String provider;
    private BigDecimal spentUsd = BigDecimal.ZERO;

    private BudgetGuard(BigDecimal limitUsd, String model, String provider) {
        this.limitUsd = limitUsd;
        this.model = model;
        this.provider = provider;
    }

    /**
     * Builds a guard for {@code maxCostUsd} (returns {@link #UNLIMITED} when null or non-positive).
     * Resolves the {@code (actualModel, provider)} pair once here so pricing on every subsequent turn
     * is a pure lookup. Fails open to {@link #UNLIMITED} if the model can't be resolved.
     *
     * <p>A non-positive limit is treated as "no limit" rather than enforced: {@code @Positive} on the
     * DTO does not run (the polymorphic {@code code} field is not {@code @Valid}-cascaded), so a
     * {@code 0}/negative value can reach here from the API. Enforcing it verbatim would make
     * {@link #shouldWrapUp()} true on the first turn and silently wrap up every evaluation with no
     * real work — worse than ignoring it.
     */
    public static BudgetGuard create(BigDecimal maxCostUsd, String modelName,
            LlmProviderFactory llmProviderFactory) {
        if (maxCostUsd == null || maxCostUsd.signum() <= 0) {
            return UNLIMITED;
        }
        try {
            var resolved = llmProviderFactory.getResolvedModelInfo(modelName);
            return new BudgetGuard(maxCostUsd, resolved.actualModel(), resolved.provider());
        } catch (Exception exception) {
            log.warn("Failed to resolve model '{}' for budget guard; proceeding without a spend limit",
                    modelName, exception);
            return UNLIMITED;
        }
    }

    /** Taps the LLM call to charge its cost, returning the response unchanged. Pass-through when unlimited. */
    public Mono<ChatResponse> track(Mono<ChatResponse> call) {
        if (limitUsd == null) {
            return call;
        }
        return call.doOnNext(this::charge);
    }

    private void charge(ChatResponse response) {
        try {
            var usage = LlmUsageExtractor.toUsageMap(response);
            if (usage == null) {
                return;
            }
            spentUsd = spentUsd.add(CostService.calculateCost(model, provider, usage, null));
        } catch (Exception exception) {
            log.warn("Failed to charge evaluation budget; continuing without this call's cost", exception);
        }
    }

    /** True once cumulative spend has reached the limit — the signal to begin wrapping up. */
    public boolean shouldWrapUp() {
        return limitUsd != null && spentUsd.compareTo(limitUsd) >= 0;
    }

    public BigDecimal spentUsd() {
        return spentUsd;
    }

    public BigDecimal limitUsd() {
        return limitUsd;
    }
}
