import React, { useRef, useState } from "react";
import { UseFormReturn } from "react-hook-form";
import get from "lodash/get";
import { Plug } from "lucide-react";

import { Button } from "@/ui/button";
import { usePermissions } from "@/contexts/PermissionsContext";
import { FormErrorSkeleton, FormField, FormItem, FormMessage } from "@/ui/form";
import useAppStore from "@/store/AppStore";
import useProviderKeys from "@/api/provider-keys/useProviderKeys";
import { PROVIDER_TYPE } from "@/types/providers";
import { DECISION_MODELS } from "@/constants/decisionModels";
import SelectBox from "@/shared/SelectBox/SelectBox";
import ManageAIProviderDialog from "@/v2/pages-shared/llm/ManageAIProviderDialog/ManageAIProviderDialog";
import { EvaluationRuleFormType } from "@/v2/pages-shared/automations/RuleEditorPanel/schema";
import { getRecordErrorKeys } from "@/v2/pages-shared/automations/RuleEditorPanel/helpers";
import PromptVariableHint from "@/v2/pages-shared/automations/RuleEditorPanel/PromptVariableHint";
import RuleEditorCard from "@/v2/pages-shared/automations/RuleEditorPanel/RuleEditorCard";
import JudgePromptInput from "@/v2/pages-shared/automations/RuleEditorPanel/JudgePromptInput";
import useRuleSampleData from "@/v2/pages-shared/automations/RuleEditorPanel/useRuleSampleData";
import TemplateEmptyState from "@/v2/pages-shared/automations/RuleEditorPanel/TemplateEmptyState";
import JudgeTemplateMenu from "@/v2/pages-shared/automations/RuleEditorPanel/JudgeTemplateMenu";
import useTemplateStart from "@/v2/pages-shared/automations/RuleEditorPanel/useTemplateStart";
import { DECISION_MODEL_TEMPLATE_OPTIONS } from "@/v2/pages-shared/automations/RuleEditorPanel/decisionModelTemplates";
import { PromptVariableEditorHandle } from "@/v2/pages-shared/llm/PromptVariableEditor/PromptVariableEditor";
import { LLMPromptTemplate } from "@/types/llm";

const getDecisionModelErrors = (
  form: UseFormReturn<EvaluationRuleFormType>,
) => {
  const errors = form.formState.errors;
  const messages = getRecordErrorKeys(errors, [
    "llmJudgeDetails",
    "variables",
  ]).map(
    (variable) =>
      (get(errors, ["llmJudgeDetails", "variables", variable, "message"]) as
        | string
        | undefined) ?? `Unknown variable {{${variable}}}.`,
  );
  return messages;
};

const DecisionModelSelect: React.FC<{
  form: UseFormReturn<EvaluationRuleFormType>;
}> = ({ form }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { data, isPending } = useProviderKeys({ workspaceName });
  const {
    permissions: { canUpdateAIProviders },
  } = usePermissions();
  const [connectOpen, setConnectOpen] = useState(false);

  const isConnected = (data?.content ?? []).some(
    (provider) => provider.provider === PROVIDER_TYPE.OPEN_ROUTER,
  );

  if (!isPending && !isConnected) {
    if (!canUpdateAIProviders) {
      return (
        <span className="comet-body-xs text-muted-slate">
          Ask an admin to connect OpenRouter
        </span>
      );
    }
    return (
      <>
        <Button
          type="button"
          variant="ghost"
          size="2xs"
          className="gap-1 px-0 hover:bg-transparent hover:text-primary-hover"
          onClick={() => setConnectOpen(true)}
        >
          <Plug className="size-3 shrink-0" />
          Connect OpenRouter
        </Button>
        {connectOpen && (
          <ManageAIProviderDialog
            open
            setOpen={setConnectOpen}
            configuredProvidersList={data?.content}
            defaultProvider={PROVIDER_TYPE.OPEN_ROUTER}
          />
        )}
      </>
    );
  }

  return (
    <SelectBox
      variant="ghost"
      value={form.watch("llmJudgeDetails.model")}
      onChange={(model: string) =>
        form.setValue("llmJudgeDetails.model", model, {
          shouldDirty: true,
        })
      }
      options={DECISION_MODELS}
      testId="decision-model-select"
      className="comet-body-xs h-6 w-auto gap-1 px-1 text-muted-slate"
    />
  );
};

type DecisionModelPromptFieldProps = {
  form: UseFormReturn<EvaluationRuleFormType>;
  onSelectTemplate: (template: LLMPromptTemplate, label: string) => void;
  datasetColumnNames?: string[];
};

const DecisionModelPromptField: React.FC<DecisionModelPromptFieldProps> = ({
  form,
  onSelectTemplate,
  datasetColumnNames,
}) => {
  const editorRef = useRef<PromptVariableEditorHandle>(null);
  const { showStartCards, startBlank, startWithTemplate } = useTemplateStart(
    form,
    editorRef,
    onSelectTemplate,
  );
  const scope = form.watch("scope");
  const decisionModelErrors = getDecisionModelErrors(form);
  const sampleData = useRuleSampleData(
    form.watch("projectIds")[0] ?? "",
    scope,
    true,
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
          label="Text to judge"
          options={DECISION_MODEL_TEMPLATE_OPTIONS}
          blankLabel="Write your own"
          blankDescription="Write the text to judge and your own questions."
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
          <RuleEditorCard
            hasError={
              Boolean(fieldState.error) || decisionModelErrors.length > 0
            }
            header={
              <>
                <DecisionModelSelect form={form} />
                <div className="ml-auto">
                  <JudgeTemplateMenu
                    options={DECISION_MODEL_TEMPLATE_OPTIONS}
                    onSelect={onSelectTemplate}
                    onAfterSelect={() => editorRef.current?.focusAtEnd()}
                  />
                </div>
              </>
            }
          >
            <JudgePromptInput
              value={field.value}
              onChange={field.onChange}
              onBlur={field.onBlur}
              sampleData={sampleData}
              editorRef={editorRef}
              showMedia={false}
            />
          </RuleEditorCard>
          <PromptVariableHint scope={scope} />
          <FormMessage />
          {decisionModelErrors.map((message) => (
            <FormErrorSkeleton key={message}>{message}</FormErrorSkeleton>
          ))}
        </FormItem>
      )}
    />
  );
};

export default DecisionModelPromptField;
