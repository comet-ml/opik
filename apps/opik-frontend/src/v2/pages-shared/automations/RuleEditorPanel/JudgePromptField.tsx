import React, { useRef } from "react";
import { UseFormReturn } from "react-hook-form";
import { Text } from "lucide-react";
import get from "lodash/get";

import { FormErrorSkeleton, FormField, FormItem, FormMessage } from "@/ui/form";
import { EvaluationRuleFormType } from "@/v2/pages-shared/automations/RuleEditorPanel/schema";
import RuleContentCard from "@/v2/pages-shared/automations/RuleEditorPanel/RuleContentCard";
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
import useRuleTemplatePicker, {
  RuleTemplateActions,
} from "@/v2/pages-shared/automations/RuleEditorPanel/useRuleTemplatePicker";
import { EVALUATORS_RULE_SCOPE } from "@/types/automations";

type JudgePromptFieldProps = {
  form: UseFormReturn<EvaluationRuleFormType>;
  templateActions: RuleTemplateActions;
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
  templateActions,
  mergeInfo,
  datasetColumnNames,
}) => {
  const editorRef = useRef<PromptVariableEditorHandle>(null);
  const scope = form.watch("scope");
  const picker = useRuleTemplatePicker(form, templateActions, () =>
    editorRef.current?.focusAtEnd(),
  );
  const judgeErrors = getJudgeErrors(form);
  const sampleData = useRuleSampleData(
    form.watch("projectIds")[0] ?? "",
    scope,
    false,
    datasetColumnNames,
  );

  const renderCard = (hasError: boolean, content?: React.ReactNode) => (
    <RuleContentCard
      picker={picker}
      contentLabel="Prompt"
      contentIcon={Text}
      contentIconClassName="bg-[var(--rule-icon-llm-judge)]"
      customDescription="Your own prompt, kept while you try templates."
      blankDescription="Write your own prompt from scratch."
      headerEnd={<JudgePromptToolbar form={form} />}
      hasError={hasError}
    >
      {content}
    </RuleContentCard>
  );
  const judgeErrorMessages = judgeErrors.map((message) => (
    <FormErrorSkeleton key={message}>{message}</FormErrorSkeleton>
  ));

  if (picker.showTemplates) {
    const startError = get(form.formState.errors, [
      "llmJudgeDetails",
      "messages",
      0,
      "content",
      "message",
    ]);
    return (
      <div className="flex flex-1 flex-col gap-1">
        {renderCard(judgeErrors.length > 0)}
        {startError && <FormErrorSkeleton>{startError}</FormErrorSkeleton>}
        {judgeErrorMessages}
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
          {renderCard(
            Boolean(fieldState.error) || judgeErrors.length > 0,
            <JudgePromptInput
              value={field.value}
              onChange={field.onChange}
              onBlur={field.onBlur}
              sampleData={sampleData}
              editorRef={editorRef}
              showMedia={scope !== EVALUATORS_RULE_SCOPE.thread}
            />,
          )}
          {sampleData && <PromptVariableHint scope={scope} />}
          <FormMessage />
          {judgeErrorMessages}
        </FormItem>
      )}
    />
  );
};

export default JudgePromptField;
