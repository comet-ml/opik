import React from "react";
import { UseFormReturn } from "react-hook-form";

import { LLMPromptTemplate } from "@/types/llm";
import { PromptVariableEditorHandle } from "@/v2/pages-shared/llm/PromptVariableEditor/PromptVariableEditor";
import { EvaluationRuleFormType } from "@/v2/pages-shared/automations/RuleEditorPanel/schema";
import { JudgeTemplateOption } from "@/v2/pages-shared/automations/RuleEditorPanel/judgeTemplates";
import { createInitialRulePrompt } from "@/v2/pages-shared/automations/RuleEditorPanel/constants";

const useTemplateStart = (
  form: UseFormReturn<EvaluationRuleFormType>,
  onSelectTemplate: (template: LLMPromptTemplate, label: string) => void,
  editorRef?: React.RefObject<PromptVariableEditorHandle | null>,
) => {
  const focusEditor = () =>
    window.setTimeout(() => editorRef?.current?.focusAtEnd());

  return {
    showStartCards: !form.watch("llmJudgeDetails.messages").length,
    startBlank: () => {
      form.setValue(
        "llmJudgeDetails.messages",
        createInitialRulePrompt(form.getValues("uiType")),
        { shouldDirty: true },
      );
      form.clearErrors("llmJudgeDetails.messages");
      focusEditor();
    },
    startWithTemplate: (option: JudgeTemplateOption) => {
      onSelectTemplate(option.template, option.label);
      focusEditor();
    },
  };
};

export default useTemplateStart;
