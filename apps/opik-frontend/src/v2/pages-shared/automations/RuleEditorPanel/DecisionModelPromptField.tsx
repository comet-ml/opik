import React, { useState } from "react";
import { UseFormReturn } from "react-hook-form";
import get from "lodash/get";

import { Button } from "@/ui/button";
import { Description } from "@/ui/description";
import { FormErrorSkeleton, FormField, FormItem, FormMessage } from "@/ui/form";
import { Tag } from "@/ui/tag";
import SelectBox from "@/shared/SelectBox/SelectBox";
import useAppStore from "@/store/AppStore";
import useProviderKeys from "@/api/provider-keys/useProviderKeys";
import { usePermissions } from "@/contexts/PermissionsContext";
import { PROVIDER_TYPE } from "@/types/providers";
import { DECISION_MODELS } from "@/constants/decisionModels";
import ManageAIProviderDialog from "@/v2/pages-shared/llm/ManageAIProviderDialog/ManageAIProviderDialog";
import { EvaluationRuleFormType } from "@/v2/pages-shared/automations/RuleEditorPanel/schema";
import { getRecordErrorKeys } from "@/v2/pages-shared/automations/RuleEditorPanel/helpers";
import { SCOPE_ENTITY_LABEL } from "@/v2/pages-shared/automations/RuleEditorPanel/constants";
import RuleEditorCard from "@/v2/pages-shared/automations/RuleEditorPanel/RuleEditorCard";
import RuleEditorNotice from "@/v2/pages-shared/automations/RuleEditorPanel/RuleEditorNotice";
import JudgePromptInput from "@/v2/pages-shared/automations/RuleEditorPanel/JudgePromptInput";
import useRuleSampleData from "@/v2/pages-shared/automations/RuleEditorPanel/useRuleSampleData";

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
  if (form.getValues("llmJudgeDetails.parsingVariablesError")) {
    messages.push("The text has a variable that is not written correctly.");
  }
  return messages;
};

const OpenRouterNotice: React.FC = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { data, isPending } = useProviderKeys({ workspaceName });
  const {
    permissions: { canUpdateAIProviders },
  } = usePermissions();
  const [open, setOpen] = useState(false);

  const isConnected = (data?.content ?? []).some(
    (provider) => provider.provider === PROVIDER_TYPE.OPEN_ROUTER,
  );
  if (isPending || isConnected) return null;

  return (
    <RuleEditorNotice>
      <span>
        Decision models run through OpenRouter. Connect OpenRouter so that this
        evaluator can run.
      </span>
      {canUpdateAIProviders && (
        <>
          <Button
            type="button"
            variant="outline"
            size="2xs"
            className="self-start"
            onClick={() => setOpen(true)}
          >
            Connect OpenRouter
          </Button>
          <ManageAIProviderDialog
            open={open}
            setOpen={setOpen}
            configuredProvidersList={data?.content}
          />
        </>
      )}
    </RuleEditorNotice>
  );
};

type DecisionModelPromptFieldProps = {
  form: UseFormReturn<EvaluationRuleFormType>;
  datasetColumnNames?: string[];
};

const DecisionModelPromptField: React.FC<DecisionModelPromptFieldProps> = ({
  form,
  datasetColumnNames,
}) => {
  const scope = form.watch("scope");
  const decisionModelErrors = getDecisionModelErrors(form);
  const sampleData = useRuleSampleData(
    form.watch("projectIds")[0] ?? "",
    scope,
    true,
    datasetColumnNames,
  );

  return (
    <FormField
      control={form.control}
      name="llmJudgeDetails.messages.0.content"
      render={({ field, fieldState }) => (
        <FormItem className="flex flex-1 flex-col gap-1">
          <OpenRouterNotice />
          <RuleEditorCard
            hasError={
              Boolean(fieldState.error) || decisionModelErrors.length > 0
            }
            header={
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
            }
          >
            <JudgePromptInput
              value={field.value}
              onChange={field.onChange}
              onBlur={field.onBlur}
              sampleData={sampleData}
              showMedia={false}
            />
          </RuleEditorCard>
          <Description className="px-0.5">
            <span>
              The decision model reads this text and answers each question with
              yes or no. A score is true when the probability is 0.5 or higher,
              and the reason shows the probability. Use
            </span>{" "}
            <Tag variant="green" className="px-1 align-top font-mono">
              {"{{"}
            </Tag>{" "}
            <span>{`to insert data from ${SCOPE_ENTITY_LABEL[scope]}.`}</span>
          </Description>
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
