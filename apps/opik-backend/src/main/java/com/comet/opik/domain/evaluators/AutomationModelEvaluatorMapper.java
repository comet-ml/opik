package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUserDefinedMetricPython;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

import java.util.List;

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
}
