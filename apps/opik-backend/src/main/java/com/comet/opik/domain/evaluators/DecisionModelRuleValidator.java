package com.comet.opik.domain.evaluators;

import com.comet.opik.api.PromptType;
import com.comet.opik.api.evaluators.AutomationRuleEvaluator;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorSpanLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUpdate;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUpdateLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUpdateSpanLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUpdateTraceThreadLlmAsJudge;
import com.comet.opik.api.evaluators.LlmAsJudgeMessage;
import com.comet.opik.api.evaluators.LlmAsJudgeModelApi;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchema;
import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchemaType;
import com.comet.opik.api.resources.v1.events.OnlineScoringEngine;
import com.comet.opik.infrastructure.llm.decisions.DecisionModels;
import dev.langchain4j.data.message.ChatMessageType;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Rejects LLM-as-a-Judge rules that a decisions model can't run (see {@link DecisionModels} for which rules use one).
 * Such a model answers yes/no questions about a text in one call, so a rule on it needs Boolean scores, a single
 * text-only user message, no {@code {{trace}}} / {@code {{span}}} variables (those drive the agentic path), and no
 * spend budget (the budget guard only meters chat calls). Thread rules aren't supported, and a rule can't ask for the
 * Decisions API on a model that has none. Other rules pass through untouched.
 */
@Singleton
public class DecisionModelRuleValidator {

    public void validate(@NonNull AutomationRuleEvaluator<?, ?> evaluator) {
        switch (evaluator) {
            case AutomationRuleEvaluatorLlmAsJudge llmAsJudge -> validate(RuleCode.builder()
                    .model(llmAsJudge.getCode().model())
                    .messages(llmAsJudge.getCode().messages())
                    .variables(llmAsJudge.getCode().variables())
                    .schema(llmAsJudge.getCode().schema())
                    .maxCostUsd(llmAsJudge.getCode().maxCostUsd())
                    .scope(Scope.TRACE)
                    .build());
            case AutomationRuleEvaluatorSpanLlmAsJudge spanLlmAsJudge -> validate(RuleCode.builder()
                    .model(spanLlmAsJudge.getCode().model())
                    .messages(spanLlmAsJudge.getCode().messages())
                    .variables(spanLlmAsJudge.getCode().variables())
                    .schema(spanLlmAsJudge.getCode().schema())
                    .scope(Scope.SPAN)
                    .build());
            case AutomationRuleEvaluatorTraceThreadLlmAsJudge threadLlmAsJudge ->
                validateThread(threadLlmAsJudge.getCode().model());
            default -> {
                // Python metric rules have no model.
            }
        }
    }

    public void validate(@NonNull AutomationRuleEvaluatorUpdate<?, ?> evaluatorUpdate) {
        switch (evaluatorUpdate) {
            case AutomationRuleEvaluatorUpdateLlmAsJudge llmAsJudge -> validate(RuleCode.builder()
                    .model(llmAsJudge.getCode().model())
                    .messages(llmAsJudge.getCode().messages())
                    .variables(llmAsJudge.getCode().variables())
                    .schema(llmAsJudge.getCode().schema())
                    .maxCostUsd(llmAsJudge.getCode().maxCostUsd())
                    .scope(Scope.TRACE)
                    .build());
            case AutomationRuleEvaluatorUpdateSpanLlmAsJudge spanLlmAsJudge -> validate(RuleCode.builder()
                    .model(spanLlmAsJudge.getCode().model())
                    .messages(spanLlmAsJudge.getCode().messages())
                    .variables(spanLlmAsJudge.getCode().variables())
                    .schema(spanLlmAsJudge.getCode().schema())
                    .scope(Scope.SPAN)
                    .build());
            case AutomationRuleEvaluatorUpdateTraceThreadLlmAsJudge threadLlmAsJudge ->
                validateThread(threadLlmAsJudge.getCode().model());
            default -> {
                // Python metric rules have no model.
            }
        }
    }

    private void validateThread(LlmAsJudgeModelParameters model) {
        validateApi(model);
        if (DecisionModels.isDecisionsRule(model)) {
            throw new BadRequestException(
                    "Thread rules don't support decisions models, model '%s'".formatted(model.name()));
        }
    }

    private void validateApi(LlmAsJudgeModelParameters model) {
        if (model.api() == LlmAsJudgeModelApi.DECISIONS && !DecisionModels.isDecisionsRule(model)) {
            throw new BadRequestException(
                    "Model '%s' has no Decisions API".formatted(model.name()));
        }
    }

    private void validate(RuleCode code) {
        validateApi(code.model());
        if (!DecisionModels.isDecisionsRule(code.model())) {
            return;
        }
        if (code.schema().isEmpty()) {
            throw new BadRequestException(
                    "Rules on decisions models need at least one score, model '%s'".formatted(code.model().name()));
        }
        code.schema().stream()
                .filter(score -> score.type() != LlmAsJudgeOutputSchemaType.BOOLEAN)
                .findFirst()
                .ifPresent(score -> {
                    throw new BadRequestException(
                            "Decisions models only support Boolean scores, score '%s', type '%s'"
                                    .formatted(score.name(), score.type()));
                });
        if (code.maxCostUsd() != null) {
            throw new BadRequestException(
                    "Decisions models don't support a spend budget, model '%s'".formatted(code.model().name()));
        }
        if (code.messages().size() != 1 || code.messages().getFirst().role() != ChatMessageType.USER) {
            throw new BadRequestException(
                    "Rules on decisions models need exactly one user message, model '%s'"
                            .formatted(code.model().name()));
        }
        if (!isTextOnly(code.messages().getFirst())) {
            throw new BadRequestException(
                    "Decisions models only accept text messages, model '%s'".formatted(code.model().name()));
        }
        boolean referencesStructure = switch (code.scope()) {
            case TRACE -> OnlineScoringEngine.templateReferencesTraceStructure(
                    code.messages(), code.variables(), PromptType.MUSTACHE);
            case SPAN -> OnlineScoringEngine.templateReferencesSpanStructure(
                    code.messages(), code.variables(), PromptType.MUSTACHE);
        };
        if (referencesStructure) {
            throw new BadRequestException(
                    "Decisions models don't support the '{{%s}}' variable, model '%s'"
                            .formatted(code.scope().variableName, code.model().name()));
        }
    }

    private static boolean isTextOnly(LlmAsJudgeMessage message) {
        return message.isStringContent()
                || (message.isStructuredContent()
                        && message.asContentList().stream().allMatch(part -> "text".equals(part.type())));
    }

    @RequiredArgsConstructor
    private enum Scope {
        TRACE("trace"),
        SPAN("span");

        private final String variableName;
    }

    @Builder(toBuilder = true)
    private record RuleCode(@NonNull LlmAsJudgeModelParameters model, @NonNull List<LlmAsJudgeMessage> messages,
            @NonNull Map<String, String> variables, @NonNull List<LlmAsJudgeOutputSchema> schema,
            BigDecimal maxCostUsd, @NonNull Scope scope) {
    }
}
