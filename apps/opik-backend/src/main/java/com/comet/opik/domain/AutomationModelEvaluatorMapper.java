package com.comet.opik.domain;

import com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

import java.time.Instant;

@Mapper(imports = Instant.class)
interface AutomationModelEvaluatorMapper {

    AutomationModelEvaluatorMapper INSTANCE = Mappers.getMapper(AutomationModelEvaluatorMapper.class);

    @Mapping(target = "code", expression = "java(map(model.code()))")
    AutomationRuleEvaluatorLlmAsJudge map(LlmAsJudgeAutomationRuleEvaluatorModel model);

    LlmAsJudgeAutomationRuleEvaluatorModel map(AutomationRuleEvaluatorLlmAsJudge dto);

    AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode map(LlmAsJudgeAutomationRuleEvaluatorModel.LlmAsJudgeCode detail);

    LlmAsJudgeAutomationRuleEvaluatorModel.LlmAsJudgeCode map(AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode code);
}
