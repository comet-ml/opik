package com.comet.opik.domain.mapping.otel;

import com.comet.opik.domain.SpanType;
import com.comet.opik.domain.mapping.OpenTelemetryMappingUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BinaryNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes the flattened OpenInference semantic conventions into Opik's input, output and span fields.
 *
 * <p>The normalizer is intentionally gated by the required per-span {@code openinference.span.kind}
 * attribute. Instrumentation scope names are batch-level in OTLP and cannot safely identify a span when
 * one request contains several integrations.</p>
 */
@Slf4j
public final class OpenInferenceSpanNormalizer {

    public static final String SPAN_KIND = "openinference.span.kind";

    private static final String INPUT_VALUE = "input.value";
    private static final String INPUT_MIME_TYPE = "input.mime_type";
    private static final String OUTPUT_VALUE = "output.value";
    private static final String OUTPUT_MIME_TYPE = "output.mime_type";
    private static final String JSON_MIME_TYPE = "application/json";

    private static final Pattern MESSAGE_ATTRIBUTE = Pattern.compile(
            "^llm\\.(input|output)_messages\\.([^.]+)\\.message\\.(.+)$");
    private static final Pattern MESSAGE_CONTENT_ATTRIBUTE = Pattern.compile(
            "^contents\\.([^.]+)\\.(.+)$");
    private static final Pattern MESSAGE_TOOL_CALL_ATTRIBUTE = Pattern.compile(
            "^tool_calls\\.([^.]+)\\.tool_call\\.(.+)$");
    private static final Pattern TOOL_ATTRIBUTE = Pattern.compile(
            "^llm\\.tools\\.([^.]+)\\.tool\\.(name|description|json_schema)$");
    private static final Pattern PROMPT_ATTRIBUTE = Pattern.compile(
            "^llm\\.prompts\\.([^.]+)\\.prompt\\.text$");
    private static final Pattern CHOICE_ATTRIBUTE = Pattern.compile(
            "^llm\\.choices\\.([^.]+)\\.completion\\.text$");

    private static final Map<String, String> USAGE_KEYS = Map.of(
            "llm.token_count.prompt", "prompt_tokens",
            "llm.token_count.completion", "completion_tokens",
            "llm.token_count.total", "total_tokens",
            "llm.token_count.prompt_details.cache_read", "cache_read_input_tokens",
            "llm.token_count.prompt_details.cache_write", "cache_creation_input_tokens",
            "llm.token_count.prompt_details.audio", "input_audio_tokens",
            "llm.token_count.completion_details.reasoning", "reasoning_tokens",
            "llm.token_count.completion_details.audio", "output_audio_tokens");

    private static final Set<String> RESERVED_METADATA_KEYS = Set.of(
            "thread_id", "integration", "server.address", SPAN_KIND, INPUT_MIME_TYPE, OUTPUT_MIME_TYPE,
            "user.id");

    private static final Set<String> OPENINFERENCE_PREFIXES = Set.of(
            "openinference.", "input.", "output.", "llm.", "session.", "user.", "tag.", "tool.",
            "embedding.", "retrieval.", "reranker.", "prompt.", "agent.", "graph.");

    private OpenInferenceSpanNormalizer() {
    }

    /**
     * Returns a normalization result only when the exact required marker is present on this span.
     */
    public static Optional<Result> normalize(List<KeyValue> attributes) {
        if (attributes == null || attributes.stream().noneMatch(attribute -> SPAN_KIND.equals(attribute.getKey()))) {
            return Optional.empty();
        }

        var state = new State();
        attributes.forEach(state::accept);
        return Optional.of(state.finish());
    }

    /**
     * OpenInference values that are applied after the common OTEL rules, so semantic attributes win
     * deterministic collisions with raw input/output objects and generic attributes.
     */
    public record Result(
            Set<String> consumedKeys,
            JsonNode rawInput,
            boolean hasRawInput,
            JsonNode rawOutput,
            boolean hasRawOutput,
            ObjectNode structuredInput,
            ObjectNode structuredOutput,
            ObjectNode metadata,
            Map<String, Integer> usage,
            Set<String> tags,
            String model,
            String provider,
            SpanType spanType,
            BigDecimal totalEstimatedCost,
            String sessionId) {

        public boolean consumes(String key) {
            return consumedKeys.contains(key);
        }

        public JsonNode composeInput(ObjectNode commonInput) {
            return compose(rawInput, hasRawInput, commonInput, structuredInput);
        }

        public JsonNode composeOutput(ObjectNode commonOutput) {
            return compose(rawOutput, hasRawOutput, commonOutput, structuredOutput);
        }

        private static JsonNode compose(JsonNode raw, boolean hasRaw, ObjectNode common, ObjectNode semantic) {
            boolean hasCommon = common != null && !common.isEmpty();
            boolean hasSemantic = semantic != null && !semantic.isEmpty();

            if (hasRaw && !raw.isObject() && !hasCommon && !hasSemantic) {
                return raw.deepCopy();
            }
            if (!hasRaw && !hasCommon && !hasSemantic) {
                return null;
            }

            ObjectNode result = JsonUtils.createObjectNode();
            if (hasRaw) {
                if (raw.isObject()) {
                    result.setAll((ObjectNode) raw);
                } else {
                    result.set("value", raw.deepCopy());
                }
            }
            if (hasCommon) {
                result.setAll(common);
            }
            if (hasSemantic) {
                result.setAll(semantic);
            }
            return result;
        }
    }

    private static final class State {

        private final Set<String> consumedKeys = new HashSet<>();
        private final ObjectNode input = JsonUtils.createObjectNode();
        private final ObjectNode output = JsonUtils.createObjectNode();
        private final ObjectNode metadata = JsonUtils.createObjectNode();
        private final Map<String, Integer> usage = new TreeMap<>();
        private final Set<String> tags = new HashSet<>();
        private final TreeMap<Integer, MessageBuilder> inputMessages = new TreeMap<>();
        private final TreeMap<Integer, MessageBuilder> outputMessages = new TreeMap<>();
        private final TreeMap<Integer, ObjectNode> tools = new TreeMap<>();
        private final TreeMap<Integer, ObjectNode> prompts = new TreeMap<>();
        private final TreeMap<Integer, ObjectNode> choices = new TreeMap<>();

        private AnyValue rawInput;
        private AnyValue rawOutput;
        private String inputMimeType;
        private String outputMimeType;
        private String responseModel;
        private String model;
        private String requestModel;
        private String provider;
        private String system;
        private String spanKind;
        private String sessionId;
        private BigDecimal totalEstimatedCost;
        private JsonNode functionCall;

        private void accept(KeyValue attribute) {
            String key = attribute.getKey();
            AnyValue value = attribute.getValue();

            if (acceptExact(key, value)
                    || acceptMessage(key, value)
                    || acceptIndexedObject(key, value)
                    || acceptUsage(key, value)) {
                consumedKeys.add(key);
                return;
            }

            if (isOpenInferenceAttribute(key)) {
                metadata.set(key, toJsonNode(value));
                consumedKeys.add(key);
            }
        }

        private boolean acceptExact(String key, AnyValue value) {
            return switch (key) {
                case SPAN_KIND -> {
                    spanKind = stringValueOrMetadata(key, value);
                    metadata.set(key, toJsonNode(value));
                    yield true;
                }
                case INPUT_VALUE -> {
                    rawInput = value;
                    yield true;
                }
                case OUTPUT_VALUE -> {
                    rawOutput = value;
                    yield true;
                }
                case INPUT_MIME_TYPE -> {
                    inputMimeType = stringValueOrMetadata(key, value);
                    metadata.set(key, toJsonNode(value));
                    yield true;
                }
                case OUTPUT_MIME_TYPE -> {
                    outputMimeType = stringValueOrMetadata(key, value);
                    metadata.set(key, toJsonNode(value));
                    yield true;
                }
                case "llm.response.model_name" -> {
                    responseModel = stringValueOrMetadata(key, value);
                    yield true;
                }
                case "llm.model_name" -> {
                    model = stringValueOrMetadata(key, value);
                    yield true;
                }
                case "llm.request.model_name" -> {
                    requestModel = stringValueOrMetadata(key, value);
                    yield true;
                }
                case "llm.provider" -> {
                    provider = stringValueOrMetadata(key, value);
                    yield true;
                }
                case "llm.system" -> {
                    system = stringValueOrMetadata(key, value);
                    yield true;
                }
                case "llm.invocation_parameters" -> {
                    JsonNode parsed = parseJsonStringOrMetadata(key, value);
                    if (parsed != null) {
                        input.set("invocation_parameters", parsed);
                    }
                    yield true;
                }
                case "llm.function_call" -> {
                    functionCall = parseJsonStringOrMetadata(key, value);
                    yield true;
                }
                case "llm.finish_reason" -> {
                    putStringOrMetadata(output, "finish_reason", key, value);
                    yield true;
                }
                case "llm.prompt_template.template" -> {
                    putPromptTemplateField("template", key, value, false);
                    yield true;
                }
                case "llm.prompt_template.variables" -> {
                    putPromptTemplateField("variables", key, value, true);
                    yield true;
                }
                case "llm.prompt_template.version" -> {
                    putPromptTemplateField("version", key, value, false);
                    yield true;
                }
                case "llm.cost.total" -> {
                    totalEstimatedCost = OpenTelemetryMappingUtils.extractCost(value).orElse(null);
                    if (totalEstimatedCost == null) {
                        metadata.set(key, toJsonNode(value));
                    }
                    yield true;
                }
                case "session.id" -> {
                    sessionId = stringValueOrMetadata(key, value);
                    yield true;
                }
                case "user.id" -> {
                    metadata.set(key, toJsonNode(value));
                    yield true;
                }
                case "tag.tags" -> {
                    var extractedTags = OpenTelemetryMappingUtils.extractTags(value);
                    boolean validTagsValue = value.hasStringValue()
                            || (value.hasArrayValue()
                                    && value.getArrayValue().getValuesList().stream()
                                            .allMatch(AnyValue::hasStringValue));
                    if (!validTagsValue) {
                        metadata.set(key, toJsonNode(value));
                    } else {
                        tags.addAll(extractedTags);
                    }
                    yield true;
                }
                case "metadata" -> {
                    mergeMetadata(value);
                    yield true;
                }
                default -> false;
            };
        }

        private boolean acceptMessage(String key, AnyValue value) {
            Matcher matcher = MESSAGE_ATTRIBUTE.matcher(key);
            if (!matcher.matches()) {
                return false;
            }

            Integer messageIndex = parseIndex(matcher.group(2));
            if (messageIndex == null) {
                metadata.set(key, toJsonNode(value));
                return true;
            }

            TreeMap<Integer, MessageBuilder> messages = "input".equals(matcher.group(1))
                    ? inputMessages
                    : outputMessages;
            MessageBuilder message = messages.computeIfAbsent(messageIndex, ignored -> new MessageBuilder());
            if (!message.accept(matcher.group(3), value)) {
                metadata.set(key, toJsonNode(value));
            }
            return true;
        }

        private boolean acceptIndexedObject(String key, AnyValue value) {
            Matcher toolMatcher = TOOL_ATTRIBUTE.matcher(key);
            if (toolMatcher.matches()) {
                Integer index = parseIndex(toolMatcher.group(1));
                if (index == null) {
                    metadata.set(key, toJsonNode(value));
                    return true;
                }
                String field = toolMatcher.group(2);
                ObjectNode tool = tools.computeIfAbsent(index, ignored -> JsonUtils.createObjectNode());
                if ("json_schema".equals(field)) {
                    JsonNode parsed = parseJsonStringOrMetadata(key, value);
                    if (parsed != null) {
                        tool.set(field, parsed);
                    }
                } else {
                    putStringOrMetadata(tool, field, key, value);
                }
                return true;
            }

            Matcher promptMatcher = PROMPT_ATTRIBUTE.matcher(key);
            if (promptMatcher.matches()) {
                Integer index = parseIndex(promptMatcher.group(1));
                if (index == null) {
                    metadata.set(key, toJsonNode(value));
                    return true;
                }
                ObjectNode prompt = prompts.computeIfAbsent(index, ignored -> JsonUtils.createObjectNode());
                putStringOrMetadata(prompt, "text", key, value);
                return true;
            }

            Matcher choiceMatcher = CHOICE_ATTRIBUTE.matcher(key);
            if (choiceMatcher.matches()) {
                Integer index = parseIndex(choiceMatcher.group(1));
                if (index == null) {
                    metadata.set(key, toJsonNode(value));
                    return true;
                }
                ObjectNode choice = choices.computeIfAbsent(index, ignored -> JsonUtils.createObjectNode());
                putStringOrMetadata(choice, "text", key, value);
                return true;
            }
            return false;
        }

        private boolean acceptUsage(String key, AnyValue value) {
            String usageKey = USAGE_KEYS.get(key);
            if (usageKey == null) {
                return false;
            }

            Integer tokenCount = nonNegativeInt(value);
            if (tokenCount == null) {
                metadata.set(key, toJsonNode(value));
            } else {
                usage.put(usageKey, tokenCount);
            }
            return true;
        }

        private Result finish() {
            if (!inputMessages.isEmpty()) {
                ArrayNode messages = buildMessages(inputMessages, false);
                if (!messages.isEmpty()) {
                    input.set("messages", messages);
                }
            }
            if (!outputMessages.isEmpty()) {
                ArrayNode messages = buildMessages(outputMessages, true);
                if (!messages.isEmpty()) {
                    output.set("messages", messages);
                }
            }
            if (!tools.isEmpty()) {
                ArrayNode normalizedTools = buildArray(tools);
                if (!normalizedTools.isEmpty()) {
                    input.set("tools", normalizedTools);
                }
            }
            if (!prompts.isEmpty()) {
                ArrayNode normalizedPrompts = buildArray(prompts);
                if (!normalizedPrompts.isEmpty()) {
                    input.set("prompts", normalizedPrompts);
                }
            }
            if (!choices.isEmpty()) {
                ArrayNode normalizedChoices = buildArray(choices);
                if (!normalizedChoices.isEmpty()) {
                    output.set("choices", normalizedChoices);
                }
            }
            if (functionCall != null) {
                output.set("function_call", functionCall);
            }

            JsonNode parsedInput = rawInput == null ? null : parseRawValue(rawInput, inputMimeType, INPUT_VALUE);
            JsonNode parsedOutput = rawOutput == null ? null : parseRawValue(rawOutput, outputMimeType, OUTPUT_VALUE);

            return new Result(
                    Set.copyOf(consumedKeys),
                    parsedInput,
                    rawInput != null,
                    parsedOutput,
                    rawOutput != null,
                    input,
                    output,
                    metadata,
                    Map.copyOf(usage),
                    Set.copyOf(tags),
                    StringUtils.firstNonBlank(responseModel, model, requestModel),
                    StringUtils.firstNonBlank(provider, system),
                    toSpanType(spanKind),
                    totalEstimatedCost,
                    sessionId);
        }

        private ArrayNode buildMessages(TreeMap<Integer, MessageBuilder> messages, boolean outputSide) {
            ArrayNode result = JsonUtils.createArrayNode();
            messages.values().forEach(messageBuilder -> {
                ObjectNode message = messageBuilder.build();
                JsonNode legacyFunctionCall = message.remove("function_call");
                if (outputSide && functionCall == null && legacyFunctionCall != null) {
                    functionCall = legacyFunctionCall;
                } else if (legacyFunctionCall != null) {
                    message.set("function_call", legacyFunctionCall);
                }
                if (!message.isEmpty()) {
                    result.add(message);
                }
            });
            return result;
        }

        private void putPromptTemplateField(String field, String originalKey, AnyValue value, boolean parseJson) {
            if (parseJson) {
                JsonNode parsed = parseJsonStringOrMetadata(originalKey, value);
                if (parsed != null) {
                    promptTemplate().set(field, parsed);
                }
                return;
            }
            if (!value.hasStringValue()) {
                metadata.set(originalKey, toJsonNode(value));
                return;
            }
            promptTemplate().put(field, value.getStringValue());
        }

        private ObjectNode promptTemplate() {
            JsonNode existing = input.get("prompt_template");
            if (existing instanceof ObjectNode object) {
                return object;
            }
            ObjectNode template = JsonUtils.createObjectNode();
            input.set("prompt_template", template);
            return template;
        }

        private void mergeMetadata(AnyValue value) {
            if (!value.hasStringValue()) {
                metadata.set("metadata", toJsonNode(value));
                return;
            }
            try {
                JsonNode parsed = JsonUtils.getJsonNodeFromString(value.getStringValue());
                if (!parsed.isObject()) {
                    metadata.set("metadata", parsed);
                    return;
                }
                parsed.fields().forEachRemaining(entry -> {
                    if (!RESERVED_METADATA_KEYS.contains(entry.getKey())) {
                        metadata.set(entry.getKey(), entry.getValue());
                    }
                });
            } catch (UncheckedIOException exception) {
                log.debug("Failed to parse OpenInference metadata as JSON", exception);
                metadata.put("metadata", value.getStringValue());
            }
        }

        private String stringValueOrMetadata(String key, AnyValue value) {
            if (value.hasStringValue()) {
                return StringUtils.trimToNull(value.getStringValue());
            }
            metadata.set(key, toJsonNode(value));
            return null;
        }

        private void putStringOrMetadata(ObjectNode target, String field, String originalKey, AnyValue value) {
            if (value.hasStringValue()) {
                target.put(field, value.getStringValue());
            } else {
                metadata.set(originalKey, toJsonNode(value));
            }
        }

        private JsonNode parseJsonStringOrMetadata(String originalKey, AnyValue value) {
            if (!value.hasStringValue()) {
                metadata.set(originalKey, toJsonNode(value));
                return null;
            }
            return parseJsonString(value.getStringValue());
        }
    }

    private static final class MessageBuilder {

        private final ObjectNode message = JsonUtils.createObjectNode();
        private final TreeMap<Integer, ContentBuilder> contents = new TreeMap<>();
        private final TreeMap<Integer, ToolCallBuilder> toolCalls = new TreeMap<>();
        private final ObjectNode functionCall = JsonUtils.createObjectNode();

        private boolean accept(String path, AnyValue value) {
            if (Set.of("role", "content", "name", "tool_call_id").contains(path)) {
                if (!value.hasStringValue()) {
                    return false;
                }
                message.put(path, value.getStringValue());
                return true;
            }
            if ("function_call_name".equals(path)) {
                if (!value.hasStringValue()) {
                    return false;
                }
                functionCall.put("name", value.getStringValue());
                return true;
            }
            if ("function_call_arguments_json".equals(path)) {
                if (!value.hasStringValue()) {
                    return false;
                }
                functionCall.set("arguments", parseJsonString(value.getStringValue()));
                return true;
            }

            Matcher contentMatcher = MESSAGE_CONTENT_ATTRIBUTE.matcher(path);
            if (contentMatcher.matches()) {
                Integer index = parseIndex(contentMatcher.group(1));
                if (index == null) {
                    return false;
                }
                return contents.computeIfAbsent(index, ignored -> new ContentBuilder())
                        .accept(contentMatcher.group(2), value);
            }

            Matcher toolCallMatcher = MESSAGE_TOOL_CALL_ATTRIBUTE.matcher(path);
            if (toolCallMatcher.matches()) {
                Integer index = parseIndex(toolCallMatcher.group(1));
                if (index == null) {
                    return false;
                }
                return toolCalls.computeIfAbsent(index, ignored -> new ToolCallBuilder())
                        .accept(toolCallMatcher.group(2), value);
            }
            return false;
        }

        private ObjectNode build() {
            if (!contents.isEmpty()) {
                ArrayNode array = JsonUtils.createArrayNode();
                contents.values().stream().map(ContentBuilder::build).filter(node -> !node.isEmpty())
                        .forEach(array::add);
                if (!array.isEmpty()) {
                    message.set("contents", array);
                }
            }
            if (!toolCalls.isEmpty()) {
                ArrayNode array = JsonUtils.createArrayNode();
                toolCalls.values().stream().map(ToolCallBuilder::build).filter(node -> !node.isEmpty())
                        .forEach(array::add);
                if (!array.isEmpty()) {
                    message.set("tool_calls", array);
                }
            }
            if (!functionCall.isEmpty()) {
                message.set("function_call", functionCall);
            }
            return message;
        }
    }

    private static final class ContentBuilder {

        private final ObjectNode content = JsonUtils.createObjectNode();
        private final ObjectNode image = JsonUtils.createObjectNode();
        private final ObjectNode audio = JsonUtils.createObjectNode();
        private final ToolCallBuilder toolCall = new ToolCallBuilder();

        private boolean accept(String path, AnyValue value) {
            if (Set.of("message_content.type", "message_content.text", "message_content.id",
                    "message_content.signature", "message_content.data", "message_content.encrypted_content")
                    .contains(path)) {
                if (!value.hasStringValue()) {
                    return false;
                }
                content.put(path.substring("message_content.".length()), value.getStringValue());
                return true;
            }
            if ("message_content.image.image.url".equals(path)) {
                if (!value.hasStringValue()) {
                    return false;
                }
                image.put("url", value.getStringValue());
                return true;
            }
            if (path.startsWith("message_content.audio.audio.")) {
                String field = path.substring("message_content.audio.audio.".length());
                if (!Set.of("url", "mime_type", "transcript").contains(field) || !value.hasStringValue()) {
                    return false;
                }
                audio.put(field, value.getStringValue());
                return true;
            }
            if (path.startsWith("tool_call.")) {
                return toolCall.accept(path.substring("tool_call.".length()), value);
            }
            return false;
        }

        private ObjectNode build() {
            if (!image.isEmpty()) {
                content.set("image", image);
            }
            if (!audio.isEmpty()) {
                content.set("audio", audio);
            }
            ObjectNode builtToolCall = toolCall.build();
            if (!builtToolCall.isEmpty()) {
                content.set("tool_call", builtToolCall);
            }
            return content;
        }
    }

    private static final class ToolCallBuilder {

        private final ObjectNode toolCall = JsonUtils.createObjectNode();
        private final ObjectNode function = JsonUtils.createObjectNode();

        private boolean accept(String path, AnyValue value) {
            if (!value.hasStringValue()) {
                return false;
            }
            return switch (path) {
                case "id" -> {
                    toolCall.put("id", value.getStringValue());
                    yield true;
                }
                case "function.name" -> {
                    function.put("name", value.getStringValue());
                    yield true;
                }
                case "function.arguments" -> {
                    // OpenInference defines tool arguments as an opaque JSON string. Preserve it verbatim.
                    function.put("arguments", value.getStringValue());
                    yield true;
                }
                case "reasoning_signature" -> {
                    toolCall.put("reasoning_signature", value.getStringValue());
                    yield true;
                }
                default -> false;
            };
        }

        private ObjectNode build() {
            if (!function.isEmpty()) {
                toolCall.set("function", function);
            }
            return toolCall;
        }
    }

    private static ArrayNode buildArray(TreeMap<Integer, ObjectNode> values) {
        ArrayNode result = JsonUtils.createArrayNode();
        values.values().stream().filter(node -> !node.isEmpty()).forEach(result::add);
        return result;
    }

    private static Integer parseIndex(String rawIndex) {
        try {
            int index = Integer.parseInt(rawIndex);
            return index >= 0 ? index : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Integer nonNegativeInt(AnyValue value) {
        long parsed;
        if (value.hasIntValue()) {
            parsed = value.getIntValue();
        } else if (value.hasStringValue()) {
            try {
                parsed = Long.parseLong(value.getStringValue());
            } catch (NumberFormatException exception) {
                return null;
            }
        } else {
            return null;
        }
        if (parsed < 0 || parsed > Integer.MAX_VALUE) {
            return null;
        }
        return (int) parsed;
    }

    private static JsonNode parseRawValue(AnyValue value, String mimeType, String key) {
        if (!value.hasStringValue()) {
            return toJsonNode(value);
        }
        if (!JSON_MIME_TYPE.equals(normalizeMimeType(mimeType))) {
            return JsonUtils.valueToTree(value.getStringValue());
        }
        try {
            return JsonUtils.getJsonNodeFromString(value.getStringValue());
        } catch (UncheckedIOException exception) {
            log.debug("Failed to parse OpenInference {} as JSON; preserving the original value", key, exception);
            return JsonUtils.valueToTree(value.getStringValue());
        }
    }

    private static String normalizeMimeType(String mimeType) {
        if (mimeType == null) {
            return null;
        }
        int parameters = mimeType.indexOf(';');
        String type = parameters >= 0 ? mimeType.substring(0, parameters) : mimeType;
        return type.trim().toLowerCase(Locale.ROOT);
    }

    private static JsonNode parseJsonString(String value) {
        return JsonUtils.getJsonNodeFromStringWithFallback(value);
    }

    private static SpanType toSpanType(String kind) {
        if (kind == null) {
            return SpanType.general;
        }
        return switch (kind.toUpperCase(Locale.ROOT)) {
            case "LLM" -> SpanType.llm;
            case "TOOL" -> SpanType.tool;
            case "GUARDRAIL" -> SpanType.guardrail;
            default -> SpanType.general;
        };
    }

    private static boolean isOpenInferenceAttribute(String key) {
        if ("metadata".equals(key)) {
            return true;
        }
        return OPENINFERENCE_PREFIXES.stream().anyMatch(key::startsWith);
    }

    private static JsonNode toJsonNode(AnyValue value) {
        return switch (value.getValueCase()) {
            case STRING_VALUE -> JsonUtils.valueToTree(value.getStringValue());
            case BOOL_VALUE -> JsonUtils.valueToTree(value.getBoolValue());
            case INT_VALUE -> JsonUtils.valueToTree(value.getIntValue());
            case DOUBLE_VALUE -> JsonUtils.valueToTree(value.getDoubleValue());
            case BYTES_VALUE -> BinaryNode.valueOf(value.getBytesValue().toByteArray());
            case ARRAY_VALUE -> {
                ArrayNode array = JsonUtils.createArrayNode();
                value.getArrayValue().getValuesList().stream().map(OpenInferenceSpanNormalizer::toJsonNode)
                        .forEach(array::add);
                yield array;
            }
            case KVLIST_VALUE -> {
                ObjectNode object = JsonUtils.createObjectNode();
                value.getKvlistValue().getValuesList()
                        .forEach(entry -> object.set(entry.getKey(), toJsonNode(entry.getValue())));
                yield object;
            }
            case VALUE_NOT_SET -> NullNode.getInstance();
            default -> NullNode.getInstance();
        };
    }
}
