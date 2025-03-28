import { createContext, useContext, useEffect, useMemo, useState } from "react";
import { FeatureToggle } from "@/types/feature-toggle";
import { OnChangeFn } from "@/types/shared";
import useFeatureToggle from "@/api/feature-toggle/useFeatureToggle";

type FeatureTogglesProps = {
  children: React.ReactNode;
};

type FeatureTogglesState = {
  features: FeatureToggle;
  setFeatures: OnChangeFn<FeatureToggle>;
  isFeatureEnabled: (feature: keyof FeatureToggle) => boolean;
};

const DEFAULT_STATE: FeatureToggle = {
  TOGGLE_PYTHON_EVALUATOR_ENABLED: "false",
};

const initialState: FeatureTogglesState = {
  features: DEFAULT_STATE,
  setFeatures: () => undefined,
  isFeatureEnabled: () => false,
};

const FeatureTogglesProviderContext =
  createContext<FeatureTogglesState>(initialState);

export function FeatureTogglesProvider({ children }: FeatureTogglesProps) {
  const [features, setFeatures] = useState<FeatureToggle>(DEFAULT_STATE);
  const { data } = useFeatureToggle();

  useEffect(() => {
    if (data) setFeatures(data);
  }, [data]);

  const value = useMemo(() => {
    return {
      features,
      setFeatures,
      isFeatureEnabled: (feature: keyof FeatureToggle) =>
        features[feature] === "true",
    };
  }, [features]);

  return (
    <FeatureTogglesProviderContext.Provider value={value}>
      {children}
    </FeatureTogglesProviderContext.Provider>
  );
}

export const useIsFeatureEnabled = (feature: keyof FeatureToggle) => {
  const context = useContext(FeatureTogglesProviderContext);

  if (context === undefined)
    throw new Error(
      "useIsFeatureEnabled must be used within a FeatureTogglesProvider",
    );

  return context.isFeatureEnabled(feature);
};
