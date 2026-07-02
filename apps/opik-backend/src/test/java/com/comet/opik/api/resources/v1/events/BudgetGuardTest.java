package com.comet.opik.api.resources.v1.events;

import com.comet.opik.domain.llm.LlmProviderFactory;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BudgetGuardTest {

    // gpt-4o is priced at $2.5/1M input + $10/1M output tokens in the bundled price table,
    // so 100k input tokens cost exactly $0.25 — the unit used across these cases.
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
        var guard = BudgetGuard.create(null, "gpt-4o", factoryFor("gpt-4o", "openai"));

        assertThat(guard).isSameAs(BudgetGuard.UNLIMITED);

        var response = responseWith(1_000_000, 1_000_000);
        // Pass-through: same instance back, nothing charged, never wraps up.
        assertThat(guard.track(Mono.just(response)).block()).isSameAs(response);
        assertThat(guard.shouldWrapUp()).isFalse();
    }

    @Test
    void nonPositiveBudgetIsTreatedAsUnlimitedNotAnInstantTrip() {
        // @Positive on the DTO doesn't run (code field isn't @Valid-cascaded), so a 0/negative limit
        // can reach create(). It must degrade to UNLIMITED, not wrap up every evaluation on turn one.
        var factory = factoryFor("gpt-4o", "openai");

        assertThat(BudgetGuard.create(BigDecimal.ZERO, "gpt-4o", factory)).isSameAs(BudgetGuard.UNLIMITED);

        var negative = BudgetGuard.create(new BigDecimal("-1"), "gpt-4o", factory);
        assertThat(negative).isSameAs(BudgetGuard.UNLIMITED);
        negative.track(Mono.just(responseWith(1_000_000, 1_000_000))).block();
        assertThat(negative.shouldWrapUp()).isFalse();
    }

    @Test
    void accumulatesSpendAcrossCallsAndTripsOnceLimitReached() {
        var guard = BudgetGuard.create(new BigDecimal("0.50"), "gpt-4o", factoryFor("gpt-4o", "openai"));

        assertThat(guard.shouldWrapUp()).isFalse();

        guard.track(Mono.just(responseWith(100_000, 0))).block(); // +$0.25 -> $0.25
        assertThat(guard.shouldWrapUp()).isFalse();

        guard.track(Mono.just(responseWith(100_000, 0))).block(); // +$0.25 -> $0.50, reaches limit
        assertThat(guard.shouldWrapUp()).isTrue();
        assertThat(guard.spentUsd()).isEqualByComparingTo("0.50");
    }

    @Test
    void doesNotChargeWhenResponseCarriesNoUsage() {
        var guard = BudgetGuard.create(new BigDecimal("0.01"), "gpt-4o", factoryFor("gpt-4o", "openai"));

        var response = ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
        guard.track(Mono.just(response)).block();

        assertThat(guard.spentUsd()).isEqualByComparingTo("0");
        assertThat(guard.shouldWrapUp()).isFalse();
    }

    @Test
    void failsOpenToUnlimitedWhenModelCannotBeResolved() {
        var factory = mock(LlmProviderFactory.class);
        when(factory.getResolvedModelInfo("mystery")).thenThrow(new RuntimeException("unknown model"));

        var guard = BudgetGuard.create(new BigDecimal("0.01"), "mystery", factory);

        assertThat(guard).isSameAs(BudgetGuard.UNLIMITED);
        assertThat(guard.shouldWrapUp()).isFalse();
    }

    @Test
    void unknownModelPriceNeverTripsTheBudget() {
        // Model resolves but has no entry in the price table -> cost is zero, so the budget can't trip.
        var guard = BudgetGuard.create(new BigDecimal("0.01"), "no-such-model",
                factoryFor("no-such-model", "no-such-provider"));

        guard.track(Mono.just(responseWith(1_000_000, 1_000_000))).block();

        assertThat(guard.spentUsd()).isEqualByComparingTo("0");
        assertThat(guard.shouldWrapUp()).isFalse();
    }
}
