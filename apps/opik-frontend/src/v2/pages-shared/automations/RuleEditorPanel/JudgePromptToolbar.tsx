import React, { useCallback } from "react";
import { UseFormReturn } from "react-hook-form";
import get from "lodash/get";

import { Separator } from "@/ui/separator";
import useAppStore from "@/store/AppStore";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";
import useLastPickedModel from "@/hooks/useLastPickedModel";
import useOpenAiPipelineMode from "@/hooks/useOpenAiPipelineMode";
import { COMPOSED_PROVIDER_TYPE, PROVIDER_MODEL_TYPE } from "@/types/providers";
import PromptModelSelect from "@/v2/pages-shared/llm/PromptModelSelect/PromptModelSelect";
import PromptModelConfigs from "@/v2/pages-shared/llm/PromptModelSettings/PromptModelConfigs";
import { LLMPromptTemplate } from "@/types/llm";
import JudgeTemplateMenu from "@/v2/pages-shared/automations/RuleEditorPanel/JudgeTemplateMenu";
import { getJudgeTemplateOptions } from "@/v2/pages-shared/automations/RuleEditorPanel/judgeTemplates";
import { RULE_UNSUPPORTED_PARAMS } from "@/v2/pages-shared/llm/PromptModelSettings/modelConfigParams";
import { hasVisibleControls } from "@/v2/pages-shared/llm/PromptModelSettings/providerConfigs/visibleControls";
import { parseComposedProviderType } from "@/lib/provider";
import {
  EvaluationRuleFormType,
  updateConfigForModelChange,
} from "@/v2/pages-shared/automations/RuleEditorPanel/schema";
import { RULE_LAST_PICKED_MODEL } from "@/v2/pages-shared/automations/RuleEditorPanel/constants";
import { useCalculateRuleDefaultModel } from "@/v2/pages-shared/automations/RuleEditorPanel/useRuleDefaultModel";

type JudgePromptToolbarProps = {
  form: UseFormReturn<EvaluationRuleFormType>;
  onSelectTemplate?: (template: LLMPromptTemplate, label: string) => void;
  onAfterSelectTemplate?: () => void;
};

const JudgePromptToolbar: React.FC<JudgePromptToolbarProps> = ({
  form,
  onSelectTemplate,
  onAfterSelectTemplate,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const openAiPipelineMode = useOpenAiPipelineMode(workspaceName);
  const { calculateModelProvider } = useLLMProviderModelsData();
  const calculateRuleDefaultModel = useCalculateRuleDefaultModel();
  const [, setLastPickedModel] = useLastPickedModel({
    key: RULE_LAST_PICKED_MODEL,
  });

  const model = form.watch("llmJudgeDetails.model") as PROVIDER_MODEL_TYPE | "";
  const config = form.watch("llmJudgeDetails.config");
  const provider = calculateModelProvider(model);
  const showSettings =
    Boolean(model && provider) &&
    hasVisibleControls(
      parseComposedProviderType(provider),
      model,
      config,
      RULE_UNSUPPORTED_PARAMS,
      openAiPipelineMode,
    );
  const hasModelError = Boolean(
    get(form.formState.errors, ["llmJudgeDetails", "model", "message"]),
  );

  const applyModel = useCallback(
    (
      nextModel: PROVIDER_MODEL_TYPE,
      selectedProvider?: COMPOSED_PROVIDER_TYPE,
    ) => {
      const previousModel = form.getValues("llmJudgeDetails.model");
      form.setValue("llmJudgeDetails.model", nextModel, { shouldDirty: true });

      const currentConfig = form.getValues("llmJudgeDetails.config");
      const adjustedConfig = updateConfigForModelChange(
        currentConfig,
        {
          model: previousModel,
          provider: calculateModelProvider(
            previousModel as PROVIDER_MODEL_TYPE | "",
          ),
        },
        {
          model: nextModel,
          provider:
            calculateModelProvider(nextModel, selectedProvider) ||
            selectedProvider ||
            "",
        },
        openAiPipelineMode,
      );
      if (adjustedConfig !== currentConfig) {
        form.setValue("llmJudgeDetails.config", adjustedConfig);
      }
    },
    [form, calculateModelProvider, openAiPipelineMode],
  );

  const handleModelChange = useCallback(
    (
      nextModel: PROVIDER_MODEL_TYPE,
      selectedProvider: COMPOSED_PROVIDER_TYPE,
    ) => {
      if (!nextModel) return;
      applyModel(nextModel, selectedProvider);
      setLastPickedModel(nextModel);
    },
    [applyModel, setLastPickedModel],
  );

  const handleAddProvider = useCallback(
    (addedProvider: COMPOSED_PROVIDER_TYPE) => {
      if (form.getValues("llmJudgeDetails.model")) return;
      const nextModel = calculateRuleDefaultModel(
        "",
        [addedProvider],
        addedProvider,
      );
      if (!nextModel) return;
      applyModel(nextModel, addedProvider);
      setLastPickedModel(nextModel);
    },
    [form, calculateRuleDefaultModel, applyModel, setLastPickedModel],
  );

  const handleDeleteProvider = useCallback(
    (deletedProvider: COMPOSED_PROVIDER_TYPE) => {
      const currentModel = form.getValues(
        "llmJudgeDetails.model",
      ) as PROVIDER_MODEL_TYPE;
      if (
        calculateModelProvider(currentModel, deletedProvider) !==
        deletedProvider
      )
        return;

      form.setValue("llmJudgeDetails.model", "");
      const currentConfig = form.getValues("llmJudgeDetails.config");
      if (currentConfig.custom_parameters != null) {
        form.setValue("llmJudgeDetails.config", {
          ...currentConfig,
          custom_parameters: null,
        });
      }
    },
    [form, calculateModelProvider],
  );

  return (
    <>
      <PromptModelSelect
        compact
        value={model}
        onChange={handleModelChange}
        provider={provider}
        workspaceName={workspaceName}
        onAddProvider={handleAddProvider}
        onDeleteProvider={handleDeleteProvider}
        hasError={hasModelError}
      />
      {showSettings && (
        <>
          <Separator orientation="vertical" className="ml-2 mr-0.5 h-3" />
          <PromptModelConfigs
            size="icon-2xs"
            variant="ghost"
            provider={provider}
            model={model}
            configs={config}
            unsupportedParams={RULE_UNSUPPORTED_PARAMS}
            openAiPipelineMode={openAiPipelineMode}
            onChange={(partialConfig) =>
              form.setValue("llmJudgeDetails.config", {
                ...form.getValues("llmJudgeDetails.config"),
                ...partialConfig,
              })
            }
          />
        </>
      )}
      {onSelectTemplate && (
        <div className="ml-auto">
          <JudgeTemplateMenu
            options={getJudgeTemplateOptions(form.watch("scope"))}
            onSelect={onSelectTemplate}
            onAfterSelect={onAfterSelectTemplate}
          />
        </div>
      )}
    </>
  );
};

export default JudgePromptToolbar;
