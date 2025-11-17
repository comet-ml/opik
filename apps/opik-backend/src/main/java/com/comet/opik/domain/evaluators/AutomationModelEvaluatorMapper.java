package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUserDefinedMetricPython;
import com.comet.opik.api.evaluators.LlmAsJudgeMessage;
import com.comet.opik.api.evaluators.LlmAsJudgeMessageContent;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import dev.langchain4j.data.message.ChatMessageType;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

import java.util.List;
import java.util.stream.Collectors;

@Mapper
interface AutomationModelEvaluatorMapper {

    AutomationModelEvaluatorMapper INSTANCE = Mappers.getMapper(AutomationModelEvaluatorMapper.class);

    @Mapping(target = "code", expression = "java(map(model.code()))")
    AutomationRuleEvaluatorLlmAsJudge map(LlmAsJudgeAutomationRuleEvaluatorModel model);

    @Mapping(target = "code", expression = "java(map(model.code()))")
    AutomationRuleEvaluatorTraceThreadLlmAsJudge map(TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel model);

    @Mapping(target = "code", expression = "java(map(model.code()))")
    AutomationRuleEvaluatorUserDefinedMetricPython map(UserDefinedMetricPythonAutomationRuleEvaluatorModel model);

    @Mapping(target = "code", expression = "java(map(model.code()))")
    AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython map(
            TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel model);

    LlmAsJudgeAutomationRuleEvaluatorModel map(AutomationRuleEvaluatorLlmAsJudge dto);

    TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel map(AutomationRuleEvaluatorTraceThreadLlmAsJudge dto);

    UserDefinedMetricPythonAutomationRuleEvaluatorModel map(AutomationRuleEvaluatorUserDefinedMetricPython dto);

    TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel map(
            AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython dto);

    AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode map(LlmAsJudgeAutomationRuleEvaluatorModel.LlmAsJudgeCode detail);

    AutomationRuleEvaluatorTraceThreadLlmAsJudge.TraceThreadLlmAsJudgeCode map(
            TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel.TraceThreadLlmAsJudgeCode code);

    AutomationRuleEvaluatorUserDefinedMetricPython.UserDefinedMetricPythonCode map(
            UserDefinedMetricPythonAutomationRuleEvaluatorModel.UserDefinedMetricPythonCode code);

    AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.TraceThreadUserDefinedMetricPythonCode map(
            TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel.TraceThreadUserDefinedMetricPythonCode code);

    LlmAsJudgeAutomationRuleEvaluatorModel.LlmAsJudgeCode map(AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode code);

    TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel.TraceThreadLlmAsJudgeCode map(
            AutomationRuleEvaluatorTraceThreadLlmAsJudge.TraceThreadLlmAsJudgeCode code);

    UserDefinedMetricPythonAutomationRuleEvaluatorModel.UserDefinedMetricPythonCode map(
            AutomationRuleEvaluatorUserDefinedMetricPython.UserDefinedMetricPythonCode code);

    TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel.TraceThreadUserDefinedMetricPythonCode map(
            AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.TraceThreadUserDefinedMetricPythonCode code);

    default List<TraceFilter> map(String filtersJson) {
        if (StringUtils.isBlank(filtersJson)) {
            return List.of();
        }
        try {
            return JsonUtils.getMapper().readValue(filtersJson, TraceFilter.LIST_TYPE_REFERENCE);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse filters JSON", e);
        }
    }

    default String map(List<TraceFilter> filters) {
        if (filters == null || filters.isEmpty()) {
            return null;
        }
        try {
            return JsonUtils.getMapper().writeValueAsString(filters);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize filters to JSON", e);
        }
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
            try {
                contentString = JsonUtils.getMapper().writeValueAsString(message.contentArray());
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to serialize message content to JSON", e);
            }
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
        } else if (contentString.trim().startsWith("[")) {
            // It's a JSON array, deserialize to List<LlmAsJudgeMessageContent>
            try {
                // Deserialize as raw list first to handle potential LinkedHashMap issue
                List<?> rawList = JsonUtils.getMapper().readValue(
                        contentString,
                        JsonUtils.getMapper().getTypeFactory().constructCollectionType(
                                List.class,
                                Object.class));

                // Convert each element to LlmAsJudgeMessageContent
                List<LlmAsJudgeMessageContent> contentArray = rawList.stream()
                        .map(this::convertToMessageContent)
                        .toList();

                return LlmAsJudgeMessage.builder()
                        .role(role)
                        .contentArray(contentArray)
                        .build();
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to deserialize message content from JSON", e);
            }
        } else {
            // It's a plain string
            return LlmAsJudgeMessage.builder()
                    .role(role)
                    .content(contentString)
                    .build();
        }
    }

    /**
     * Convert a deserialized object (either LlmAsJudgeMessageContent or Map) to LlmAsJudgeMessageContent.
     * This handles the case where Jackson deserializes JSON objects as LinkedHashMap.
     */
    private LlmAsJudgeMessageContent convertToMessageContent(Object obj) {
        if (obj instanceof LlmAsJudgeMessageContent content) {
            return content;
        }

        if (obj instanceof java.util.Map<?, ?> map) {
            return LlmAsJudgeMessageContent.builder()
                    .type((String) map.get("type"))
                    .text((String) map.get("text"))
                    .imageUrl(map.get("image_url") != null
                            ? convertToImageUrl(map.get("image_url"))
                            : null)
                    .videoUrl(map.get("video_url") != null
                            ? convertToVideoUrl(map.get("video_url"))
                            : null)
                    .build();
        }

        throw new IllegalStateException("Unexpected content part type: " + obj.getClass());
    }

    private LlmAsJudgeMessageContent.ImageUrl convertToImageUrl(Object obj) {
        if (obj instanceof LlmAsJudgeMessageContent.ImageUrl imageUrl) {
            return imageUrl;
        }

        if (obj instanceof java.util.Map<?, ?> map) {
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

        if (obj instanceof java.util.Map<?, ?> map) {
            return LlmAsJudgeMessageContent.VideoUrl.builder()
                    .url((String) map.get("url"))
                    .build();
        }

        throw new IllegalStateException("Unexpected video_url type: " + obj.getClass());
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
