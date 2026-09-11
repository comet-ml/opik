package com.comet.opik.domain.evaluation;

import com.comet.opik.api.ErrorInfo;
import com.comet.opik.api.FeedbackScoreItem;
import com.comet.opik.api.Source;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.VisibilityMode;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.SpanType;
import com.comet.opik.infrastructure.ResponseFormattingConfig;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.exception.ExceptionUtils;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds the monitoring {@link Span}s and the parent {@link Trace} for one online evaluation from its
 * {@link EvaluationContext} and the langchain4j request/response. Pure construction: it produces the
 * Opik entities and never persists them — {@link OnlineEvaluationRecorder} hands them to the
 * observability core. Split out of the recorder so that class stays focused on orchestration.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class EvaluationEntityFactory {

    private static final String TRACE_NAME = "online_evaluation";
    private static final String SPAN_NAME = "llm_call";
    private static final String PREPARE_SPAN_NAME = "prepare_evaluation";
    private static final String MODE_INLINE = "inline";
    private static final String MODE_AGENTIC_TOOLS = "agentic_tools";
    private static final String BUDGET_EXCEEDED_TAG = "budget_exceeded";

    private final @NonNull IdGenerator idGenerator;
    private final @NonNull @Config("responseFormatting") ResponseFormattingConfig responseFormattingConfig;

    /** One {@code llm} span per LLM round-trip; carries usage + resolved model/provider for costing. */
    Span llmSpan(EvaluationContext eval, ChatRequest request, ChatResponse response, Throwable error, Instant start) {
        var spanBuilder = Span.builder()
                .id(idGenerator.generateId())
                .traceId(eval.traceId())
                .projectName(eval.projectName())
                .type(SpanType.llm)
                .name(SPAN_NAME)
                .startTime(start)
                .endTime(Instant.now())
                .input(buildMessagesInput(request))
                .model(eval.actualModel())
                .provider(eval.provider())
                .source(Source.EVALUATOR);

        if (response != null) {
            spanBuilder.output(buildResponseOutput(response))
                    .usage(LlmUsageExtractor.toUsageMap(response));
        }
        if (error != null) {
            spanBuilder.errorInfo(toErrorInfo(error));
        }

        return spanBuilder.build();
    }

    /** One {@code tool} span per agentic tool execution. */
    Span toolSpan(EvaluationContext eval, String toolName, String arguments, String result, Throwable error,
            Instant start) {
        Map<String, Object> input = new LinkedHashMap<>();
        if (arguments != null) {
            input.put("arguments", arguments);
        }

        var spanBuilder = Span.builder()
                .id(idGenerator.generateId())
                .traceId(eval.traceId())
                .projectName(eval.projectName())
                .type(SpanType.tool)
                .name(toolName)
                .startTime(start)
                .endTime(Instant.now())
                .input(JsonUtils.valueToTree(input))
                .source(Source.EVALUATOR);

        if (result != null) {
            spanBuilder.output(JsonUtils.valueToTree(Map.of("result", result)));
        }
        if (error != null) {
            spanBuilder.errorInfo(toErrorInfo(error));
        }

        return spanBuilder.build();
    }

    /** The upfront retrieval + context-assembly span preceding the first LLM round. */
    Span preparationSpan(EvaluationContext eval, int fetchedSpanCount, int estimatedTokens, boolean agentic) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(eval.evaluatedIdKey(), eval.evaluatedId());
        if (eval.evaluatedProjectId() != null) {
            metadata.put("evaluated_project_id", eval.evaluatedProjectId().toString());
        }
        if (eval.evaluatedName() != null) {
            metadata.put("evaluated_name", eval.evaluatedName());
        }
        metadata.put("model", eval.modelName());
        metadata.put("fetched_span_count", fetchedSpanCount);
        metadata.put("estimated_tokens", estimatedTokens);
        metadata.put("mode", agentic ? MODE_AGENTIC_TOOLS : MODE_INLINE);

        var spanBuilder = Span.builder()
                .id(idGenerator.generateId())
                .traceId(eval.traceId())
                .projectName(eval.projectName())
                .type(SpanType.general)
                .name(PREPARE_SPAN_NAME)
                .startTime(eval.startTime())
                .endTime(Instant.now())
                .metadata(JsonUtils.valueToTree(metadata))
                .source(Source.EVALUATOR);

        // Put the evaluated entity's own input/output on the span (so the UI pretty-renders them exactly
        // as on the source trace), preview-capped to keep the monitoring span small.
        var input = previewNode(eval.evaluatedInput());
        if (input != null) {
            spanBuilder.input(input);
        }
        var output = previewNode(eval.evaluatedOutput());
        if (output != null) {
            spanBuilder.output(output);
        }

        return spanBuilder.build();
    }

    /** Finalizes the parent trace on success, recording the produced scores as its output. */
    Trace completedTrace(EvaluationContext eval, List<? extends FeedbackScoreItem> scores, boolean budgetExceeded) {
        return trace(eval, buildScoresOutput(scores), null, budgetExceeded);
    }

    /** Finalizes the parent trace on failure, recording the error. */
    Trace failedTrace(EvaluationContext eval, Throwable error, boolean budgetExceeded) {
        return trace(eval, null, toErrorInfo(error), budgetExceeded);
    }

    private Trace trace(EvaluationContext eval, JsonNode output, ErrorInfo errorInfo, boolean budgetExceeded) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put(eval.evaluatedIdKey(), eval.evaluatedId());
        if (eval.evaluatedProjectId() != null) {
            input.put("evaluated_project_id", eval.evaluatedProjectId().toString());
        }
        input.put("model", eval.modelName());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("created_from", "online_evaluation");
        metadata.put("rule_id", eval.ruleId().toString());
        if (eval.ruleName() != null) {
            metadata.put("rule_name", eval.ruleName());
        }
        metadata.put(eval.evaluatedIdKey(), eval.evaluatedId());
        metadata.put("model", eval.modelName());

        var traceBuilder = Trace.builder()
                .id(eval.traceId())
                .projectName(eval.projectName())
                .name(TRACE_NAME)
                .startTime(eval.startTime())
                .endTime(Instant.now())
                .input(JsonUtils.valueToTree(input))
                .metadata(JsonUtils.valueToTree(metadata))
                .source(Source.EVALUATOR)
                .visibilityMode(VisibilityMode.HIDDEN);

        if (budgetExceeded) {
            traceBuilder.tags(Set.of(BUDGET_EXCEEDED_TAG));
        }
        if (output != null) {
            traceBuilder.output(output);
        }
        if (errorInfo != null) {
            traceBuilder.errorInfo(errorInfo);
        }

        return traceBuilder.build();
    }

    private static JsonNode buildMessagesInput(ChatRequest request) {
        var messages = request.messages() == null
                ? List.<JudgeMessage>of()
                : request.messages().stream()
                        .map(message -> JudgeMessage.builder()
                                .role(message.type().name().toLowerCase())
                                .content(messageText(message))
                                .build())
                        .toList();
        return JsonUtils.valueToTree(MessagesInput.builder().messages(messages).build());
    }

    private static JsonNode buildResponseOutput(ChatResponse response) {
        var aiMessage = response.aiMessage();
        var toolCalls = aiMessage.hasToolExecutionRequests()
                ? aiMessage.toolExecutionRequests().stream()
                        .map(toolCall -> ToolCallView.builder()
                                .name(toolCall.name())
                                .arguments(toolCall.arguments())
                                .build())
                        .toList()
                : null;
        return JsonUtils.valueToTree(ResponseOutput.builder()
                .output(aiMessage.text() == null ? "" : aiMessage.text())
                .toolCalls(toolCalls)
                .build());
    }

    private static JsonNode buildScoresOutput(List<? extends FeedbackScoreItem> scores) {
        var scoreViews = scores.stream()
                .map(score -> ScoreView.builder()
                        .name(score.name())
                        .value(score.value())
                        .reason(score.reason())
                        .build())
                .toList();
        return JsonUtils.valueToTree(ScoresOutput.builder().scores(scoreViews).build());
    }

    private static String messageText(ChatMessage message) {
        return switch (message) {
            case SystemMessage systemMessage -> systemMessage.text();
            case UserMessage userMessage -> userMessage.contents().stream()
                    .filter(TextContent.class::isInstance)
                    .map(content -> ((TextContent) content).text())
                    .collect(Collectors.joining("\n"));
            case AiMessage aiMessage -> aiMessage.text() == null ? "" : aiMessage.text();
            case ToolExecutionResultMessage toolResult -> toolResult.text();
            default -> String.valueOf(message);
        };
    }

    private static ErrorInfo toErrorInfo(Throwable error) {
        var rootCause = ExceptionUtils.getRootCause(error);
        var cause = rootCause != null ? rootCause : error;
        var message = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
        return ErrorInfo.builder()
                .exceptionType(cause.getClass().getSimpleName())
                .message(message)
                .traceback(ExceptionUtils.getStackTrace(error))
                .build();
    }

    /**
     * Preview of an evaluated entity's input/output for the prepare_evaluation span, so a viewer can
     * tell what the evaluation is assessing without opening the evaluated trace. Small payloads are
     * kept structurally intact (so the UI renders them in pretty mode just like the source trace);
     * payloads larger than the Opik-wide {@code responseFormatting.truncationSize} (the same field
     * truncation limit used for all traces) fall back to a truncated text node.
     */
    private JsonNode previewNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        int previewMaxChars = responseFormattingConfig.getTruncationSize();
        // Measure against the SERIALIZED form so a heavily-escaped string (quotes/backslashes) can't slip
        // past the cap once rendered as JSON. Slice the raw text for string nodes and clamp the index to
        // its length, so we never slice past a string shorter than the cap (which would throw IOOBE).
        String serialized = node.toString();
        if (serialized.length() <= previewMaxChars) {
            return node;
        }
        String raw = node.isTextual() ? node.asText() : serialized;
        int end = Math.min(previewMaxChars, raw.length());
        return TextNode.valueOf(raw.substring(0, end) + "…[truncated]");
    }

    // Fixed-shape span/trace input & output, serialized via the snake_case + non-null mapper
    // (so toolCalls -> tool_calls and null fields are omitted), instead of hand-built JSON nodes.
    @Builder(toBuilder = true)
    private record MessagesInput(List<JudgeMessage> messages) {
    }

    @Builder(toBuilder = true)
    private record JudgeMessage(String role, String content) {
    }

    @Builder(toBuilder = true)
    private record ResponseOutput(String output, List<ToolCallView> toolCalls) {
    }

    @Builder(toBuilder = true)
    private record ToolCallView(String name, String arguments) {
    }

    @Builder(toBuilder = true)
    private record ScoresOutput(List<ScoreView> scores) {
    }

    @Builder(toBuilder = true)
    private record ScoreView(String name, BigDecimal value, String reason) {
    }
}
