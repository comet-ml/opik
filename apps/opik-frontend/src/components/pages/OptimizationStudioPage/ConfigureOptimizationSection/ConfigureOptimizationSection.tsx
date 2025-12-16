import React, { useCallback, useEffect, useMemo, useRef } from "react";
import { useFormContext } from "react-hook-form";
import { Link } from "@tanstack/react-router";
import { SquareArrowOutUpRight } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import {
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import useAppStore from "@/store/AppStore";
import { useOptimizationStudioContext } from "../OptimizationStudioContext";
import DatasetSelectBox from "@/components/pages-shared/llm/DatasetSelectBox/DatasetSelectBox";
import OptimizationModelSelect from "@/components/pages-shared/optimizations/OptimizationModelSelect/OptimizationModelSelect";
import OptimizationTemperatureConfig from "./OptimizationTemperatureConfig";
import LLMPromptMessages from "@/components/pages-shared/llm/LLMPromptMessages/LLMPromptMessages";
import AlgorithmConfigs from "@/components/pages-shared/optimizations/AlgorithmSettings/AlgorithmConfigs";
import MetricConfigs from "@/components/pages-shared/optimizations/MetricSettings/MetricConfigs";
import { OptimizationConfigFormType } from "./schema";
import {
  OPTIMIZER_TYPE,
  METRIC_TYPE,
  OptimizerParameters,
  MetricParameters,
  OPTIMIZATION_STATUS,
} from "@/types/optimizations";
import { PROVIDER_MODEL_TYPE, LLMPromptConfigsType } from "@/types/providers";
import { LLM_MESSAGE_ROLE, LLMMessage } from "@/types/llm";
import { LLM_MESSAGE_ROLE_NAME_MAP } from "@/constants/llm";
import { generateDefaultLLMPromptMessage } from "@/lib/llm";
import {
  getDefaultOptimizerConfig,
  getDefaultMetricConfig,
  getOptimizationDefaultConfigByProvider,
} from "@/lib/optimizations";
import useDatasetsList from "@/api/datasets/useDatasetsList";
import useDatasetItemsList from "@/api/datasets/useDatasetItemsList";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";

const MESSAGE_TYPE_OPTIONS = [
  {
    label: LLM_MESSAGE_ROLE_NAME_MAP[LLM_MESSAGE_ROLE.system],
    value: LLM_MESSAGE_ROLE.system,
  },
  {
    label: LLM_MESSAGE_ROLE_NAME_MAP[LLM_MESSAGE_ROLE.user],
    value: LLM_MESSAGE_ROLE.user,
  },
  {
    label: LLM_MESSAGE_ROLE_NAME_MAP[LLM_MESSAGE_ROLE.assistant],
    value: LLM_MESSAGE_ROLE.assistant,
  },
];

const ConfigureOptimizationSection: React.FC = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { activeOptimization } = useOptimizationStudioContext();
  const form = useFormContext<OptimizationConfigFormType>();

  const disableForm =
    activeOptimization?.status === OPTIMIZATION_STATUS.RUNNING;

  const optimizerConfigsCache = useRef<
    Record<OPTIMIZER_TYPE, Partial<OptimizerParameters>>
  >({
    [OPTIMIZER_TYPE.GEPA]: {},
    [OPTIMIZER_TYPE.EVOLUTIONARY]: {}, // Commented out from UI options below
    [OPTIMIZER_TYPE.HIERARCHICAL_REFLECTIVE]: {},
  });

  const datasetId = form.watch("datasetId");
  const optimizerType = form.watch("optimizerType");
  const metricType = form.watch("metricType");
  const model = form.watch("modelName") as PROVIDER_MODEL_TYPE | "";
  const config = form.watch("modelConfig");

  const { data: datasetsData } = useDatasetsList({
    workspaceName,
    page: 1,
    size: 1000,
  });

  const datasets = useMemo(
    () => datasetsData?.content || [],
    [datasetsData?.content],
  );

  const handleDatasetChange = useCallback(
    (id: string | null) => {
      form.setValue("datasetId", id || "");
    },
    [form],
  );

  const selectedDataset = useMemo(
    () => datasets.find((ds) => ds.id === datasetId),
    [datasets, datasetId],
  );

  const { data: datasetItemsData } = useDatasetItemsList(
    {
      datasetId: selectedDataset?.id || "",
      page: 1,
      size: 1,
    },
    {
      enabled:
        !!selectedDataset?.id &&
        metricType === METRIC_TYPE.JSON_SCHEMA_VALIDATOR,
    },
  );

  // get the first dataset item's data to infer schema
  const firstDatasetItemData = useMemo(
    () =>
      datasetItemsData?.content?.[0]?.data as
        | Record<string, unknown>
        | undefined,
    [datasetItemsData?.content],
  );

  const { calculateModelProvider } = useLLMProviderModelsData();

  const handleOptimizerTypeChange = useCallback(
    (newOptimizerType: OPTIMIZER_TYPE) => {
      const currentOptimizerType = form.getValues("optimizerType");
      const currentParams = form.getValues("optimizerParams");

      if (currentOptimizerType && currentParams) {
        optimizerConfigsCache.current[currentOptimizerType] = currentParams;
      }

      form.setValue("optimizerType", newOptimizerType);

      const cachedConfig = optimizerConfigsCache.current[newOptimizerType];

      if (Object.keys(cachedConfig).length > 0) {
        form.setValue("optimizerParams", cachedConfig);
      } else {
        const defaultConfig = getDefaultOptimizerConfig(newOptimizerType);
        form.setValue("optimizerParams", defaultConfig);
        optimizerConfigsCache.current[newOptimizerType] = defaultConfig;
      }
    },
    [form],
  );

  const handleOptimizerParamsChange = useCallback(
    (newParams: Partial<OptimizerParameters>) => {
      form.setValue("optimizerParams", newParams);
      if (optimizerType) {
        optimizerConfigsCache.current[optimizerType] = newParams;
      }
    },
    [form, optimizerType],
  );

  const handleMetricTypeChange = useCallback(
    (newMetricType: METRIC_TYPE) => {
      const defaultConfig = getDefaultMetricConfig(newMetricType);
      form.setValue(
        "metricParams",
        defaultConfig as OptimizationConfigFormType["metricParams"],
      );
      form.setValue("metricType", newMetricType);
    },
    [form],
  );

  const handleMetricParamsChange = useCallback(
    (newParams: Partial<MetricParameters>) => {
      form.setValue(
        "metricParams",
        newParams as OptimizationConfigFormType["metricParams"],
      );
    },
    [form],
  );

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

  const optimizerOptions = [
    { value: OPTIMIZER_TYPE.GEPA, label: "GEPA" },
    // { value: OPTIMIZER_TYPE.EVOLUTIONARY, label: "Evolutionary" },
    {
      value: OPTIMIZER_TYPE.HIERARCHICAL_REFLECTIVE,
      label: "Hierarchical Reflective",
    },
  ];

  const metricOptions = [
    { value: METRIC_TYPE.EQUALS, label: "Equals" },
    {
      value: METRIC_TYPE.JSON_SCHEMA_VALIDATOR,
      label: "JSON Schema Validator",
    },
    { value: METRIC_TYPE.G_EVAL, label: "Custom (G-Eval)" },
    { value: METRIC_TYPE.LEVENSHTEIN, label: "Levenshtein" },
  ];

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

  return (
    <form className="space-y-4">
      <Card>
        <CardHeader className="space-y-0.5 px-4 pt-3">
          <CardTitle className="comet-body-s-accented">
            Configure optimization
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-4 p-6">
          <FormField
            control={form.control}
            name="datasetId"
            render={({ field }) => (
              <FormItem>
                <div className="mb-2 flex items-center justify-between">
                  <FormLabel className="comet-body-s">Dataset</FormLabel>
                  {selectedDataset && (
                    <Link
                      to="/$workspaceName/datasets/$datasetId/items"
                      params={{
                        workspaceName,
                        datasetId: selectedDataset.id,
                      }}
                      target="_blank"
                    >
                      <Button
                        type="button"
                        variant="link"
                        size="sm"
                        className="h-auto p-0 text-xs"
                      >
                        <SquareArrowOutUpRight className="mr-1 size-3" />
                        Open dataset
                      </Button>
                    </Link>
                  )}
                </div>
                <FormControl>
                  <DatasetSelectBox
                    value={field.value}
                    onChange={handleDatasetChange}
                    workspaceName={workspaceName}
                    disabled={disableForm}
                    showClearButton={false}
                    buttonClassName="h-8 w-full"
                  />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />

          <FormField
            control={form.control}
            name="optimizerType"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Algorithm</FormLabel>
                <FormControl>
                  <div className="flex h-8 items-center gap-2">
                    <SelectBox
                      value={field.value}
                      onChange={handleOptimizerTypeChange}
                      options={optimizerOptions}
                      placeholder="Select algorithm"
                      className="h-8 w-full"
                      disabled={disableForm}
                    />

                    <FormField
                      control={form.control}
                      name="optimizerParams"
                      render={({ field: paramsField }) => (
                        <AlgorithmConfigs
                          size="icon-sm"
                          optimizerType={optimizerType}
                          configs={
                            paramsField.value as Partial<OptimizerParameters>
                          }
                          onChange={handleOptimizerParamsChange}
                          disabled={disableForm}
                        />
                      )}
                    />
                  </div>
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />

          <FormField
            control={form.control}
            name="metricType"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Metric</FormLabel>
                <FormControl>
                  <div className="flex h-8 items-center gap-2">
                    <SelectBox
                      value={field.value}
                      onChange={handleMetricTypeChange}
                      options={metricOptions}
                      placeholder="Select metric"
                      className="h-8 w-full"
                      disabled={disableForm}
                    />

                    <FormField
                      control={form.control}
                      name="metricParams"
                      render={({ field: paramsField }) => (
                        <MetricConfigs
                          size="icon-sm"
                          metricType={metricType}
                          configs={
                            paramsField.value as Partial<MetricParameters>
                          }
                          onChange={handleMetricParamsChange}
                          disabled={disableForm}
                        />
                      )}
                    />
                  </div>
                </FormControl>
                <FormMessage />
                {form.formState.errors.metricParams && (
                  <p className="text-sm text-destructive">
                    {getFirstMetricParamsError()}
                  </p>
                )}
              </FormItem>
            )}
          />

          <FormField
            control={form.control}
            name="modelName"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Model</FormLabel>
                <FormControl>
                  <div className="flex h-8 items-center gap-2">
                    <OptimizationModelSelect
                      value={field.value as PROVIDER_MODEL_TYPE | ""}
                      onChange={handleModelChange}
                      hasError={Boolean(form.formState.errors.modelName)}
                      disabled={disableForm}
                    />
                    <OptimizationTemperatureConfig
                      size="icon-sm"
                      model={model}
                      configs={config}
                      onChange={handleModelConfigChange}
                      disabled={disableForm}
                    />
                  </div>
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />
        </CardContent>
      </Card>

      <Card>
        <CardContent className="space-y-4 p-6">
          <FormField
            control={form.control}
            name="messages"
            render={({ field }) => {
              const messages = field.value;

              return (
                <FormItem>
                  <FormLabel>Prompt</FormLabel>
                  <LLMPromptMessages
                    messages={messages}
                    possibleTypes={MESSAGE_TYPE_OPTIONS}
                    hidePromptActions={true}
                    disableMedia
                    disabled={disableForm}
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
        </CardContent>
      </Card>
    </form>
  );
};

export default ConfigureOptimizationSection;
