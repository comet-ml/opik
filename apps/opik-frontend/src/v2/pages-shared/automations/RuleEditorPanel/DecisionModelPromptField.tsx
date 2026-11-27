import React, { useState } from "react";
import { UseFormReturn } from "react-hook-form";
import get from "lodash/get";
import { Plug } from "lucide-react";

import { Button } from "@/ui/button";
import { usePermissions } from "@/contexts/PermissionsContext";
import {
  FormErrorSkeleton,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/ui/form";
import useAppStore from "@/store/AppStore";
import useProviderKeys from "@/api/provider-keys/useProviderKeys";
import useOpenRouterRegion from "@/api/provider-keys/useOpenRouterRegion";
import { PROVIDER_TYPE } from "@/types/providers";
import { PROVIDERS } from "@/constants/providers";
import {
  DECISION_MODELS,
  DECISIONS_API_MODELS,
} from "@/constants/decisionModels";
import SelectBox from "@/shared/SelectBox/SelectBox";
import { getDecisionModelUnavailableReason } from "@/lib/openRouterRegion";
import ManageAIProviderDialog from "@/v2/pages-shared/llm/ManageAIProviderDialog/ManageAIProviderDialog";
import { EvaluationRuleFormType } from "@/v2/pages-shared/automations/RuleEditorPanel/schema";
import { getRecordErrorKeys } from "@/v2/pages-shared/automations/RuleEditorPanel/helpers";
import PromptVariableHint from "@/v2/pages-shared/automations/RuleEditorPanel/PromptVariableHint";
import RuleEditorCard from "@/v2/pages-shared/automations/RuleEditorPanel/RuleEditorCard";
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
  return messages;
};

export const DecisionModelSelect: React.FC<{
  form: UseFormReturn<EvaluationRuleFormType>;
}> = ({ form }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { data, isPending } = useProviderKeys({ workspaceName });
  const { data: openRouterRegionInfo } = useOpenRouterRegion({
    workspaceName,
  });
  const unavailableDecisionModels =
    openRouterRegionInfo?.unavailable_decision_models;
  const {
    permissions: { canUpdateAIProviders },
  } = usePermissions();
  const [connectOpen, setConnectOpen] = useState(false);

  const model = form.watch("llmJudgeDetails.model");
  // DECISION_MODELS (Jev) are all OpenRouter's.
  const provider =
    DECISIONS_API_MODELS.find((decisionModel) => decisionModel.value === model)
      ?.provider ?? PROVIDER_TYPE.OPEN_ROUTER;
  const providerLabel = PROVIDERS[provider].label;
  const isConnected = (data?.content ?? []).some(
    (providerKey) => providerKey.provider === provider,
  );
  const unavailableReason = getDecisionModelUnavailableReason(
    model,
    unavailableDecisionModels,
  );
  const options = [
    ...DECISION_MODELS.map((decisionModel) => {
      const reason = getDecisionModelUnavailableReason(
        decisionModel.value,
        unavailableDecisionModels,
      );
      return reason
        ? { ...decisionModel, disabled: true, description: reason }
        : decisionModel;
    }),
    ...DECISIONS_API_MODELS,
  ];

  return (
    <>
      <SelectBox
        variant="ghost"
        value={model}
        onChange={(nextModel: string) =>
          form.setValue("llmJudgeDetails.model", nextModel, {
            shouldDirty: true,
          })
        }
        options={options}
        testId="decision-model-select"
        className="comet-body-xs h-6 w-auto gap-1 px-1 text-muted-slate"
      />
      {unavailableReason && (
        <span className="comet-body-xs text-destructive">
          {unavailableReason}
        </span>
      )}
      {!unavailableReason &&
        !isPending &&
        !isConnected &&
        (canUpdateAIProviders ? (
          <>
            <Button
              type="button"
              variant="ghost"
              size="2xs"
              className="gap-1 px-0 hover:bg-transparent hover:text-primary-hover"
              onClick={() => setConnectOpen(true)}
            >
              <Plug className="size-3 shrink-0" />
              <span>{`Connect ${providerLabel}`}</span>
            </Button>
            {connectOpen && (
              <ManageAIProviderDialog
                open
                setOpen={setConnectOpen}
                configuredProvidersList={data?.content}
                defaultProvider={provider}
              />
            )}
          </>
        ) : (
          <span className="comet-body-xs text-muted-slate">
            {`Ask an admin to connect ${providerLabel}`}
          </span>
        ))}
    </>
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
          <FormLabel className="px-0.5 pb-0.5">Text to judge</FormLabel>
          <RuleEditorCard
            hasError={
              Boolean(fieldState.error) || decisionModelErrors.length > 0
            }
            header={<DecisionModelSelect form={form} />}
          >
            <JudgePromptInput
              value={field.value}
              onChange={field.onChange}
              onBlur={field.onBlur}
              sampleData={sampleData}
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
