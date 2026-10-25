import { useCallback } from "react";
import useAppStore from "@/store/AppStore";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";
import useLastPickedModel from "@/hooks/useLastPickedModel";
import useProviderKeys from "@/api/provider-keys/useProviderKeys";
import { COMPOSED_PROVIDER_TYPE, PROVIDER_MODEL_TYPE } from "@/types/providers";
import { RULE_LAST_PICKED_MODEL } from "@/v2/pages-shared/automations/RuleEditorPanel/constants";

export type RuleDefaultModel = {
  model: PROVIDER_MODEL_TYPE | "";
  provider: COMPOSED_PROVIDER_TYPE | "";
};

// Rules start where the playground does: the same last-picked rule, then the catalog's default model. A
// remembered judge model the picker no longer lists is skipped.
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
      const stillListed = isDropdownModel(lastPicked, lastPickedProvider);
      return calculateDefaultModel(
        stillListed ? lastPicked : "",
        setupProviders,
        preferredProvider,
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
