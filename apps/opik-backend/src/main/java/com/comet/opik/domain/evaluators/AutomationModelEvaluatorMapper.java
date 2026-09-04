package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorSpanLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorSpanUserDefinedMetricPython;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUserDefinedMetricPython;
import com.comet.opik.api.evaluators.LlmAsJudgeMessage;
import com.comet.opik.api.evaluators.LlmAsJudgeMessageContent;
import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.filter.SpanFilter;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.api.filter.TraceThreadFilter;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import dev.langchain4j.data.message.ChatMessageType;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Mapper
interface AutomationModelEvaluatorMapper {

    AutomationModelEvaluatorMapper INSTANCE = Mappers.getMapper(AutomationModelEvaluatorMapper.class);

    Logger log = LoggerFactory.getLogger(AutomationModelEvaluatorMapper.class);

    @Mapping(target = "id", expression = "java(model.id())")
    @Mapping(target = "projectId", expression = "java(model.projectId())")
    @Mapping(target = "projectName", expression = "java(model.projectName())")
    @Mapping(target = "projects", expression = "java(model.projects())")
    @Mapping(target = "name", expression = "java(model.name())")
    @Mapping(target = "samplingRate", expression = "java(model.samplingRate())")
    @Mapping(target = "enabled", expression = "java(model.enabled())")
    @Mapping(target = "triggerScope", expression = "java(model.triggerScope())")
    @Mapping(target = "createdAt", expression = "java(model.createdAt())")
    @Mapping(target = "createdBy", expression = "java(model.createdBy())")
    @Mapping(target = "lastUpdatedAt", expression = "java(model.lastUpdatedAt())")
    @Mapping(target = "lastUpdatedBy", expression = "java(model.lastUpdatedBy())")
    @Mapping(target = "code", expression = "java(map(model.code()))")
    @Mapping(target = "filters", expression = "java(mapFilters(model))")
    AutomationRuleEvaluatorLlmAsJudge map(LlmAsJudgeAutomationRuleEvaluatorModel model);

    @Mapping(target = "id", expression = "java(model.id())")
    @Mapping(target = "projectId", expression = "java(model.projectId())")
    @Mapping(target = "projectName", expression = "java(model.projectName())")
    @Mapping(target = "projects", expression = "java(model.projects())")
    @Mapping(target = "name", expression = "java(model.name())")
    @Mapping(target = "samplingRate", expression = "java(model.samplingRate())")
    @Mapping(target = "enabled", expression = "java(model.enabled())")
    @Mapping(target = "triggerScope", expression = "java(model.triggerScope())")
    @Mapping(target = "createdAt", expression = "java(model.createdAt())")
    @Mapping(target = "createdBy", expression = "java(model.createdBy())")
    @Mapping(target = "lastUpdatedAt", expression = "java(model.lastUpdatedAt())")
    @Mapping(target = "lastUpdatedBy", expression = "java(model.lastUpdatedBy())")
    @Mapping(target = "code", expression = "java(map(model.code()))")
    @Mapping(target = "filters", expression = "java(mapFilters(model))")
    AutomationRuleEvaluatorTraceThreadLlmAsJudge map(TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel model);

    @Mapping(target = "id", expression = "java(model.id())")
    @Mapping(target = "projectId", expression = "java(model.projectId())")
    @Mapping(target = "projectName", expression = "java(model.projectName())")
    @Mapping(target = "projects", expression = "java(model.projects())")
    @Mapping(target = "name", expression = "java(model.name())")
    @Mapping(target = "samplingRate", expression = "java(model.samplingRate())")
    @Mapping(target = "enabled", expression = "java(model.enabled())")
    @Mapping(target = "triggerScope", expression = "java(model.triggerScope())")
    @Mapping(target = "createdAt", expression = "java(model.createdAt())")
    @Mapping(target = "createdBy", expression = "java(model.createdBy())")
    @Mapping(target = "lastUpdatedAt", expression = "java(model.lastUpdatedAt())")
    @Mapping(target = "lastUpdatedBy", expression = "java(model.lastUpdatedBy())")
    @Mapping(target = "code", expression = "java(map(model.code()))")
    @Mapping(target = "filters", expression = "java(mapFilters(model))")
    AutomationRuleEvaluatorUserDefinedMetricPython map(UserDefinedMetricPythonAutomationRuleEvaluatorModel model);

    @Mapping(target = "id", expression = "java(model.id())")
    @Mapping(target = "projectId", expression = "java(model.projectId())")
    @Mapping(target = "projectName", expression = "java(model.projectName())")
    @Mapping(target = "projects", expression = "java(model.projects())")
    @Mapping(target = "name", expression = "java(model.name())")
    @Mapping(target = "samplingRate", expression = "java(model.samplingRate())")
    @Mapping(target = "enabled", expression = "java(model.enabled())")
    @Mapping(target = "triggerScope", expression = "java(model.triggerScope())")
    @Mapping(target = "createdAt", expression = "java(model.createdAt())")
    @Mapping(target = "createdBy", expression = "java(model.createdBy())")
    @Mapping(target = "lastUpdatedAt", expression = "java(model.lastUpdatedAt())")
    @Mapping(target = "lastUpdatedBy", expression = "java(model.lastUpdatedBy())")
    @Mapping(target = "code", expression = "java(map(model.code()))")
    @Mapping(target = "filters", expression = "java(mapFilters(model))")
    AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython map(
            TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel model);

    @Mapping(target = "id", expression = "java(model.id())")
    @Mapping(target = "projectId", expression = "java(model.projectId())")
    @Mapping(target = "projectName", expression = "java(model.projectName())")
    @Mapping(target = "projects", expression = "java(model.projects())")
    @Mapping(target = "name", expression = "java(model.name())")
    @Mapping(target = "samplingRate", expression = "java(model.samplingRate())")
    @Mapping(target = "enabled", expression = "java(model.enabled())")
    @Mapping(target = "triggerScope", expression = "java(model.triggerScope())")
    @Mapping(target = "createdAt", expression = "java(model.createdAt())")
    @Mapping(target = "createdBy", expression = "java(model.createdBy())")
    @Mapping(target = "lastUpdatedAt", expression = "java(model.lastUpdatedAt())")
    @Mapping(target = "lastUpdatedBy", expression = "java(model.lastUpdatedBy())")
    @Mapping(target = "code", expression = "java(map(model.code()))")
    @Mapping(target = "filters", expression = "java(mapFilters(model))")
    AutomationRuleEvaluatorSpanLlmAsJudge map(SpanLlmAsJudgeAutomationRuleEvaluatorModel model);

    @Mapping(target = "id", expression = "java(model.id())")
    @Mapping(target = "projectId", expression = "java(model.projectId())")
    @Mapping(target = "projectName", expression = "java(model.projectName())")
    @Mapping(target = "projects", expression = "java(model.projects())")
    @Mapping(target = "name", expression = "java(model.name())")
    @Mapping(target = "samplingRate", expression = "java(model.samplingRate())")
    @Mapping(target = "enabled", expression = "java(model.enabled())")
    @Mapping(target = "triggerScope", expression = "java(model.triggerScope())")
    @Mapping(target = "createdAt", expression = "java(model.createdAt())")
    @Mapping(target = "createdBy", expression = "java(model.createdBy())")
    @Mapping(target = "lastUpdatedAt", expression = "java(model.lastUpdatedAt())")
    @Mapping(target = "lastUpdatedBy", expression = "java(model.lastUpdatedBy())")
    @Mapping(target = "code", expression = "java(map(model.code()))")
    @Mapping(target = "filters", expression = "java(mapFilters(model))")
    AutomationRuleEvaluatorSpanUserDefinedMetricPython map(
            SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel model);

    @Mapping(target = "filters", expression = "java(map(dto.getFilters()))")
    LlmAsJudgeAutomationRuleEvaluatorModel map(AutomationRuleEvaluatorLlmAsJudge dto);

    @Mapping(target = "filters", expression = "java(map(dto.getFilters()))")
    TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel map(AutomationRuleEvaluatorTraceThreadLlmAsJudge dto);

    @Mapping(target = "filters", expression = "java(map(dto.getFilters()))")
    UserDefinedMetricPythonAutomationRuleEvaluatorModel map(AutomationRuleEvaluatorUserDefinedMetricPython dto);

    @Mapping(target = "filters", expression = "java(map(dto.getFilters()))")
    TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel map(
            AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython dto);

    @Mapping(target = "filters", expression = "java(map(dto.getFilters()))")
    SpanLlmAsJudgeAutomationRuleEvaluatorModel map(AutomationRuleEvaluatorSpanLlmAsJudge dto);

    @Mapping(target = "filters", expression = "java(map(dto.getFilters()))")
    SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel map(AutomationRuleEvaluatorSpanUserDefinedMetricPython dto);

    AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode map(LlmAsJudgeAutomationRuleEvaluatorModel.LlmAsJudgeCode detail);

    AutomationRuleEvaluatorTraceThreadLlmAsJudge.TraceThreadLlmAsJudgeCode map(
            TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel.TraceThreadLlmAsJudgeCode code);

    AutomationRuleEvaluatorUserDefinedMetricPython.UserDefinedMetricPythonCode map(
            UserDefinedMetricPythonAutomationRuleEvaluatorModel.UserDefinedMetricPythonCode code);

    AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.TraceThreadUserDefinedMetricPythonCode map(
            TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel.TraceThreadUserDefinedMetricPythonCode code);

    AutomationRuleEvaluatorSpanLlmAsJudge.SpanLlmAsJudgeCode map(
            SpanLlmAsJudgeAutomationRuleEvaluatorModel.SpanLlmAsJudgeCode code);

    AutomationRuleEvaluatorSpanUserDefinedMetricPython.SpanUserDefinedMetricPythonCode map(
            SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel.SpanUserDefinedMetricPythonCode code);

    LlmAsJudgeAutomationRuleEvaluatorModel.LlmAsJudgeCode map(AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode code);

    TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel.TraceThreadLlmAsJudgeCode map(
            AutomationRuleEvaluatorTraceThreadLlmAsJudge.TraceThreadLlmAsJudgeCode code);

    UserDefinedMetricPythonAutomationRuleEvaluatorModel.UserDefinedMetricPythonCode map(
            AutomationRuleEvaluatorUserDefinedMetricPython.UserDefinedMetricPythonCode code);

    TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel.TraceThreadUserDefinedMetricPythonCode map(
            AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.TraceThreadUserDefinedMetricPythonCode code);

    SpanLlmAsJudgeAutomationRuleEvaluatorModel.SpanLlmAsJudgeCode map(
            AutomationRuleEvaluatorSpanLlmAsJudge.SpanLlmAsJudgeCode code);

    SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel.SpanUserDefinedMetricPythonCode map(
            AutomationRuleEvaluatorSpanUserDefinedMetricPython.SpanUserDefinedMetricPythonCode code);

    default <T extends Filter> List<T> mapFilters(AutomationRuleEvaluatorModel<?> model) {
        if (StringUtils.isBlank(model.filters())) {
            return List.of();
        }

        return switch (model) {
            case LlmAsJudgeAutomationRuleEvaluatorModel ignored ->
                (List<T>) JsonUtils.readValue(model.filters(), TraceFilter.LIST_TYPE_REFERENCE);
            case UserDefinedMetricPythonAutomationRuleEvaluatorModel ignored ->
                (List<T>) JsonUtils.readValue(model.filters(), TraceFilter.LIST_TYPE_REFERENCE);
            case TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel ignored ->
                (List<T>) JsonUtils.readValue(model.filters(), TraceThreadFilter.LIST_TYPE_REFERENCE);
            case TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel ignored ->
                (List<T>) JsonUtils.readValue(model.filters(), TraceThreadFilter.LIST_TYPE_REFERENCE);
            case SpanLlmAsJudgeAutomationRuleEvaluatorModel ignored ->
                (List<T>) JsonUtils.readValue(model.filters(), SpanFilter.LIST_TYPE_REFERENCE);
            case SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel ignored ->
                (List<T>) JsonUtils.readValue(model.filters(), SpanFilter.LIST_TYPE_REFERENCE);
        };
    }

    default String map(List<? extends Filter> filters) {
        if (filters == null || filters.isEmpty()) {
            return null;
        }
        return JsonUtils.writeValueAsString(filters);
    }

    /**
     * Map LlmAsJudgeMessage to LlmAsJudgeCodeMessage for database storage.
     * Handles the content which can be either a String (content field) or a List (contentArray field).
     */
    default LlmAsJudgeCodeMessage map(LlmAsJudgeMessage message) {
        if (message == null) {
            return null;
        }

        String contentString;
        if (message.isStringContent()) {
            // Simple string content
            contentString = message.content();
        } else if (message.isStructuredContent()) {
            // Structured content (array), serialize to JSON
            contentString = JsonUtils.writeValueAsString(message.contentArray());
        } else {
            // Both are null
            contentString = null;
        }

        return new LlmAsJudgeCodeMessage(message.role().toString(), contentString);
    }

    /**
     * Map list of LlmAsJudgeMessage to list of LlmAsJudgeCodeMessage.
     */
    default List<LlmAsJudgeCodeMessage> mapMessages(List<LlmAsJudgeMessage> messages) {
        if (messages == null) {
            return null;
        }
        return messages.stream()
                .map(this::map)
                .collect(Collectors.toList());
    }

    /**
     * Map LlmAsJudgeCodeMessage from database to LlmAsJudgeMessage for API.
     * Handles the content field which can be stored as either a plain String or a JSON array.
     */
    default LlmAsJudgeMessage map(LlmAsJudgeCodeMessage codeMessage) {
        if (codeMessage == null) {
            return null;
        }

        String contentString = codeMessage.content();
        ChatMessageType role = ChatMessageType.valueOf(codeMessage.role());

        if (contentString == null) {
            return LlmAsJudgeMessage.builder()
                    .role(role)
                    .build();
        }

        return parseContentArray(contentString)
                .map(contentArray -> LlmAsJudgeMessage.builder().role(role).contentArray(contentArray).build())
                .orElseGet(() -> LlmAsJudgeMessage.builder().role(role).content(contentString).build());
    }

    /**
     * Read the stored content back as structured content parts, or empty when it is not that shape.
     *
     * <p>The column keeps no discriminator, so a leading '[' is a hint and not a guarantee: a plain
     * prompt may legitimately open with one ("[Source Text]..."). Treating unparseable content as the
     * plain string it looks like keeps such a rule listable; throwing here fails the whole page, and
     * with it every other rule in the project.
     */
    private Optional<List<LlmAsJudgeMessageContent>> parseContentArray(String contentString) {
        if (!contentString.trim().startsWith("[")) {
            return Optional.empty();
        }

        List<?> rawList;
        try {
            // Deserialize as raw list first to handle potential LinkedHashMap issue.
            // FAIL_ON_TRAILING_TOKENS because the shared mapper does not set it: readValue otherwise
            // stops at the first complete array and drops the rest, so a prompt that opens with an
            // example array and continues in prose would keep the example and lose the instruction.
            rawList = JsonUtils.getMapper()
                    .readerFor(JsonUtils.getMapper().getTypeFactory().constructCollectionType(
                            List.class,
                            Object.class))
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(contentString);
        } catch (JsonProcessingException e) {
            // Prose that happens to open with '[' — the ordinary case, not a failure.
            return Optional.empty();
        }

        // allMatch is vacuously true on an empty list, and a message with no parts is not something
        // the renderer can build: langchain4j rejects a UserMessage with no contents. Empty is prose.
        if (rawList.isEmpty() || !rawList.stream().allMatch(AutomationModelEvaluatorMapper::isContentPart)) {
            // JSON, but not content parts: a null element, or an object carrying no discriminator.
            // Admitting one would hand the renderer a part with a null type, which its switch
            // dereferences.
            return Optional.empty();
        }

        try {
            // Convert each element to LlmAsJudgeMessageContent
            return Optional.of(rawList.stream()
                    .map(this::convertToMessageContent)
                    .toList());
        } catch (IllegalStateException | ClassCastException e) {
            // The discriminator was right and the payload still would not convert. Unlike the two
            // fallbacks above this is surprising, so record it — without the content, which is a
            // customer prompt.
            log.warn("Failed to read judge message content parts, falling back to plain string", e);
            return Optional.empty();
        }
    }

    /**
     * Whether a deserialized element is a content part at all — an object declaring a string
     * discriminator. Deliberately not a closed set: {@code type} is unconstrained on write, so
     * rejecting a value this renderer does not know would downgrade an array the API accepted into a
     * string, and the next save would store it as one. The renderer already skips a type it cannot
     * render; losing the shape on the way out is the worse failure.
     */
    private static boolean isContentPart(Object element) {
        // Deserialized as List<Object>, so Jackson only ever hands us Map / String / Number /
        // Boolean / List / null — never an LlmAsJudgeMessageContent.
        return element instanceof Map<?, ?> map && map.get("type") instanceof String;
    }

    /**
     * Convert a deserialized object (either LlmAsJudgeMessageContent or Map) to LlmAsJudgeMessageContent.
     * This handles the case where Jackson deserializes JSON objects as LinkedHashMap.
     */
    private LlmAsJudgeMessageContent convertToMessageContent(Object obj) {
        if (obj instanceof LlmAsJudgeMessageContent content) {
            return content;
        }

        if (obj instanceof Map<?, ?> map) {
            return LlmAsJudgeMessageContent.builder()
                    .type((String) map.get("type"))
                    .text((String) map.get("text"))
                    .imageUrl(map.get("image_url") != null
                            ? convertToImageUrl(map.get("image_url"))
                            : null)
                    .videoUrl(map.get("video_url") != null
                            ? convertToVideoUrl(map.get("video_url"))
                            : null)
                    .audioUrl(map.get("audio_url") != null
                            ? convertToAudioUrl(map.get("audio_url"))
                            : null)
                    .build();
        }

        throw new IllegalStateException("Unexpected content part type: " + obj.getClass());
    }

    private LlmAsJudgeMessageContent.ImageUrl convertToImageUrl(Object obj) {
        if (obj instanceof LlmAsJudgeMessageContent.ImageUrl imageUrl) {
            return imageUrl;
        }

        if (obj instanceof Map<?, ?> map) {
            return LlmAsJudgeMessageContent.ImageUrl.builder()
                    .url((String) map.get("url"))
                    .detail((String) map.get("detail"))
                    .build();
        }

        throw new IllegalStateException("Unexpected image_url type: " + obj.getClass());
    }

    private LlmAsJudgeMessageContent.VideoUrl convertToVideoUrl(Object obj) {
        if (obj instanceof LlmAsJudgeMessageContent.VideoUrl videoUrl) {
            return videoUrl;
        }

        if (obj instanceof Map<?, ?> map) {
            return LlmAsJudgeMessageContent.VideoUrl.builder()
                    .url((String) map.get("url"))
                    .build();
        }

        throw new IllegalStateException("Unexpected video_url type: " + obj.getClass());
    }

    private LlmAsJudgeMessageContent.AudioUrl convertToAudioUrl(Object obj) {
        if (obj instanceof LlmAsJudgeMessageContent.AudioUrl audioUrl) {
            return audioUrl;
        }

        if (obj instanceof Map<?, ?> map) {
            return LlmAsJudgeMessageContent.AudioUrl.builder()
                    .url((String) map.get("url"))
                    .build();
        }

        throw new IllegalStateException("Unexpected audio_url type: " + obj.getClass());
    }

    /**
     * Map list of LlmAsJudgeCodeMessage to list of LlmAsJudgeMessage.
     */
    default List<LlmAsJudgeMessage> mapCodeMessages(List<LlmAsJudgeCodeMessage> codeMessages) {
        if (codeMessages == null) {
            return null;
        }
        return codeMessages.stream()
                .map(this::map)
                .collect(Collectors.toList());
    }
}
