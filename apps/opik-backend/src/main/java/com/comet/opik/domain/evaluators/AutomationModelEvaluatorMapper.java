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
     * Handles the content field which can be either a String or a List.
     */
    default LlmAsJudgeCodeMessage map(LlmAsJudgeMessage message) {
        if (message == null) {
            return null;
        }

        String contentString;
        if (message.content() == null) {
            contentString = null;
        } else if (message.content() instanceof String stringContent) {
            contentString = stringContent;
        } else {
            // It's a List<LlmAsJudgeMessageContent>, serialize to JSON
            try {
                contentString = JsonUtils.getMapper().writeValueAsString(message.content());
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to serialize message content to JSON", e);
            }
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

        Object content;
        String contentString = codeMessage.content();

        if (contentString == null) {
            content = null;
        } else if (contentString.trim().startsWith("[")) {
            // It's a JSON array, deserialize to List<LlmAsJudgeMessageContent>
            try {
                content = JsonUtils.getMapper().readValue(
                        contentString,
                        JsonUtils.getMapper().getTypeFactory().constructCollectionType(
                                List.class,
                                LlmAsJudgeMessageContent.class));
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to deserialize message content from JSON", e);
            }
        } else {
            // It's a plain string
            content = contentString;
        }

        return new LlmAsJudgeMessage(
                ChatMessageType.valueOf(codeMessage.role()),
                content);
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
