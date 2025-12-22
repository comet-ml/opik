import React, { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate } from "@tanstack/react-router";
import { useFormContext } from "react-hook-form";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import useAppStore from "@/store/AppStore";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import useDatasetsList from "@/api/datasets/useDatasetsList";
import useDatasetSamplePreview from "./useDatasetSamplePreview";
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
import { LLM_MESSAGE_ROLE, LLMMessage } from "@/types/llm";
import { useLastOptimizationRun } from "@/lib/optimizationSessionStorage";
import { generateDefaultLLMPromptMessage } from "@/lib/llm";
import {
  getDefaultOptimizerConfig,
  getDefaultMetricConfig,
  getOptimizationDefaultConfigByProvider,
} from "@/lib/optimizations";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";
import LLMPromptMessages from "@/components/pages-shared/llm/LLMPromptMessages/LLMPromptMessages";
import DatasetSelectBox from "@/components/pages-shared/llm/DatasetSelectBox/DatasetSelectBox";
import OptimizationModelSelect from "@/components/pages-shared/optimizations/OptimizationModelSelect/OptimizationModelSelect";
import AlgorithmConfigs from "@/components/pages-shared/optimizations/AlgorithmSettings/AlgorithmConfigs";
import MetricConfigs from "@/components/pages-shared/optimizations/MetricSettings/MetricConfigs";
import OptimizationTemperatureConfig from "@/components/pages-shared/optimizations/OptimizationConfigForm/OptimizationTemperatureConfig";
import {
  OPTIMIZATION_MESSAGE_TYPE_OPTIONS,
  OPTIMIZER_OPTIONS,
  OPTIMIZATION_METRIC_OPTIONS,
} from "@/constants/optimizations";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import ExplainerDescription from "@/components/shared/ExplainerDescription/ExplainerDescription";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import DatasetSamplePreview from "./DatasetSamplePreview";

const getBreadcrumbTitle = (name: string) =>
  name?.trim() ? `${name} (new)` : "... (new)";

const OptimizationsNewPageContent: React.FC = () => {
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

  return (
    <div className="w-full py-6">
      <div className="mb-2 flex items-center justify-between">
        <h1 className="comet-title-l">Optimize a prompt</h1>
        <div className="flex gap-2">
          <Button variant="outline" size="sm" onClick={handleCancel}>
            Cancel
          </Button>
          <Button
            size="sm"
            onClick={handleSubmit}
            disabled={isSubmitting || !form.formState.isValid}
          >
            {isSubmitting ? "Starting..." : "Optimize prompt"}
          </Button>
        </div>
      </div>
      <ExplainerDescription
        {...EXPLAINERS_MAP[EXPLAINER_ID.whats_the_optimization_config]}
        className="mb-6"
      />

      <div className="flex gap-6">
        <div className="flex-1 space-y-6">
          <FormField
            control={form.control}
            name="name"
            render={({ field }) => (
              <FormItem>
                <FormLabel className="comet-body-s-accented">Name</FormLabel>
                <FormControl>
                  <Input
                    {...field}
                    onChange={(e) => handleNameChange(e.target.value)}
                    placeholder="Enter optimization name, or the name will be generated automatically"
                    className="h-10"
                  />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />

          <div>
            <div className="mb-2 flex items-center justify-between">
              <Label className="comet-body-s-accented">Prompt</Label>
              <FormField
                control={form.control}
                name="modelName"
                render={({ field }) => (
                  <FormItem className="flex items-center gap-2">
                    <FormControl>
                      <div className="flex h-7 items-center gap-1">
                        <div className="h-full w-56">
                          <OptimizationModelSelect
                            value={field.value as PROVIDER_MODEL_TYPE | ""}
                            onChange={handleModelChange}
                            hasError={Boolean(form.formState.errors.modelName)}
                          />
                        </div>
                        <OptimizationTemperatureConfig
                          size="icon-xs"
                          model={model}
                          configs={config}
                          onChange={handleModelConfigChange}
                        />
                      </div>
                    </FormControl>
                  </FormItem>
                )}
              />
            </div>
            <FormField
              control={form.control}
              name="messages"
              render={({ field }) => {
                const messages = field.value;

                return (
                  <FormItem>
                    <LLMPromptMessages
                      messages={messages}
                      possibleTypes={OPTIMIZATION_MESSAGE_TYPE_OPTIONS}
                      hidePromptActions
                      disableMedia
                      promptVariables={datasetVariables}
                      onChange={(messages: LLMMessage[]) => {
                        field.onChange(messages);
                      }}
                      onAddMessage={() =>
                        field.onChange([
                          ...messages,
                          generateDefaultLLMPromptMessage({
                            role: LLM_MESSAGE_ROLE.user,
                          }),
                        ])
                      }
                    />
                    <FormMessage />
                  </FormItem>
                );
              }}
            />
          </div>
        </div>

        <div className="w-[500px] shrink-0 space-y-6">
          <FormField
            control={form.control}
            name="optimizerType"
            render={({ field }) => (
              <FormItem>
                <FormLabel className="comet-body-s-accented flex items-center gap-1">
                  Algorithm
                  <ExplainerIcon
                    {...EXPLAINERS_MAP[
                      EXPLAINER_ID.whats_the_algorithm_section
                    ]}
                  />
                </FormLabel>
                <FormControl>
                  <div className="flex items-center gap-2">
                    <SelectBox
                      value={field.value}
                      onChange={handleOptimizerTypeChange}
                      options={OPTIMIZER_OPTIONS}
                      placeholder="Select algorithm"
                      className="w-full"
                    />
                    <FormField
                      control={form.control}
                      name="optimizerParams"
                      render={({ field: paramsField }) => (
                        <AlgorithmConfigs
                          size="icon"
                          optimizerType={optimizerType}
                          configs={
                            paramsField.value as Partial<OptimizerParameters>
                          }
                          onChange={handleOptimizerParamsChange}
                        />
                      )}
                    />
                  </div>
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />

          <div className="space-y-3">
            <FormField
              control={form.control}
              name="datasetId"
              render={({ field }) => (
                <FormItem>
                  <FormLabel className="comet-body-s-accented flex items-center gap-1">
                    Dataset
                    <ExplainerIcon
                      {...EXPLAINERS_MAP[
                        EXPLAINER_ID.whats_the_dataset_section
                      ]}
                    />
                  </FormLabel>
                  <FormControl>
                    <DatasetSelectBox
                      value={field.value}
                      onChange={handleDatasetChange}
                      workspaceName={workspaceName}
                      showClearButton={false}
                      buttonClassName="h-10 w-full"
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            {datasetSample && (
              <DatasetSamplePreview datasetSample={datasetSample} />
            )}
          </div>

          <div className="space-y-3">
            <FormField
              control={form.control}
              name="metricType"
              render={({ field }) => (
                <FormItem>
                  <FormLabel className="comet-body-s-accented flex items-center gap-1">
                    Metric
                    <ExplainerIcon
                      {...EXPLAINERS_MAP[EXPLAINER_ID.whats_the_metric_section]}
                    />
                  </FormLabel>
                  <FormControl>
                    <SelectBox
                      value={field.value}
                      onChange={handleMetricTypeChange}
                      options={OPTIMIZATION_METRIC_OPTIONS}
                      placeholder="Select metric"
                      className="w-full"
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name="metricParams"
              render={({ field: paramsField }) => (
                <div className="pl-4">
                  <MetricConfigs
                    inline
                    metricType={metricType}
                    configs={paramsField.value as Partial<MetricParameters>}
                    onChange={handleMetricParamsChange}
                    datasetVariables={datasetVariables}
                  />
                  {form.formState.errors.metricParams && (
                    <p className="mt-2 text-sm text-destructive">
                      {getFirstMetricParamsError()}
                    </p>
                  )}
                </div>
              )}
            />
          </div>
        </div>
      </div>
    </div>
  );
};

export default OptimizationsNewPageContent;
