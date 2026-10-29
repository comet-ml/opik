import { useCallback } from "react";
import first from "lodash/first";

import useAppStore from "@/store/AppStore";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";
import useLastPickedModel from "@/hooks/useLastPickedModel";
import useProviderKeys from "@/api/provider-keys/useProviderKeys";
import { COMPOSED_PROVIDER_TYPE, PROVIDER_MODEL_TYPE } from "@/types/providers";
import { parseComposedProviderType } from "@/lib/provider";
import {
  RULE_DEFAULT_MODEL_BY_PROVIDER,
  RULE_LAST_PICKED_MODEL,
} from "@/v2/pages-shared/automations/RuleEditorPanel/constants";

export type RuleDefaultModel = {
  model: PROVIDER_MODEL_TYPE | "";
  provider: COMPOSED_PROVIDER_TYPE | "";
};

export const useCalculateRuleDefaultModel = () => {
  const { calculateModelProvider, calculateDefaultModel, isDropdownModel } =
    useLLMProviderModelsData();

  return useCallback(
    (
      lastPicked: PROVIDER_MODEL_TYPE | "",
      setupProviders: COMPOSED_PROVIDER_TYPE[],
      preferredProvider?: COMPOSED_PROVIDER_TYPE,
    ): PROVIDER_MODEL_TYPE | "" => {
      const lastPickedProvider = calculateModelProvider(
        lastPicked,
        preferredProvider,
      );
      if (
        setupProviders.includes(lastPickedProvider) &&
        isDropdownModel(lastPicked, lastPickedProvider)
      ) {
        return lastPicked;
      }

      const composedProvider = preferredProvider ?? first(setupProviders);
      if (!composedProvider) return "";
      return (
        RULE_DEFAULT_MODEL_BY_PROVIDER[
          parseComposedProviderType(composedProvider)
        ] ?? calculateDefaultModel("", setupProviders, composedProvider)
      );
    },
    [calculateModelProvider, calculateDefaultModel, isDropdownModel],
  );
};

const useRuleDefaultModel = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { calculateModelProvider, isFetched: isModelsFetched } =
    useLLMProviderModelsData();
  const calculateRuleDefaultModel = useCalculateRuleDefaultModel();
  const { data: providerKeysData, isPending: isProviderKeysPending } =
    useProviderKeys({ workspaceName });
  const [lastPickedModel] = useLastPickedModel({
    key: RULE_LAST_PICKED_MODEL,
  });

  const isReady = isModelsFetched && !isProviderKeysPending;
  const model = isReady
    ? calculateRuleDefaultModel(
        lastPickedModel,
        (providerKeysData?.content ?? []).map((p) => p.ui_composed_provider),
      )
    : "";
  const defaultModel: RuleDefaultModel = {
    model,
    provider: calculateModelProvider(model),
  };

  return { isReady, defaultModel };
};

export default useRuleDefaultModel;
