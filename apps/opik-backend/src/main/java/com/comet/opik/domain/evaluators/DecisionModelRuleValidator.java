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
import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchema;
import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchemaType;
import com.comet.opik.api.resources.v1.events.OnlineScoringEngine;
import com.comet.opik.infrastructure.llm.openrouter.OpenRouterDecisionModel;
import dev.langchain4j.data.message.ChatMessageType;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Rejects LLM-as-a-Judge rules that a decisions model (TypeSafe Jev) can't run. Such a model answers yes/no
 * questions about a text in one call, so a rule on it needs Boolean scores, a single text-only user message,
 * and no {@code {{trace}}} / {@code {{span}}} variables (those drive the agentic path). Thread rules aren't
 * supported. Rules on other models pass through untouched.
 */
@Singleton
public class DecisionModelRuleValidator {

    public void validate(@NonNull AutomationRuleEvaluator<?, ?> evaluator) {
        switch (evaluator) {
            case AutomationRuleEvaluatorLlmAsJudge llmAsJudge -> validate(RuleCode.builder()
                    .model(llmAsJudge.getCode().model().name())
                    .messages(llmAsJudge.getCode().messages())
                    .variables(llmAsJudge.getCode().variables())
                    .schema(llmAsJudge.getCode().schema())
                    .scope(Scope.TRACE)
                    .build());
            case AutomationRuleEvaluatorSpanLlmAsJudge spanLlmAsJudge -> validate(RuleCode.builder()
                    .model(spanLlmAsJudge.getCode().model().name())
                    .messages(spanLlmAsJudge.getCode().messages())
                    .variables(spanLlmAsJudge.getCode().variables())
                    .schema(spanLlmAsJudge.getCode().schema())
                    .scope(Scope.SPAN)
                    .build());
            case AutomationRuleEvaluatorTraceThreadLlmAsJudge threadLlmAsJudge ->
                validateThread(threadLlmAsJudge.getCode().model().name());
            default -> {
                // Python metric rules have no model.
            }
        }
    }

    public void validate(@NonNull AutomationRuleEvaluatorUpdate<?, ?> evaluatorUpdate) {
        switch (evaluatorUpdate) {
            case AutomationRuleEvaluatorUpdateLlmAsJudge llmAsJudge -> validate(RuleCode.builder()
                    .model(llmAsJudge.getCode().model().name())
                    .messages(llmAsJudge.getCode().messages())
                    .variables(llmAsJudge.getCode().variables())
                    .schema(llmAsJudge.getCode().schema())
                    .scope(Scope.TRACE)
                    .build());
            case AutomationRuleEvaluatorUpdateSpanLlmAsJudge spanLlmAsJudge -> validate(RuleCode.builder()
                    .model(spanLlmAsJudge.getCode().model().name())
                    .messages(spanLlmAsJudge.getCode().messages())
                    .variables(spanLlmAsJudge.getCode().variables())
                    .schema(spanLlmAsJudge.getCode().schema())
                    .scope(Scope.SPAN)
                    .build());
            case AutomationRuleEvaluatorUpdateTraceThreadLlmAsJudge threadLlmAsJudge ->
                validateThread(threadLlmAsJudge.getCode().model().name());
            default -> {
                // Python metric rules have no model.
            }
        }
    }

    private void validateThread(String model) {
        if (OpenRouterDecisionModel.isDecisionModel(model)) {
            throw new BadRequestException(
                    "Thread rules don't support decisions models, model '%s'".formatted(model));
        }
    }

    private void validate(RuleCode code) {
        if (!OpenRouterDecisionModel.isDecisionModel(code.model())) {
            return;
        }
        if (code.schema().isEmpty()) {
            throw new BadRequestException(
                    "Rules on decisions models need at least one score, model '%s'".formatted(code.model()));
        }
        code.schema().stream()
                .filter(score -> score.type() != LlmAsJudgeOutputSchemaType.BOOLEAN)
                .findFirst()
                .ifPresent(score -> {
                    throw new BadRequestException(
                            "Decisions models only support Boolean scores, score '%s', type '%s'"
                                    .formatted(score.name(), score.type()));
                });
        if (code.messages().size() != 1 || code.messages().getFirst().role() != ChatMessageType.USER) {
            throw new BadRequestException(
                    "Rules on decisions models need exactly one user message, model '%s'".formatted(code.model()));
        }
        if (!isTextOnly(code.messages().getFirst())) {
            throw new BadRequestException(
                    "Decisions models only accept text messages, model '%s'".formatted(code.model()));
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
                            .formatted(code.scope().variableName, code.model()));
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
    private record RuleCode(@NonNull String model, @NonNull List<LlmAsJudgeMessage> messages,
            @NonNull Map<String, String> variables, @NonNull List<LlmAsJudgeOutputSchema> schema,
            @NonNull Scope scope) {
    }
}
