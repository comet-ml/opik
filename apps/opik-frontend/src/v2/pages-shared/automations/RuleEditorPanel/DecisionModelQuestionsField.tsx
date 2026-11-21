import React from "react";
import { UseFormReturn } from "react-hook-form";
import get from "lodash/get";

import { FormErrorSkeleton } from "@/ui/form";
import { LLMPromptTemplate } from "@/types/llm";
import { UI_EVALUATORS_RULE_TYPE } from "@/types/automations";
import { EvaluationRuleFormType } from "@/v2/pages-shared/automations/RuleEditorPanel/schema";
import DecisionModelQuestionsCard from "@/v2/pages-shared/automations/RuleEditorPanel/DecisionModelQuestionsCard";
import TemplateEmptyState from "@/v2/pages-shared/automations/RuleEditorPanel/TemplateEmptyState";
import useTemplateStart from "@/v2/pages-shared/automations/RuleEditorPanel/useTemplateStart";
import { getRuleTemplateOptions } from "@/v2/pages-shared/automations/RuleEditorPanel/ruleTemplates";

type DecisionModelQuestionsFieldProps = {
  form: UseFormReturn<EvaluationRuleFormType>;
  onSelectTemplate: (template: LLMPromptTemplate, label: string) => void;
};

const DecisionModelQuestionsField: React.FC<
  DecisionModelQuestionsFieldProps
> = ({ form, onSelectTemplate }) => {
  const { showStartCards, startBlank, startWithTemplate } = useTemplateStart(
    form,
    onSelectTemplate,
  );

  if (!showStartCards) return <DecisionModelQuestionsCard form={form} />;

  const startError = get(form.formState.errors, [
    "llmJudgeDetails",
    "messages",
    0,
    "content",
    "message",
  ]);

  return (
    <div className="flex flex-col gap-1">
      <TemplateEmptyState
        label="Questions"
        options={getRuleTemplateOptions(
          UI_EVALUATORS_RULE_TYPE.decision_model,
          form.watch("scope"),
        )}
        blankLabel="Write your own"
        blankDescription="Write your own questions and the text to judge."
        onStartBlank={startBlank}
        onSelect={startWithTemplate}
      />
      {startError && <FormErrorSkeleton>{startError}</FormErrorSkeleton>}
    </div>
  );
};

export default DecisionModelQuestionsField;
