import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate } from "@tanstack/react-router";
import { useFormContext } from "react-hook-form";

import useAppStore from "@/store/AppStore";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import useDatasetsList from "@/api/datasets/useDatasetsList";
import useOptimizationCreateMutation from "@/api/optimizations/useOptimizationCreateMutation";
import { OptimizationConfigFormType } from "@/components/pages-shared/optimizations/OptimizationConfigForm/schema";
import { convertFormDataToStudioConfig } from "@/components/pages-shared/optimizations/OptimizationConfigForm/schema";
import {
  OPTIMIZATION_STATUS,
  OPTIMIZER_TYPE,
  METRIC_TYPE,
  OptimizerParameters,
  MetricParameters,
} from "@/types/optimizations";
import { PROVIDER_MODEL_TYPE, LLMPromptConfigsType } from "@/types/providers";
import { useLastOptimizationRun } from "@/lib/optimizationSessionStorage";
import {
  getDefaultOptimizerConfig,
  getDefaultMetricConfig,
  getOptimizationDefaultConfigByProvider,
} from "@/lib/optimizations";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";
import useDatasetSamplePreview from "./useDatasetSamplePreview";

const getBreadcrumbTitle = (name: string) =>
  name?.trim() ? `${name} (new)` : "... (new)";

export const useOptimizationsNewFormHandlers = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const navigate = useNavigate();
  const { setLastSessionRunId } = useLastOptimizationRun();
  const { mutateAsync: createOptimization } = useOptimizationCreateMutation();
  const [isSubmitting, setIsSubmitting] = useState(false);
  const setBreadcrumbParam = useBreadcrumbsStore((state) => state.setParam);

  const form = useFormContext<OptimizationConfigFormType>();

  const { data: datasetsData } = useDatasetsList({
    workspaceName,
    page: 1,
    size: 1000,
  });

  const datasets = useMemo(
    () => datasetsData?.content || [],
    [datasetsData?.content],
  );

  const datasetId = form.watch("datasetId");
  const optimizerType = form.watch("optimizerType");
  const metricType = form.watch("metricType");
  const model = form.watch("modelName") as PROVIDER_MODEL_TYPE | "";
  const config = form.watch("modelConfig");

  const { datasetSample, datasetVariables } = useDatasetSamplePreview({
    datasetId,
  });

  const { calculateModelProvider } = useLLMProviderModelsData();

  const handleDatasetChange = useCallback(
    (id: string | null) => {
      form.setValue("datasetId", id || "", { shouldValidate: true });

      const currentMetricType = form.getValues("metricType");
      const currentMetricParams = form.getValues("metricParams");

      const metricsWithReferenceKey = [
        METRIC_TYPE.EQUALS,
        METRIC_TYPE.JSON_SCHEMA_VALIDATOR,
        METRIC_TYPE.LEVENSHTEIN,
      ];

      if (metricsWithReferenceKey.includes(currentMetricType)) {
        form.setValue(
          "metricParams",
          {
            ...currentMetricParams,
            reference_key: "",
          } as OptimizationConfigFormType["metricParams"],
          { shouldValidate: true },
        );
      }
    },
    [form],
  );

  const handleOptimizerTypeChange = useCallback(
    (newOptimizerType: OPTIMIZER_TYPE) => {
      form.setValue("optimizerType", newOptimizerType, {
        shouldValidate: true,
      });

      const defaultConfig = getDefaultOptimizerConfig(newOptimizerType);
      form.setValue("optimizerParams", defaultConfig, {
        shouldValidate: true,
      });
    },
    [form],
  );

  const handleOptimizerParamsChange = useCallback(
    (newParams: Partial<OptimizerParameters>) => {
      form.setValue("optimizerParams", newParams);
    },
    [form],
  );

  const handleMetricTypeChange = useCallback(
    (newMetricType: METRIC_TYPE) => {
      const defaultConfig = getDefaultMetricConfig(newMetricType);
      form.setValue("metricType", newMetricType);
      form.setValue(
        "metricParams",
        defaultConfig as OptimizationConfigFormType["metricParams"],
        { shouldValidate: true },
      );
    },
    [form],
  );

  const handleMetricParamsChange = useCallback(
    (newParams: Partial<MetricParameters>) => {
      form.setValue(
        "metricParams",
        newParams as OptimizationConfigFormType["metricParams"],
        { shouldValidate: true },
      );
    },
    [form],
  );

  const getFirstMetricParamsError = useCallback(() => {
    const errors = form.formState.errors.metricParams;
    if (!errors) return null;
    if (errors.message) return errors.message;

    const firstKey = Object.keys(errors)[0];
    const firstError = errors[firstKey as keyof typeof errors];

    if (
      firstError &&
      typeof firstError === "object" &&
      "message" in firstError
    ) {
      return firstError.message as string;
    }

    return null;
  }, [form.formState.errors.metricParams]);

  const handleModelConfigChange = useCallback(
    (newConfigs: Partial<LLMPromptConfigsType>) => {
      const currentConfig = form.getValues("modelConfig");
      form.setValue("modelConfig", {
        ...currentConfig,
        ...newConfigs,
      } as typeof currentConfig);
    },
    [form],
  );

  const handleModelChange = useCallback(
    (newModel: PROVIDER_MODEL_TYPE) => {
      const newProvider = calculateModelProvider(newModel);
      const defaultConfig = getOptimizationDefaultConfigByProvider(
        newProvider,
        newModel,
      );

      form.setValue("modelName", newModel);
      form.setValue(
        "modelConfig",
        defaultConfig as OptimizationConfigFormType["modelConfig"],
      );
    },
    [form, calculateModelProvider],
  );

  const handleSubmit = useCallback(async () => {
    const isValid = await form.trigger();
    if (!isValid) return;

    const formData = form.getValues();
    const selectedDs = datasets.find((ds) => ds.id === formData.datasetId);
    const datasetNameValue = selectedDs?.name || "";

    if (!datasetNameValue) return;

    setIsSubmitting(true);

    try {
      const studioConfig = convertFormDataToStudioConfig(
        formData,
        datasetNameValue,
      );

      const result = await createOptimization({
        optimization: {
          name: formData.name || undefined,
          studio_config: studioConfig,
          dataset_name: datasetNameValue,
          objective_name: studioConfig.evaluation.metrics[0].type,
          status: OPTIMIZATION_STATUS.INITIALIZED,
        },
      });

      if (result?.id) {
        setLastSessionRunId(result.id);
        navigate({
          to: "/$workspaceName/optimizations/$datasetId/compare",
          params: {
            workspaceName,
            datasetId: formData.datasetId,
          },
          search: { optimizations: [result.id] },
        });
      }
    } finally {
      setIsSubmitting(false);
    }
  }, [
    form,
    datasets,
    createOptimization,
    navigate,
    workspaceName,
    setLastSessionRunId,
  ]);

  const handleCancel = useCallback(() => {
    navigate({
      to: "/$workspaceName/optimizations",
      params: { workspaceName },
    });
  }, [navigate, workspaceName]);

  const handleNameChange = useCallback(
    (value: string) => {
      form.setValue("name", value);
      setBreadcrumbParam("optimizationsNew", "new", getBreadcrumbTitle(value));
    },
    [form, setBreadcrumbParam],
  );

  useEffect(() => {
    const initialName = form.getValues("name") ?? "";
    setBreadcrumbParam(
      "optimizationsNew",
      "new",
      getBreadcrumbTitle(initialName),
    );

    return () => setBreadcrumbParam("optimizationsNew", "new", "");
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return {
    form,
    workspaceName,
    isSubmitting,
    datasetId,
    optimizerType,
    metricType,
    model,
    config,
    datasetSample,
    datasetVariables,
    handleDatasetChange,
    handleOptimizerTypeChange,
    handleOptimizerParamsChange,
    handleMetricTypeChange,
    handleMetricParamsChange,
    handleModelConfigChange,
    handleModelChange,
    handleSubmit,
    handleCancel,
    handleNameChange,
    getFirstMetricParamsError,
  };
};
