import React, {
  createContext,
  useContext,
  useState,
  useCallback,
  useMemo,
  useEffect,
} from "react";
import { useNavigate } from "@tanstack/react-router";
import { FormProvider, useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { OptimizationStudio, OPTIMIZATION_STATUS } from "@/types/optimizations";
import { Experiment } from "@/types/datasets";
import { OptimizationTemplate } from "@/constants/optimizations";
import useOptimizationCreateMutation from "@/api/optimizations/useOptimizationCreateMutation";
import useDatasetsList from "@/api/datasets/useDatasetsList";
import useAppStore from "@/store/AppStore";
import {
  OptimizationConfigFormType,
  OptimizationConfigSchema,
  convertFormDataToStudioConfig,
  convertOptimizationStudioToFormData,
} from "./ConfigureOptimizationSection/schema";
import { useLastOptimizationRun } from "@/lib/optimizationSessionStorage";

interface OptimizationStudioContextType {
  activeOptimization: OptimizationStudio | null;
  setActiveOptimization: (optimization: OptimizationStudio | null) => void;
  experiments: Experiment[];
  setExperiments: (experiments: Experiment[]) => void;
  templateData: OptimizationTemplate | null;
  setTemplateData: (template: OptimizationTemplate | null) => void;
  isSubmitting: boolean;
  submitOptimization: () => Promise<void>;
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
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const navigate = useNavigate();
  const createOptimizationMutation = useOptimizationCreateMutation();
  const { setLastSessionRunId } = useLastOptimizationRun();

  const { data: datasetsData } = useDatasetsList({
    workspaceName,
    page: 1,
    size: 1000,
  });

  const datasets = useMemo(
    () => datasetsData?.content || [],
    [datasetsData?.content],
  );

  const [activeOptimization, setActiveOptimization] =
    useState<OptimizationStudio | null>(null);
  const [experiments, setExperiments] = useState<Experiment[]>([]);
  const [templateData, setTemplateData] = useState<OptimizationTemplate | null>(
    null,
  );
  const [isSubmitting, setIsSubmitting] = useState(false);

  const defaultValues: OptimizationConfigFormType = useMemo(() => {
    return convertOptimizationStudioToFormData(
      activeOptimization || templateData || null,
    );
  }, [activeOptimization, templateData]);

  const form = useForm<OptimizationConfigFormType>({
    resolver: zodResolver(OptimizationConfigSchema),
    defaultValues,
  });

  // reset form when activeOptimization or templateData changes
  useEffect(() => {
    form.reset(
      convertOptimizationStudioToFormData(
        activeOptimization || templateData || null,
      ),
    );

    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeOptimization, templateData, form.reset]);

  const submitOptimization = useCallback(async () => {
    const isValid = await form.trigger();

    if (!isValid) {
      return;
    }

    const formData = form.getValues();
    const selectedDataset = datasets.find((ds) => ds.id === formData.datasetId);
    const datasetName = selectedDataset?.name || "";

    setIsSubmitting(true);

    try {
      const studioConfig = convertFormDataToStudioConfig(formData, datasetName);

      const optimizationPayload = {
        studio_config: studioConfig,
        dataset_name: datasetName,
        objective_name: studioConfig.evaluation.metrics[0].type,
        status: OPTIMIZATION_STATUS.INITIALIZED,
      };

      const result = await createOptimizationMutation.mutateAsync({
        optimization: optimizationPayload,
      });

      if (result?.id) {
        setLastSessionRunId(result.id);
        navigate({
          to: "/$workspaceName/optimization-studio/run",
          params: { workspaceName },
          search: { optimizationId: result.id },
        });
      }
    } finally {
      setIsSubmitting(false);
    }
  }, [form, datasets, createOptimizationMutation, navigate, workspaceName, setLastSessionRunId]);

  return (
    <OptimizationStudioContext.Provider
      value={{
        activeOptimization,
        setActiveOptimization,
        experiments,
        setExperiments,
        templateData,
        setTemplateData,
        isSubmitting,
        submitOptimization,
      }}
    >
      <FormProvider {...form}>{children}</FormProvider>
    </OptimizationStudioContext.Provider>
  );
};
