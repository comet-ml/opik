import { createContext, useContext, useEffect, useMemo, useState } from "react";
import { FeatureToggles } from "@/types/feature-toggles";
import { OnChangeFn } from "@/types/shared";
import useFeatureToggle from "@/api/feature-toggle/useFeatureToggle";
import useAppStore from "@/store/AppStore";

type FeatureTogglesProps = {
  children: React.ReactNode;
};

type FeatureTogglesState = {
  features: FeatureToggles;
  setFeatures: OnChangeFn<FeatureToggles>;
  isFeatureEnabled: (feature: keyof FeatureToggles) => boolean;
};

const DEFAULT_STATE: FeatureToggles = {
  python_evaluator_enabled: false,
  guardrails_enabled: false,
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
    if (data) setFeatures(data);
  }, [data]);

  const value = useMemo(() => {
    return {
      features,
      setFeatures,
      isFeatureEnabled: (feature: keyof FeatureToggles) => features[feature],
    };
  }, [features]);

  return (
    <FeatureTogglesProviderContext.Provider value={value}>
      {children}
    </FeatureTogglesProviderContext.Provider>
  );
}

export const useIsFeatureEnabled = (feature: keyof FeatureToggles) => {
  const context = useContext(FeatureTogglesProviderContext);

  if (context === undefined)
    throw new Error(
      "useIsFeatureEnabled must be used within a FeatureTogglesProvider",
    );

  return context.isFeatureEnabled(feature);
};
