import { createContext, useContext, useEffect, useMemo, useState } from "react";
import { FeatureToggleKeys, FeatureToggles } from "@/types/feature-toggles";
import { OnChangeFn } from "@/types/shared";
import useFeatureToggle from "@/api/feature-toggle/useFeatureToggle";
import useAppStore from "@/store/AppStore";

export const DEFAULT_PAGE_SIZE_FALLBACK = 100;

type FeatureTogglesProps = {
  children: React.ReactNode;
};

type FeatureTogglesState = {
  features: FeatureToggles;
  setFeatures: OnChangeFn<FeatureToggles>;
  isFeatureEnabled: (feature: FeatureToggleKeys) => boolean;
  defaultPageSize: number;
};

const DEFAULT_STATE: FeatureToggles = {
  [FeatureToggleKeys.PYTHON_EVALUATOR_ENABLED]: false,
  [FeatureToggleKeys.GUARDRAILS_ENABLED]: false,
  [FeatureToggleKeys.TOGGLE_OPIK_AI_ENABLED]: false,
  [FeatureToggleKeys.TOGGLE_ALERTS_ENABLED]: false,
  [FeatureToggleKeys.WELCOME_WIZARD_ENABLED]: false,
  [FeatureToggleKeys.CSV_UPLOAD_ENABLED]: false,
  [FeatureToggleKeys.EXPORT_ENABLED]: true,
  [FeatureToggleKeys.DATASET_EXPORT_ENABLED]: true,
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
  [FeatureToggleKeys.OLLAMA_PROVIDER_ENABLED]: false,
  [FeatureToggleKeys.COLLABORATORS_TAB_ENABLED]: false,
  [FeatureToggleKeys.TOGGLE_RUNNERS_ENABLED]: false,
};

const initialState: FeatureTogglesState = {
  features: DEFAULT_STATE,
  setFeatures: () => undefined,
  isFeatureEnabled: () => false,
  defaultPageSize: DEFAULT_PAGE_SIZE_FALLBACK,
};

const FeatureTogglesProviderContext =
  createContext<FeatureTogglesState>(initialState);

export function FeatureTogglesProvider({ children }: FeatureTogglesProps) {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [features, setFeatures] = useState<FeatureToggles>(DEFAULT_STATE);
  const [defaultPageSize, setDefaultPageSize] = useState<number>(
    DEFAULT_PAGE_SIZE_FALLBACK,
  );
  const { data } = useFeatureToggle({
    workspaceName,
  });

  useEffect(() => {
    if (data) {
      setFeatures(data);
      // Mirrors ServiceTogglesConfig bounds (5..100). Out-of-range or
      // malformed values fall through to the fallback so a misconfigured
      // deployment can't poison the UI.
      const raw = data.default_page_size;
      if (
        typeof raw === "number" &&
        Number.isInteger(raw) &&
        raw >= 5 &&
        raw <= 100
      ) {
        setDefaultPageSize(raw);
      }
    }
  }, [data]);

  const value = useMemo(() => {
    return {
      features,
      setFeatures,
      isFeatureEnabled: (feature: FeatureToggleKeys) => features[feature],
      defaultPageSize,
    };
  }, [features, defaultPageSize]);

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

export const useDefaultPageSize = () => {
  const context = useContext(FeatureTogglesProviderContext);

  if (context === undefined)
    throw new Error(
      "useDefaultPageSize must be used within a FeatureTogglesProvider",
    );

  return context.defaultPageSize;
};
