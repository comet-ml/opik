import React from "react";
import { UseFormReturn } from "react-hook-form";

import { LLM_MESSAGE_ROLE, LLMPromptTemplate } from "@/types/llm";
import { PromptVariableEditorHandle } from "@/v2/pages-shared/llm/PromptVariableEditor/PromptVariableEditor";
import { EvaluationRuleFormType } from "@/v2/pages-shared/automations/RuleEditorPanel/schema";
import { JudgeTemplateOption } from "@/v2/pages-shared/automations/RuleEditorPanel/judgeTemplates";

const useTemplateStart = (
  form: UseFormReturn<EvaluationRuleFormType>,
  editorRef: React.RefObject<PromptVariableEditorHandle>,
  onSelectTemplate: (template: LLMPromptTemplate, label: string) => void,
) => {
  const focusEditor = () =>
    window.setTimeout(() => editorRef.current?.focusAtEnd());

  return {
    showStartCards: !form.watch("llmJudgeDetails.messages").length,
    startBlank: () => {
      form.setValue(
        "llmJudgeDetails.messages",
        [{ id: "rule-prompt", role: LLM_MESSAGE_ROLE.user, content: "" }],
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
