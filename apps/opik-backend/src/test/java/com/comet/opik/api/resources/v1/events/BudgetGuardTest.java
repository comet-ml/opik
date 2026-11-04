package com.comet.opik.api.resources.v1.events;

import com.comet.opik.domain.cost.CostService;
import com.comet.opik.domain.cost.CustomModelPrice;
import com.comet.opik.domain.cost.ModelPriceService;
import com.comet.opik.domain.cost.PriceLayers;
import com.comet.opik.domain.evaluation.LlmUsageExtractor;
import com.comet.opik.domain.llm.LlmProviderFactory;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BudgetGuardTest {

    private static ChatResponse responseWith(int inputTokens, int outputTokens) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from("ok"))
                .tokenUsage(OpenAiTokenUsage.builder()
                        .inputTokenCount(inputTokens)
                        .outputTokenCount(outputTokens)
                        .totalTokenCount(inputTokens + outputTokens)
                        .build())
                .build();
    }

    private static LlmProviderFactory factoryFor(String model, String provider) {
        var factory = mock(LlmProviderFactory.class);
        when(factory.getResolvedModelInfo(model))
                .thenReturn(new LlmProviderFactory.ResolvedModelInfo(model, provider));
        return factory;
    }

    @Test
    void noBudgetSetReturnsSharedUnlimitedGuardThatNeverTrips() {
        // A bare factory (no stubbing): create() short-circuits to UNLIMITED before resolving the model,
        // so model resolution never participates in this path.
        var guard = BudgetGuard.create(null, "gpt-4o", mock(LlmProviderFactory.class), PriceLayers.BUILT_IN_ONLY);

        assertThat(guard).isSameAs(BudgetGuard.UNLIMITED);

        var response = responseWith(1_000_000, 1_000_000);
        // Pass-through: same instance back, nothing charged, never wraps up.
        assertThat(guard.track(Mono.just(response)).block()).isSameAs(response);
        assertThat(guard.shouldWrapUp()).isFalse();
    }

    @Test
    void nonPositiveBudgetIsTreatedAsUnlimitedNotAnInstantTrip() {
        // @Positive now rejects 0/negative at the API boundary, but create() keeps this coercion as
        // defense-in-depth (e.g. a legacy persisted row): it must degrade to UNLIMITED, not wrap up
        // every evaluation on turn one. A bare factory suffices — the non-positive check short-circuits
        // before the model is resolved.
        var factory = mock(LlmProviderFactory.class);

        assertThat(BudgetGuard.create(BigDecimal.ZERO, "gpt-4o", factory, PriceLayers.BUILT_IN_ONLY))
                .isSameAs(BudgetGuard.UNLIMITED);

        var negative = BudgetGuard.create(new BigDecimal("-1"), "gpt-4o", factory, PriceLayers.BUILT_IN_ONLY);
        assertThat(negative).isSameAs(BudgetGuard.UNLIMITED);
        negative.track(Mono.just(responseWith(1_000_000, 1_000_000))).block();
        assertThat(negative.shouldWrapUp()).isFalse();
    }

    @Test
    void accumulatesSpendAcrossCallsAndTripsOnceLimitReached() {
        // Derive the per-call cost from CostService for the same (model, provider, usage) rather than
        // hardcoding it, so a bundled price-table update moves the expectation with it instead of
        // silently breaking this guard test.
        var perCallCost = CostService.calculateCost("gpt-4o", "openai",
                LlmUsageExtractor.toUsageMap(responseWith(100_000, 0)), null);
        assertThat(perCallCost).isGreaterThan(BigDecimal.ZERO); // guard against an unpriced-model false pass
        var limit = perCallCost.add(perCallCost); // trips exactly on the second identical call

        var guard = BudgetGuard.create(limit, "gpt-4o", factoryFor("gpt-4o", "openai"), PriceLayers.BUILT_IN_ONLY);

        assertThat(guard.shouldWrapUp()).isFalse();

        guard.track(Mono.just(responseWith(100_000, 0))).block(); // +perCallCost -> below limit
        assertThat(guard.shouldWrapUp()).isFalse();

        guard.track(Mono.just(responseWith(100_000, 0))).block(); // +perCallCost -> reaches limit
        assertThat(guard.shouldWrapUp()).isTrue();
        assertThat(guard.spentUsd()).isEqualByComparingTo(limit);
    }

    @Test
    void doesNotChargeWhenResponseCarriesNoUsage() {
        var guard = BudgetGuard.create(new BigDecimal("0.01"), "gpt-4o", factoryFor("gpt-4o", "openai"),
                PriceLayers.BUILT_IN_ONLY);

        var response = ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
        guard.track(Mono.just(response)).block();

        assertThat(guard.spentUsd()).isEqualByComparingTo("0");
        assertThat(guard.shouldWrapUp()).isFalse();
    }

    @Test
    void failsOpenToUnlimitedWhenModelCannotBeResolved() {
        var factory = mock(LlmProviderFactory.class);
        when(factory.getResolvedModelInfo("mystery")).thenThrow(new RuntimeException("unknown model"));

        var guard = BudgetGuard.create(new BigDecimal("0.01"), "mystery", factory, PriceLayers.BUILT_IN_ONLY);

        assertThat(guard).isSameAs(BudgetGuard.UNLIMITED);
        assertThat(guard.shouldWrapUp()).isFalse();
    }

    @Test
    void unknownModelPriceNeverTripsTheBudget() {
        // Model resolves but has no entry in the price table -> cost is zero, so the budget can't trip.
        var guard = BudgetGuard.create(new BigDecimal("0.01"), "no-such-model",
                factoryFor("no-such-model", "no-such-provider"), PriceLayers.BUILT_IN_ONLY);

        guard.track(Mono.just(responseWith(1_000_000, 1_000_000))).block();

        assertThat(guard.spentUsd()).isEqualByComparingTo("0");
        assertThat(guard.shouldWrapUp()).isFalse();
    }

    @Test
    void enforcesForOnlineEvaluationProviderNamesLikeGemini() {
        // getResolvedModelInfo returns the LlmProvider value ("gemini"), which differs from the canonical
        // price-table provider ("google_ai"). CostService maps it, so the guard actually charges and can
        // enforce the budget for Gemini/Vertex judges instead of silently pricing every call at zero.
        var guard = BudgetGuard.create(new BigDecimal("999"), "gemini-2.5-flash",
                factoryFor("gemini-2.5-flash", "gemini"), PriceLayers.BUILT_IN_ONLY);

        guard.track(Mono.just(responseWith(1_000_000, 0))).block();

        assertThat(guard.spentUsd()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void failsOpenWhenPerCallCostComputationThrows() {
        // The class contract is "everything is fail-open". A pricing exception inside charge() must be
        // swallowed: the tracked response passes through unchanged, nothing is charged, and the budget
        // never trips — a regression that let it propagate would break the scoring call.
        var guard = BudgetGuard.create(new BigDecimal("0.01"), "gpt-4o", factoryFor("gpt-4o", "openai"),
                PriceLayers.BUILT_IN_ONLY);
        var response = responseWith(100_000, 0);

        try (var costService = mockStatic(CostService.class)) {
            costService.when(() -> CostService.price(any(), any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("pricing failure"));

            assertThat(guard.track(Mono.just(response)).block()).isSameAs(response);
        }

        assertThat(guard.spentUsd()).isEqualByComparingTo("0");
        assertThat(guard.shouldWrapUp()).isFalse();
    }

    private static PriceLayers organizationPricingGpt4oAt1And10() {
        return PriceLayers.of("org-1", 1, List.of(CustomModelPrice.builder()
                .modelName("gpt-4o")
                .provider("openai")
                .kind(CustomModelPrice.Kind.FIXED)
                .inputPerMillion(new BigDecimal("1"))
                .outputPerMillion(new BigDecimal("10"))
                .build()));
    }

    @Test
    void chargesTheOrganizationsPrice() {
        var guard = BudgetGuard.create(new BigDecimal("999"), "gpt-4o", factoryFor("gpt-4o", "openai"),
                organizationPricingGpt4oAt1And10());

        guard.track(Mono.just(responseWith(1_000_000, 100_000))).block();

        assertThat(guard.spentUsd()).isEqualByComparingTo("2");
    }

    @Test
    void loadWithoutALimitLooksUpNoPrices() {
        var modelPriceService = mock(ModelPriceService.class);

        var guard = BudgetGuard.load(null, "gpt-4o", factoryFor("gpt-4o", "openai"), modelPriceService).block();

        assertThat(guard).isSameAs(BudgetGuard.UNLIMITED);
        verifyNoInteractions(modelPriceService);
    }

    @Test
    void loadChargesWithTheLoadedPrices() {
        var modelPriceService = mock(ModelPriceService.class);
        when(modelPriceService.layersFor()).thenReturn(Mono.just(organizationPricingGpt4oAt1And10()));

        var guard = BudgetGuard.load(new BigDecimal("999"), "gpt-4o", factoryFor("gpt-4o", "openai"),
                modelPriceService).block();
        guard.track(Mono.just(responseWith(1_000_000, 100_000))).block();

        assertThat(guard.spentUsd()).isEqualByComparingTo("2");
    }
}
