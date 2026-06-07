package com.comet.opik.api.resources.v1.events;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

/**
 * Records the approximate payload size (in characters) of LLM-as-judge online-scoring calls.
 * <p>
 * This is the observability piece of OPIK-6813: during a high-volume scoring surge the backend heap
 * fills with in-flight eval state (rendered prompt + LLM response), but there was previously no way to
 * see how much memory an eval costs. These histograms let us size the in-flight memory budget for the
 * consumption-bounding work and spot heavy eval workloads.
 * <p>
 * Recorded at the per-call site (inside the reactive {@code scoreTrace} wrapper), so agentic multi-turn
 * tool loops are captured per round-trip, not just once per eval.
 * <p>
 * Metric attributes are intentionally limited to {@code evaluator_type} and {@code model} to stay well
 * within the configured OTel metric cardinality limit. Per-workspace / per-rule attribution is emitted on
 * the log line instead (queryable via Loki), since {@code rule_id} is unbounded and would explode metric
 * cardinality.
 */
@UtilityClass
@Slf4j
public class OnlineScoringLlmMetrics {

    private static final String METER_NAME = "opik.online_scoring";

    private static final Meter METER = GlobalOpenTelemetry.getMeter(METER_NAME);

    private static final LongHistogram INPUT_CHARS = METER.histogramBuilder("online_scoring_llm_input_chars")
            .setDescription("Approx. characters in an LLM-as-judge request payload (proxy for heap held per eval)")
            .setUnit("characters")
            .ofLongs()
            .build();

    private static final LongHistogram OUTPUT_CHARS = METER.histogramBuilder("online_scoring_llm_output_chars")
            .setDescription("Approx. characters in an LLM-as-judge response payload")
            .setUnit("characters")
            .ofLongs()
            .build();

    /**
     * Records the request/response payload size for a single LLM-as-judge call. Best-effort: any failure to
     * compute or record is swallowed so telemetry never breaks scoring.
     */
    public void record(
            String workspaceId,
            String ruleId,
            String evaluatorType,
            String model,
            ChatRequest request,
            ChatResponse response) {
        try {
            long in = approxChars(request);
            long out = approxChars(response);

            Attributes attributes = Attributes.builder()
                    .put("evaluator_type", evaluatorType)
                    .put("model", model)
                    .build();

            INPUT_CHARS.record(in, attributes);
            OUTPUT_CHARS.record(out, attributes);

            // Per-workspace / per-rule attribution lives on the log line (not the metric) to bound cardinality.
            log.info(
                    "Online scoring LLM payload: workspaceId='{}', ruleId='{}', type='{}', model='{}', inputChars={}, outputChars={}",
                    workspaceId, ruleId, evaluatorType, model, in, out);
        } catch (RuntimeException exception) {
            log.debug("Failed to record online-scoring LLM payload metric", exception);
        }
    }

    private long approxChars(ChatRequest request) {
        if (request == null || request.messages() == null) {
            return 0L;
        }
        long total = 0L;
        for (ChatMessage message : request.messages()) {
            total += textLength(message);
        }
        return total;
    }

    private long approxChars(ChatResponse response) {
        if (response == null || response.aiMessage() == null || response.aiMessage().text() == null) {
            return 0L;
        }
        return response.aiMessage().text().length();
    }

    private long textLength(ChatMessage message) {
        return switch (message) {
            case SystemMessage systemMessage -> systemMessage.text() == null ? 0L : systemMessage.text().length();
            case AiMessage aiMessage -> aiMessage.text() == null ? 0L : aiMessage.text().length();
            case UserMessage userMessage -> {
                try {
                    String text = userMessage.singleText();
                    yield text == null ? 0L : text.length();
                } catch (RuntimeException notSingleText) {
                    // Multimodal / multi-part content: fall back to the rendered form length.
                    yield String.valueOf(userMessage).length();
                }
            }
            default -> message == null ? 0L : String.valueOf(message).length();
        };
    }
}
