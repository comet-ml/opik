import { useState } from "react";
import { UseFormReturn } from "react-hook-form";

import { EvaluationRuleFormType } from "@/v2/pages-shared/automations/RuleEditorPanel/schema";
import { JudgeTemplateOption } from "@/v2/pages-shared/automations/RuleEditorPanel/judgeTemplates";
import {
  findSelectedTemplate,
  getRuleTemplateOptions,
} from "@/v2/pages-shared/automations/RuleEditorPanel/ruleTemplates";
import type useRuleEditorForm from "@/v2/pages-shared/automations/RuleEditorPanel/useRuleEditorForm";

export type RuleTemplateActions = Pick<
  ReturnType<typeof useRuleEditorForm>,
  "applyTemplate" | "selectCustom" | "startBlank" | "hasCustomContent"
>;

const useRuleTemplatePicker = (
  form: UseFormReturn<EvaluationRuleFormType>,
  actions: RuleTemplateActions,
  focusContent?: () => void,
) => {
  const messages = form.watch("llmJudgeDetails.messages");
  const schema = form.watch("llmJudgeDetails.schema");
  const uiType = form.watch("uiType");
  const options = getRuleTemplateOptions(uiType, form.watch("scope"));
  const hasContent = messages.length > 0;
  const [isPicking, setIsPicking] = useState(!hasContent);
  const selectedOption = hasContent
    ? findSelectedTemplate({ messages, schema }, options, uiType)
    : undefined;

  const pick = (action: () => void) => {
    action();
    setIsPicking(false);
    window.setTimeout(() => focusContent?.());
  };

  return {
    showTemplates: !hasContent || isPicking,
    openTemplates: () => setIsPicking(true),
    openContent: () =>
      hasContent ? setIsPicking(false) : pick(actions.startBlank),
    gridProps: {
      options,
      selectedOption,
      showCustom: actions.hasCustomContent(),
      isCustomSelected: hasContent && !selectedOption,
      onSelect: (option: JudgeTemplateOption) =>
        pick(() => actions.applyTemplate(option.template, option.label)),
      onSelectCustom: () => pick(actions.selectCustom),
      onStartBlank: () => pick(actions.startBlank),
    },
  };
};

export type RuleTemplatePicker = ReturnType<typeof useRuleTemplatePicker>;

export default useRuleTemplatePicker;
