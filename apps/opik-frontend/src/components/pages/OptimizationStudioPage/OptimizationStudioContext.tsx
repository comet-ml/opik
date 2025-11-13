import React, { createContext, useContext, useState } from "react";
import { Optimization } from "@/types/optimizations";
import { Experiment } from "@/types/datasets";
import { OptimizationTemplate } from "@/constants/optimizations";

interface OptimizationStudioContextType {
  activeOptimization: Optimization | null;
  setActiveOptimization: (optimization: Optimization | null) => void;
  experiments: Experiment[];
  setExperiments: (experiments: Experiment[]) => void;
  templateData: OptimizationTemplate | null;
  setTemplateData: (template: OptimizationTemplate | null) => void;
}

const OptimizationStudioContext = createContext<
  OptimizationStudioContextType | undefined
>(undefined);

export const useOptimizationStudioContext = () => {
  const context = useContext(OptimizationStudioContext);
  if (context === undefined) {
    throw new Error(
      "useOptimizationStudioContext must be used within an OptimizationStudioProvider",
    );
  }
  return context;
};

interface OptimizationStudioProviderProps {
  children: React.ReactNode;
}

export const OptimizationStudioProvider: React.FC<
  OptimizationStudioProviderProps
> = ({ children }) => {
  const [activeOptimization, setActiveOptimization] =
    useState<Optimization | null>(null);
  const [experiments, setExperiments] = useState<Experiment[]>([]);
  const [templateData, setTemplateData] = useState<OptimizationTemplate | null>(
    null,
  );

  return (
    <OptimizationStudioContext.Provider
      value={{
        activeOptimization,
        setActiveOptimization,
        experiments,
        setExperiments,
        templateData,
        setTemplateData,
      }}
    >
      {children}
    </OptimizationStudioContext.Provider>
  );
};
