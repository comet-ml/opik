import isEqual from "lodash/isEqual";

import {
  EVALUATORS_RULE_SCOPE,
  UI_EVALUATORS_RULE_TYPE,
} from "@/types/automations";
import { LLMJudgeSchema, LLMMessage } from "@/types/llm";
import {
  getJudgeTemplateOptions,
  JudgeTemplateOption,
} from "@/v2/pages-shared/automations/RuleEditorPanel/judgeTemplates";
import { DECISION_MODEL_TEMPLATE_OPTIONS } from "@/v2/pages-shared/automations/RuleEditorPanel/decisionModelTemplates";

type TemplateContent = {
  messages: LLMMessage[];
  schema: LLMJudgeSchema[];
};

export const getRuleTemplateOptions = (
  uiType: UI_EVALUATORS_RULE_TYPE,
  scope: EVALUATORS_RULE_SCOPE,
): JudgeTemplateOption[] => {
  if (uiType === UI_EVALUATORS_RULE_TYPE.decision_model) {
    return DECISION_MODEL_TEMPLATE_OPTIONS;
  }
  if (uiType === UI_EVALUATORS_RULE_TYPE.llm_judge) {
    return getJudgeTemplateOptions(scope);
  }
  return [];
};

// The main score takes the rule name, and a judge's main score description is
// hidden and cleared when a new rule is saved, so neither is part of the template.
const toComparable = (
  { messages, schema }: TemplateContent,
  uiType: UI_EVALUATORS_RULE_TYPE,
) => ({
  messages: messages.map(({ role, content }) => ({ role, content })),
  schema: schema.map(({ name, description, type }, index) => ({
    name: index === 0 ? "" : name,
    description:
      index === 0 && uiType === UI_EVALUATORS_RULE_TYPE.llm_judge
        ? ""
        : description,
    type,
  })),
});

export const findSelectedTemplate = (
  content: TemplateContent,
  options: JudgeTemplateOption[],
  uiType: UI_EVALUATORS_RULE_TYPE,
): JudgeTemplateOption | undefined => {
  const current = toComparable(content, uiType);
  return options.find((option) =>
    isEqual(toComparable(option.template, uiType), current),
  );
};
