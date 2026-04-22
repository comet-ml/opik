import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "@tanstack/react-router";
import { useFormContext } from "react-hook-form";

import useAppStore, { useActiveProjectId } from "@/store/AppStore";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import useProjectDatasetsList from "@/api/datasets/useProjectDatasetsList";
import useOptimizationCreateMutation from "@/api/optimizations/useOptimizationCreateMutation";
import { OptimizationConfigFormType } from "@/v2/pages-shared/optimizations/OptimizationConfigForm/schema";
import { convertFormDataToStudioConfig } from "@/v2/pages-shared/optimizations/OptimizationConfigForm/schema";
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
  extractMetricNameFromCode,
} from "@/lib/optimizations";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";
import useDatasetSamplePreview from "./useDatasetSamplePreview";
import { BlueprintPromptRef } from "@/types/playground";
import usePromptByCommit from "@/api/prompts/usePromptByCommit";
import useSavePromptToBlueprint from "@/v2/pages-shared/llm/BlueprintPromptsSelectBox/useSavePromptToBlueprint";
import {
  serializeChatTemplate,
  chatTemplatesEqual,
  parseChatTemplateToMessages,
} from "@/lib/chatTemplate";

const getBreadcrumbTitle = (name: string) =>
  name?.trim() ? `${name} (new)` : "... (new)";

export const useOptimizationsNewFormHandlers = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const activeProjectId = useActiveProjectId();
  const navigate = useNavigate();
  const { setLastSessionRunId } = useLastOptimizationRun();
  const { mutateAsync: createOptimization } = useOptimizationCreateMutation();
  const [isSubmitting, setIsSubmitting] = useState(false);
  const setBreadcrumbParam = useBreadcrumbsStore((state) => state.setParam);

  const form = useFormContext<OptimizationConfigFormType>();

  const { data: datasetsData } = useProjectDatasetsList(
    {
      projectId: activeProjectId!,
      page: 1,
      size: 1000,
    },
    {
      enabled: !!activeProjectId,
    },
  );

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

  // Blueprint prompt integration
  const [blueprintRef, setBlueprintRef] = useState<
    BlueprintPromptRef | undefined
  >();
  const loadedCommitRef = useRef<string | null>(null);

  const { data: commitPromptData } = usePromptByCommit(
    { commitId: blueprintRef?.commitId ?? "" },
    { enabled: !!blueprintRef?.commitId },
  );

  // Populate form messages when a blueprint prompt is loaded
  useEffect(() => {
    if (!commitPromptData || !blueprintRef) return;
    const commitId = blueprintRef.commitId;
    if (loadedCommitRef.current === commitId) return;

    const template = commitPromptData.requested_version?.template;
    if (!template) return;

    try {
      const messages = parseChatTemplateToMessages(template);
      form.setValue("messages", messages, { shouldValidate: true });
      loadedCommitRef.current = commitId;
    } catch {
      // ignore parse failures
    }
  }, [commitPromptData, blueprintRef, form]);

  const handleBlueprintRefClear = useCallback(() => {
    setBlueprintRef(undefined);
    loadedCommitRef.current = null;
  }, []);

  const messages = form.watch("messages");
  const hasUnsavedBlueprintChanges = useMemo(() => {
    const loadedTemplate = commitPromptData?.requested_version?.template;
    if (!blueprintRef || !loadedTemplate || messages.length === 0) return false;
    return !chatTemplatesEqual(serializeChatTemplate(messages), loadedTemplate);
  }, [blueprintRef, commitPromptData, messages]);

  const {
    existingFieldNames: blueprintFieldNames,
    saveExistingVersion: saveBlueprintExisting,
    saveAsNewField: saveBlueprintNewField,
    isSaving: isSavingBlueprint,
  } = useSavePromptToBlueprint(activeProjectId!);

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

      // For code metrics, extract the class name from the code
      // For other metrics, use the metric type
      const metricConfig = studioConfig.evaluation.metrics[0];
      let objectiveName: string = metricConfig.type;
      if (
        metricConfig.type === METRIC_TYPE.CODE &&
        metricConfig.parameters &&
        "code" in metricConfig.parameters
      ) {
        objectiveName = extractMetricNameFromCode(metricConfig.parameters.code);
      }

      const result = await createOptimization({
        optimization: {
          name: formData.name || undefined,
          studio_config: studioConfig,
          dataset_name: datasetNameValue,
          objective_name: objectiveName,
          status: OPTIMIZATION_STATUS.INITIALIZED,
          project_id: activeProjectId ?? undefined,
        },
      });

      if (result?.id) {
        setLastSessionRunId(result.id);
        navigate({
          to: "/$workspaceName/projects/$projectId/optimizations/$optimizationId",
          params: {
            workspaceName,
            projectId: activeProjectId!,
            optimizationId: result.id,
          },
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
    activeProjectId,
    setLastSessionRunId,
  ]);

  const handleCancel = useCallback(() => {
    navigate({
      to: "/$workspaceName/projects/$projectId/optimizations",
      params: { workspaceName, projectId: activeProjectId! },
    });
  }, [navigate, workspaceName, activeProjectId]);

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

  // Build the chat template string from current form messages
  const getBlueprintTemplate = useCallback(
    () => serializeChatTemplate(form.getValues("messages")),
    [form],
  );

  const handleSaveBlueprintExisting = useCallback(
    async (changeDescription: string) => {
      if (!blueprintRef || !commitPromptData) return null;
      const result = await saveBlueprintExisting({
        ref: blueprintRef,
        promptName: commitPromptData.name,
        template: getBlueprintTemplate(),
        changeDescription: changeDescription || undefined,
      });
      if (result) {
        setBlueprintRef(result.newRef);
        loadedCommitRef.current = result.newRef.commitId;
      }
      return result;
    },
    [
      blueprintRef,
      commitPromptData,
      saveBlueprintExisting,
      getBlueprintTemplate,
    ],
  );

  const handleSaveBlueprintNewField = useCallback(
    async (fieldName: string, changeDescription: string) => {
      const newRef = await saveBlueprintNewField({
        fieldName,
        template: getBlueprintTemplate(),
        changeDescription: changeDescription || undefined,
      });
      if (newRef) {
        setBlueprintRef(newRef);
        loadedCommitRef.current = newRef.commitId;
      }
      return newRef;
    },
    [saveBlueprintNewField, getBlueprintTemplate],
  );

  return {
    form,
    isSubmitting,
    activeProjectId,
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
    blueprintRef,
    blueprintPromptName: commitPromptData?.name,
    blueprintFieldNames,
    isSavingBlueprint,
    hasUnsavedBlueprintChanges,
    handleBlueprintRefChange: setBlueprintRef,
    handleBlueprintRefClear,
    handleSaveBlueprintExisting,
    handleSaveBlueprintNewField,
  };
};
