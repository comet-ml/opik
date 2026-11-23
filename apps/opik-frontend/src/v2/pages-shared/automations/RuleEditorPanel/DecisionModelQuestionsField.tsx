import React from "react";
import { UseFormReturn } from "react-hook-form";
import { CircleHelp } from "lucide-react";
import get from "lodash/get";

import { FormErrorSkeleton } from "@/ui/form";
import { EvaluationRuleFormType } from "@/v2/pages-shared/automations/RuleEditorPanel/schema";
import DecisionModelQuestions from "@/v2/pages-shared/automations/RuleEditorPanel/DecisionModelQuestions";
import RuleContentCard from "@/v2/pages-shared/automations/RuleEditorPanel/RuleContentCard";
import useRuleTemplatePicker, {
  RuleTemplateActions,
} from "@/v2/pages-shared/automations/RuleEditorPanel/useRuleTemplatePicker";

type DecisionModelQuestionsFieldProps = {
  form: UseFormReturn<EvaluationRuleFormType>;
  templateActions: RuleTemplateActions;
};

const DecisionModelQuestionsField: React.FC<
  DecisionModelQuestionsFieldProps
> = ({ form, templateActions }) => {
  const picker = useRuleTemplatePicker(form, templateActions);
  const startError = get(form.formState.errors, [
    "llmJudgeDetails",
    "messages",
    0,
    "content",
    "message",
  ]);

  return (
    <div className="flex flex-col gap-1">
      <RuleContentCard
        picker={picker}
        contentLabel="Questions"
        contentIcon={CircleHelp}
        contentIconClassName="bg-[var(--rule-icon-decision-model)]"
        customDescription="Your own questions, kept while you try templates."
        blankDescription="Write your own questions from scratch."
        className="flex-none"
      >
        <DecisionModelQuestions form={form} />
      </RuleContentCard>
      {picker.showTemplates && startError && (
        <FormErrorSkeleton>{startError}</FormErrorSkeleton>
      )}
    </div>
  );
};

export default DecisionModelQuestionsField;
