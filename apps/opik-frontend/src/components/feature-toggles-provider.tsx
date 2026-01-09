import { createContext, useContext, useEffect, useMemo, useState } from "react";
import { FeatureToggleKeys, FeatureToggles } from "@/types/feature-toggles";
import { OnChangeFn } from "@/types/shared";
import useFeatureToggle from "@/api/feature-toggle/useFeatureToggle";
import useAppStore from "@/store/AppStore";

type FeatureTogglesProps = {
  children: React.ReactNode;
};

type FeatureTogglesState = {
  features: FeatureToggles;
  setFeatures: OnChangeFn<FeatureToggles>;
  isFeatureEnabled: (feature: FeatureToggleKeys) => boolean;
};

const DEFAULT_STATE: FeatureToggles = {
  [FeatureToggleKeys.PYTHON_EVALUATOR_ENABLED]: false,
  [FeatureToggleKeys.GUARDRAILS_ENABLED]: false,
  [FeatureToggleKeys.TOGGLE_OPIK_AI_ENABLED]: false,
  [FeatureToggleKeys.TOGGLE_ALERTS_ENABLED]: false,
  [FeatureToggleKeys.WELCOME_WIZARD_ENABLED]: false,
  [FeatureToggleKeys.CSV_UPLOAD_ENABLED]: false,
  [FeatureToggleKeys.EXPORT_ENABLED]: true,
  [FeatureToggleKeys.DATASET_VERSIONING_ENABLED]: false,
  [FeatureToggleKeys.OPTIMIZATION_STUDIO_ENABLED]: false,
  [FeatureToggleKeys.SPAN_LLM_AS_JUDGE_ENABLED]: false,
  [FeatureToggleKeys.SPAN_USER_DEFINED_METRIC_PYTHON_ENABLED]: false,
  // LLM Provider feature flags - default false
  [FeatureToggleKeys.OPENAI_PROVIDER_ENABLED]: false,
  [FeatureToggleKeys.ANTHROPIC_PROVIDER_ENABLED]: false,
  [FeatureToggleKeys.GEMINI_PROVIDER_ENABLED]: false,
  [FeatureToggleKeys.OPENROUTER_PROVIDER_ENABLED]: false,
  [FeatureToggleKeys.VERTEXAI_PROVIDER_ENABLED]: false,
  [FeatureToggleKeys.BEDROCK_PROVIDER_ENABLED]: false,
  [FeatureToggleKeys.CUSTOMLLM_PROVIDER_ENABLED]: false,
  [FeatureToggleKeys.COLLABORATORS_TAB_ENABLED]: false,
};

const initialState: FeatureTogglesState = {
  features: DEFAULT_STATE,
  setFeatures: () => undefined,
  isFeatureEnabled: () => false,
};

const FeatureTogglesProviderContext =
  createContext<FeatureTogglesState>(initialState);

export function FeatureTogglesProvider({ children }: FeatureTogglesProps) {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [features, setFeatures] = useState<FeatureToggles>(DEFAULT_STATE);
  const { data } = useFeatureToggle({
    workspaceName,
  });

  useEffect(() => {
    if (data) {
      setFeatures(data);
    }
  }, [data]);

  const value = useMemo(() => {
    return {
      features,
      setFeatures,
      isFeatureEnabled: (feature: FeatureToggleKeys) => features[feature],
    };
  }, [features]);

  return (
    <FeatureTogglesProviderContext.Provider value={value}>
      {children}
    </FeatureTogglesProviderContext.Provider>
  );
}

export const useIsFeatureEnabled = (feature: FeatureToggleKeys) => {
  const context = useContext(FeatureTogglesProviderContext);

  if (context === undefined)
    throw new Error(
      "useIsFeatureEnabled must be used within a FeatureTogglesProvider",
    );

  return context.isFeatureEnabled(feature);
};
