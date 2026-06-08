package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongHistogram;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

/**
 * Records the approximate character size of each online-scoring LLM round-trip so we can size the
 * memory an evaluation holds while blocked on the provider (OPIK-6813) and spot heavy workloads.
 * Stateful — owns the OTel instruments — so it is injected as a {@code @Singleton} rather than a
 * static utility.
 *
 * <p>The histograms are tagged with {@code evaluator_type} and {@code model} only: {@code rule_id}
 * and {@code workspace_id} are unbounded and would blow the prod metrics cardinality limit, so they
 * are emitted on a log line instead (sizes only — never the rendered content).
 */
@Singleton
@Slf4j
public class OnlineScoringMetrics {

    private static final String METER_NAME = "opik.online_scoring";
    private static final AttributeKey<String> EVALUATOR_TYPE_KEY = AttributeKey.stringKey("evaluator_type");
    private static final AttributeKey<String> MODEL_KEY = AttributeKey.stringKey("model");

    private final LongHistogram inputChars;
    private final LongHistogram outputChars;

    @Inject
    public OnlineScoringMetrics() {
        var meter = GlobalOpenTelemetry.get().getMeter(METER_NAME);
        this.inputChars = meter.histogramBuilder("online_scoring_llm_input_chars")
                .ofLongs()
                .setDescription("Approximate character count of the LLM-as-judge request messages per round-trip")
                .setUnit("chars")
                .build();
        this.outputChars = meter.histogramBuilder("online_scoring_llm_output_chars")
                .ofLongs()
                .setDescription("Approximate character count of the LLM-as-judge response text per round-trip")
                .setUnit("chars")
                .build();
    }

    /**
     * Records one LLM round-trip's input/output sizes. Called per provider call — including each
     * agentic tool-loop round — so multi-turn evaluations are captured turn by turn.
     */
    public void recordPayloadSize(@NonNull ChatRequest request, @NonNull ChatResponse response,
            @NonNull AutomationRuleEvaluatorType evaluatorType, @NonNull String model,
            @NonNull String workspaceId, @NonNull UUID ruleId) {
        long input = inputChars(request);
        long output = outputChars(response);

        var attributes = buildAttributes(evaluatorType, model);
        inputChars.record(input, attributes);
        outputChars.record(output, attributes);

        log.debug(
                "Online scoring LLM payload size: evaluatorType '{}', model '{}', workspaceId '{}', ruleId '{}', inputChars '{}', outputChars '{}'",
                evaluatorType.getType(), model, workspaceId, ruleId, input, output);
    }

    /**
     * Sums the character length of every message in the request. Switches on the message type and
     * reads the already-materialized text rather than calling {@code toString()} on the message —
     * stringifying a multi-MB rendered prompt just to measure it would roughly double the per-eval
     * heap churn (the same reason {@link OnlineScoringEngine#summarizeRequest} avoids it). Non-text
     * (image / audio / video) content parts are skipped.
     */
    /**
     * Builds the histogram tag set: {@code evaluator_type} and {@code model} only. {@code rule_id}
     * and {@code workspace_id} are deliberately excluded — they are unbounded and would breach the
     * prod metrics cardinality limit. Kept package-private so the cardinality contract is locked in
     * by a unit test.
     */
    static Attributes buildAttributes(AutomationRuleEvaluatorType evaluatorType, String model) {
        return Attributes.of(EVALUATOR_TYPE_KEY, evaluatorType.getType(), MODEL_KEY, model);
    }

    static long inputChars(ChatRequest request) {
        if (request == null || request.messages() == null) {
            return 0L;
        }
        return request.messages().stream()
                .mapToLong(OnlineScoringMetrics::messageChars)
                .sum();
    }

    static long messageChars(ChatMessage message) {
        return switch (message) {
            case null -> 0L;
            case SystemMessage systemMessage -> length(systemMessage.text());
            case UserMessage userMessage -> userMessage.contents() == null
                    ? 0L
                    : userMessage.contents().stream()
                            .mapToLong(content -> content instanceof TextContent textContent
                                    ? length(textContent.text())
                                    : 0L)
                            .sum();
            case AiMessage aiMessage -> length(aiMessage.text());
            case ToolExecutionResultMessage toolResult -> length(toolResult.text());
            default -> 0L;
        };
    }

    static long outputChars(ChatResponse response) {
        if (response == null || response.aiMessage() == null) {
            return 0L;
        }
        return length(response.aiMessage().text());
    }

    private static long length(String value) {
        return value == null ? 0L : value.length();
    }
}
