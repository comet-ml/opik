package com.comet.opik.domain;

import com.comet.opik.api.AutomationRuleEvaluator;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

import java.time.Instant;

@Mapper(imports = Instant.class)
interface AutomationModelEvaluatorMapper {

    AutomationModelEvaluatorMapper INSTANCE = Mappers.getMapper(AutomationModelEvaluatorMapper.class);

    AutomationRuleEvaluator.AutomationRuleEvaluatorLlmAsJudge map(LlmAsJudgeAutomationRuleEvaluatorModel model);

    LlmAsJudgeAutomationRuleEvaluatorModel map(AutomationRuleEvaluator.AutomationRuleEvaluatorLlmAsJudge dto);

}
