package com.comet.opik.infrastructure.llm.decisions;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.evaluators.LlmAsJudgeModelApi;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.infrastructure.llm.openai.OpenaiModelName;
import com.comet.opik.infrastructure.llm.openrouter.OpenRouterDecisionModel;
import lombok.Builder;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The models judge rules can score with through a Decisions API instead of chat. A rule resolves on its model name and
 * its {@code api} together: a decisions-only model (TypeSafe Jev) uses the Decisions API with or without {@code api},
 * while a model its provider also serves over chat (gpt-6-luna) uses it only when the rule sets {@code api} to
 * {@code decisions}.
 *
 * <p>Adding a model is one entry here. Its provider must be the one {@code LlmProviderFactory} resolves for the model,
 * so the workspace key matches the client that {@code DecisionScoringService} picks.
 */
@UtilityClass
public class DecisionModels {

    /** OpenRouter's context length for Jev, covering the text and the questions together. */
    public static final int JEV_MAX_CONTEXT_TOKENS = 32_000;

    /** OpenAI's input limit for gpt-6-luna, from the price table. */
    public static final int GPT_6_LUNA_MAX_CONTEXT_TOKENS = 922_000;

    @Builder(toBuilder = true)
    public record DecisionModel(@NonNull String name, @NonNull LlmProvider provider, boolean decisionsOnly,
            int maxContextTokens) {
    }

    public static final List<DecisionModel> ALL = Stream.concat(
            Arrays.stream(OpenRouterDecisionModel.values()).map(jev -> DecisionModel.builder()
                    .name(jev.toString())
                    .provider(LlmProvider.OPEN_ROUTER)
                    .decisionsOnly(true)
                    .maxContextTokens(JEV_MAX_CONTEXT_TOKENS)
                    .build()),
            Stream.of(DecisionModel.builder()
                    .name(OpenaiModelName.GPT_6_LUNA.toString())
                    .provider(LlmProvider.OPEN_AI)
                    .decisionsOnly(false)
                    .maxContextTokens(GPT_6_LUNA_MAX_CONTEXT_TOKENS)
                    .build()))
            .toList();

    public static Optional<DecisionModel> find(String name) {
        return ALL.stream().filter(model -> model.name().equals(name)).findFirst();
    }

    public static boolean isDecisionsRule(@NonNull LlmAsJudgeModelParameters model) {
        return find(model.name())
                .filter(decisionModel -> decisionModel.decisionsOnly() || model.api() == LlmAsJudgeModelApi.DECISIONS)
                .isPresent();
    }
}
