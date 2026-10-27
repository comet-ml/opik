import React, { useRef } from "react";
import { UseFormReturn } from "react-hook-form";
import get from "lodash/get";

import { FormErrorSkeleton, FormField, FormItem, FormMessage } from "@/ui/form";
import { EvaluationRuleFormType } from "@/v2/pages-shared/automations/RuleEditorPanel/schema";
import RuleEditorCard from "@/v2/pages-shared/automations/RuleEditorPanel/RuleEditorCard";
import JudgePromptToolbar from "@/v2/pages-shared/automations/RuleEditorPanel/JudgePromptToolbar";
import JudgePromptInput from "@/v2/pages-shared/automations/RuleEditorPanel/JudgePromptInput";
import { PromptVariableEditorHandle } from "@/v2/pages-shared/llm/PromptVariableEditor/PromptVariableEditor";
import useRuleSampleData from "@/v2/pages-shared/automations/RuleEditorPanel/useRuleSampleData";
import {
  getRecordErrorKeys,
  PromptMergeInfo,
} from "@/v2/pages-shared/automations/RuleEditorPanel/helpers";
import PromptVariableHint from "@/v2/pages-shared/automations/RuleEditorPanel/PromptVariableHint";
import RuleEditorNotice from "@/v2/pages-shared/automations/RuleEditorPanel/RuleEditorNotice";
import TemplateEmptyState from "@/v2/pages-shared/automations/RuleEditorPanel/TemplateEmptyState";
import { getJudgeTemplateOptions } from "@/v2/pages-shared/automations/RuleEditorPanel/judgeTemplates";
import useTemplateStart from "@/v2/pages-shared/automations/RuleEditorPanel/useTemplateStart";
import { LLMPromptTemplate } from "@/types/llm";
import { EVALUATORS_RULE_SCOPE } from "@/types/automations";

type JudgePromptFieldProps = {
  form: UseFormReturn<EvaluationRuleFormType>;
  onSelectTemplate: (template: LLMPromptTemplate, label: string) => void;
  mergeInfo?: PromptMergeInfo | null;
  datasetColumnNames?: string[];
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

  return messages;
};

const JudgePromptField: React.FC<JudgePromptFieldProps> = ({
  form,
  onSelectTemplate,
  mergeInfo,
  datasetColumnNames,
}) => {
  const editorRef = useRef<PromptVariableEditorHandle>(null);
  const scope = form.watch("scope");
  const { showStartCards, startBlank, startWithTemplate } = useTemplateStart(
    form,
    editorRef,
    onSelectTemplate,
  );
  const judgeErrors = getJudgeErrors(form);
  const sampleData = useRuleSampleData(
    form.watch("projectIds")[0] ?? "",
    scope,
    false,
    datasetColumnNames,
  );

  if (showStartCards) {
    const startError = get(form.formState.errors, [
      "llmJudgeDetails",
      "messages",
      0,
      "content",
      "message",
    ]);
    return (
      <div className="flex flex-1 flex-col gap-1">
        <TemplateEmptyState
          label="Prompt"
          options={getJudgeTemplateOptions(scope)}
          blankLabel="Type your prompt"
          blankDescription="Write your own judge prompt from scratch."
          onStartBlank={startBlank}
          onSelect={startWithTemplate}
        />
        {startError && <FormErrorSkeleton>{startError}</FormErrorSkeleton>}
      </div>
    );
  }

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
                onAfterSelectTemplate={() => editorRef.current?.focusAtEnd()}
              />
            }
          >
            <JudgePromptInput
              value={field.value}
              onChange={field.onChange}
              onBlur={field.onBlur}
              sampleData={sampleData}
              editorRef={editorRef}
              showMedia={scope !== EVALUATORS_RULE_SCOPE.thread}
            />
          </RuleEditorCard>
          {sampleData && <PromptVariableHint scope={scope} />}
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
