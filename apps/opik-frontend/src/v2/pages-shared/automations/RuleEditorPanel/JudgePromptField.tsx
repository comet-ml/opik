import React from "react";
import { UseFormReturn } from "react-hook-form";
import get from "lodash/get";

import { Description } from "@/ui/description";
import { Tag } from "@/ui/tag";
import { FormErrorSkeleton, FormField, FormItem, FormMessage } from "@/ui/form";
import { EvaluationRuleFormType } from "@/v2/pages-shared/automations/RuleEditorPanel/schema";
import RuleEditorCard from "@/v2/pages-shared/automations/RuleEditorPanel/RuleEditorCard";
import JudgePromptToolbar from "@/v2/pages-shared/automations/RuleEditorPanel/JudgePromptToolbar";
import JudgePromptInput from "@/v2/pages-shared/automations/RuleEditorPanel/JudgePromptInput";
import useRuleSampleData from "@/v2/pages-shared/automations/RuleEditorPanel/useRuleSampleData";
import {
  getRecordErrorKeys,
  PromptMergeInfo,
} from "@/v2/pages-shared/automations/RuleEditorPanel/helpers";
import { SCOPE_ENTITY_LABEL } from "@/v2/pages-shared/automations/RuleEditorPanel/constants";
import RuleEditorNotice from "@/v2/pages-shared/automations/RuleEditorPanel/RuleEditorNotice";
import { LLMPromptTemplate } from "@/types/llm";
import { EVALUATORS_RULE_SCOPE } from "@/types/automations";

type JudgePromptFieldProps = {
  form: UseFormReturn<EvaluationRuleFormType>;
  onSelectTemplate?: (template: LLMPromptTemplate, label: string) => void;
  mergeInfo?: PromptMergeInfo | null;
};

const getJudgeErrors = (form: UseFormReturn<EvaluationRuleFormType>) => {
  const errors = form.formState.errors;
  const messages: string[] = [];

  const modelError = get(errors, ["llmJudgeDetails", "model", "message"]);
  if (modelError) messages.push(modelError);

  getRecordErrorKeys(errors, ["llmJudgeDetails", "variables"]).forEach(
    (variable) => {
      messages.push(
        `Unknown variable {{${variable}}}. Use a path that starts with input, output or metadata, for example {{input.question}}.`,
      );
    },
  );

  if (form.getValues("llmJudgeDetails.parsingVariablesError")) {
    messages.push("The prompt has a variable that is not written correctly.");
  }

  return messages;
};

const JudgePromptField: React.FC<JudgePromptFieldProps> = ({
  form,
  onSelectTemplate,
  mergeInfo,
}) => {
  const scope = form.watch("scope");
  const judgeErrors = getJudgeErrors(form);
  const sampleData = useRuleSampleData(
    form.watch("projectIds")[0] ?? "",
    scope,
  );

  return (
    <FormField
      control={form.control}
      name="llmJudgeDetails.messages.0.content"
      render={({ field, fieldState }) => (
        <FormItem className="flex flex-1 flex-col gap-1">
          {mergeInfo && (
            <RuleEditorNotice>
              <span>
                {`LLM-as-a-Judge is moving to a new format that uses a single prompt. When you save, the ${mergeInfo.messageCount} messages of this evaluator will be combined into one prompt.`}
                {mergeInfo.hasSystemMedia &&
                  " Media in the system message was removed because it was never sent to the model."}
              </span>
            </RuleEditorNotice>
          )}
          <RuleEditorCard
            hasError={Boolean(fieldState.error) || judgeErrors.length > 0}
            header={
              <JudgePromptToolbar
                form={form}
                onSelectTemplate={onSelectTemplate}
              />
            }
          >
            <JudgePromptInput
              value={field.value}
              onChange={field.onChange}
              onBlur={field.onBlur}
              sampleData={sampleData}
              showMedia={scope !== EVALUATORS_RULE_SCOPE.thread}
            />
          </RuleEditorCard>
          {sampleData && (
            <Description className="px-0.5">
              Use{" "}
              <Tag variant="green" className="px-1 align-top font-mono">
                {"{{"}
              </Tag>{" "}
              <span>
                {`to insert variables, which will automatically pull data from ${SCOPE_ENTITY_LABEL[scope]} when the evaluator runs.`}
              </span>
            </Description>
          )}
          <FormMessage />
          {judgeErrors.map((message) => (
            <FormErrorSkeleton key={message}>{message}</FormErrorSkeleton>
          ))}
        </FormItem>
      )}
    />
  );
};

export default JudgePromptField;
