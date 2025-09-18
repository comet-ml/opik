package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUserDefinedMetricPython;
import com.comet.opik.api.filter.TraceFilter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

import java.util.List;

@Mapper
interface AutomationModelEvaluatorMapper {

    AutomationModelEvaluatorMapper INSTANCE = Mappers.getMapper(AutomationModelEvaluatorMapper.class);

    ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Mapping(target = "code", expression = "java(map(model.code()))")
    @Mapping(target = "filters", source = "filters")
    AutomationRuleEvaluatorLlmAsJudge map(LlmAsJudgeAutomationRuleEvaluatorModel model);

    @Mapping(target = "code", expression = "java(map(model.code()))")
    @Mapping(target = "filters", source = "filters")
    AutomationRuleEvaluatorTraceThreadLlmAsJudge map(TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel model);

    @Mapping(target = "code", expression = "java(map(model.code()))")
    @Mapping(target = "filters", source = "filters")
    AutomationRuleEvaluatorUserDefinedMetricPython map(UserDefinedMetricPythonAutomationRuleEvaluatorModel model);

    @Mapping(target = "code", expression = "java(map(model.code()))")
    @Mapping(target = "filters", source = "filters")
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
        if (filtersJson == null || filtersJson.isEmpty()) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(filtersJson, new TypeReference<List<TraceFilter>>() {
            });
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse filters JSON", e);
        }
    }

    default String map(List<TraceFilter> filters) {
        if (filters == null || filters.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(filters);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize filters to JSON", e);
        }
    }
}
