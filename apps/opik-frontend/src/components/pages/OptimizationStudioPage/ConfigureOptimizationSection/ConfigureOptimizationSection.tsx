import React, { useCallback, useEffect, useMemo, useRef } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Link, useNavigate } from "@tanstack/react-router";
import { SquareArrowOutUpRight } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import useAppStore from "@/store/AppStore";
import { useOptimizationStudioContext } from "../OptimizationStudioContext";
import LLMDatasetSelectBox from "@/components/pages-shared/llm/LLMDatasetSelectBox/LLMDatasetSelectBox";
import PromptModelSelect from "@/components/pages-shared/llm/PromptModelSelect/PromptModelSelect";
import PromptModelConfigs from "@/components/pages-shared/llm/PromptModelSettings/PromptModelConfigs";
import LLMPromptMessages from "@/components/pages-shared/llm/LLMPromptMessages/LLMPromptMessages";
import AlgorithmConfigs from "@/components/pages-shared/optimizations/AlgorithmSettings/AlgorithmConfigs";
import MetricConfigs from "@/components/pages-shared/optimizations/MetricSettings/MetricConfigs";
import {
  OptimizationConfigSchema,
  OptimizationConfigFormType,
  convertOptimizationToFormData,
  convertFormDataToStudioConfig,
} from "./schema";
import {
  OPTIMIZER_TYPE,
  METRIC_TYPE,
  OptimizerParameters,
  MetricParameters,
  OPTIMIZATION_STATUS,
} from "@/types/optimizations";
import {
  PROVIDER_MODEL_TYPE,
  COMPOSED_PROVIDER_TYPE,
  LLMPromptConfigsType,
} from "@/types/providers";
import { parseComposedProviderType } from "@/lib/provider";
import { LLM_MESSAGE_ROLE, LLMMessage } from "@/types/llm";
import { LLM_MESSAGE_ROLE_NAME_MAP } from "@/constants/llm";
import { generateDefaultLLMPromptMessage } from "@/lib/llm";
import {
  getDefaultOptimizerConfig,
  getDefaultMetricConfig,
} from "@/lib/optimizations";
import useOptimizationCreateMutation from "@/api/optimizations/useOptimizationCreateMutation";
import useDatasetsList from "@/api/datasets/useDatasetsList";

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
  const navigate = useNavigate();
  const { activeOptimization, templateData } = useOptimizationStudioContext();
  const createOptimizationMutation = useOptimizationCreateMutation();

  const disableForm =
    activeOptimization?.status === OPTIMIZATION_STATUS.RUNNING;

  const optimizerConfigsCache = useRef<
    Record<OPTIMIZER_TYPE, Partial<OptimizerParameters>>
  >({
    [OPTIMIZER_TYPE.GEPA]: {},
    [OPTIMIZER_TYPE.EVOLUTIONARY]: {},
    [OPTIMIZER_TYPE.HIERARCHICAL_REFLECTIVE]: {},
  });

  const metricConfigsCache = useRef<
    Record<METRIC_TYPE, Partial<MetricParameters>>
  >({
    [METRIC_TYPE.EQUALS]: {},
    [METRIC_TYPE.JSON_SCHEMA_VALIDATOR]: {},
    [METRIC_TYPE.G_EVAL]: {},
  });

  const defaultValues: OptimizationConfigFormType = useMemo(() => {
    return convertOptimizationToFormData(
      activeOptimization || templateData || null,
    );
  }, [activeOptimization, templateData]);

  const form = useForm<OptimizationConfigFormType>({
    resolver: zodResolver(OptimizationConfigSchema),
    defaultValues,
  });

  useEffect(() => {
    form.reset(
      convertOptimizationToFormData(activeOptimization || templateData || null),
    );
  }, [activeOptimization, templateData, form]);

  const datasetId = form.watch("datasetId");
  const optimizerType = form.watch("optimizerType");
  const metricType = form.watch("metricType");
  const model = form.watch("modelName") as PROVIDER_MODEL_TYPE | "";
  const config = form.watch("modelConfig");

  const {
    data: datasetsData,
    isLoading: isLoadingDatasets,
    isFetching: isFetchingDatasets,
  } = useDatasetsList({
    workspaceName,
    page: 1,
    size: 1000,
  });

  const datasets = useMemo(
    () => datasetsData?.content || [],
    [datasetsData?.content],
  );
  const datasetName = datasets?.find((ds) => ds.id === datasetId)?.name || null;

  useEffect(() => {
    if (datasetId && !isLoadingDatasets && !isFetchingDatasets) {
      const datasetExists = datasets.some((ds) => ds.id === datasetId);
      if (!datasetExists) {
        form.setValue("datasetId", "");
      }
    }
  }, [datasetId, datasets, isLoadingDatasets, isFetchingDatasets, form]);

  const calculateModelProvider = useCallback(
    (modelValue: PROVIDER_MODEL_TYPE | ""): COMPOSED_PROVIDER_TYPE | "" => {
      if (!modelValue) {
        return "";
      }
      const result = parseComposedProviderType(modelValue);
      return result[0];
    },
    [],
  );

  const provider = calculateModelProvider(model);

  const handleAddProvider = useCallback(() => {}, []);
  const handleDeleteProvider = useCallback(() => {}, []);

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
      const currentMetricType = form.getValues("metricType");
      const currentParams = form.getValues("metricParams");

      if (currentMetricType && currentParams) {
        metricConfigsCache.current[currentMetricType] = currentParams;
      }

      form.setValue("metricType", newMetricType);

      const cachedConfig = metricConfigsCache.current[newMetricType];

      if (Object.keys(cachedConfig).length > 0) {
        form.setValue("metricParams", cachedConfig);
      } else {
        const defaultConfig = getDefaultMetricConfig(newMetricType);
        form.setValue("metricParams", defaultConfig);
        metricConfigsCache.current[newMetricType] = defaultConfig;
      }
    },
    [form],
  );

  const handleMetricParamsChange = useCallback(
    (newParams: Partial<MetricParameters>) => {
      form.setValue("metricParams", newParams);
      if (metricType) {
        metricConfigsCache.current[metricType] = newParams;
      }
    },
    [form, metricType],
  );

  useEffect(() => {
    if (model && (!config || Object.keys(config).length === 0)) {
      form.setValue("modelConfig", {
        temperature: 1.0,
      });
    }
  }, [model, config, form]);

  const onSubmit = useCallback(
    async (data: OptimizationConfigFormType) => {
      if (!datasetName) {
        console.error("Dataset name not available when submitting form");
        return;
      }

      const studioConfig = convertFormDataToStudioConfig(data, datasetName);

      const optimizationPayload = {
        dataset_name: datasetName,
        objective_name: "Accuracy",
        status: OPTIMIZATION_STATUS.RUNNING,
        studio_config: studioConfig,
      };

      const result = await createOptimizationMutation.mutateAsync({
        optimization: optimizationPayload,
      });

      if (result?.id) {
        navigate({
          to: "/$workspaceName/optimization-studio/run",
          params: { workspaceName },
          search: { optimizationId: result.id },
        });
      }
    },
    [datasetName, createOptimizationMutation, navigate, workspaceName],
  );

  const optimizerOptions = [
    { value: OPTIMIZER_TYPE.GEPA, label: "GEPA" },
    { value: OPTIMIZER_TYPE.EVOLUTIONARY, label: "Evolutionary" },
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
  ];

  return (
    <Form {...form}>
      <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-4">
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
                    {datasetId && (
                      <Link
                        to="/$workspaceName/datasets/$datasetId/items"
                        params={{
                          workspaceName,
                          datasetId,
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
                          View DS
                        </Button>
                      </Link>
                    )}
                  </div>
                  <FormControl>
                    <LLMDatasetSelectBox
                      value={field.value || null}
                      onChange={(value) => field.onChange(value || "")}
                      disabled={disableForm}
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
                      <PromptModelSelect
                        value={field.value as PROVIDER_MODEL_TYPE | ""}
                        onChange={(m) => {
                          if (m) {
                            field.onChange(m);
                            const providerType = calculateModelProvider(m);
                            form.setValue("modelProvider", providerType);
                          }
                        }}
                        provider={provider}
                        hasError={Boolean(form.formState.errors.modelName)}
                        workspaceName={workspaceName}
                        onAddProvider={handleAddProvider}
                        onDeleteProvider={handleDeleteProvider}
                        disabled={disableForm}
                      />

                      <FormField
                        control={form.control}
                        name="modelConfig"
                        render={({ field: configField }) => (
                          <PromptModelConfigs
                            size="icon-sm"
                            provider={provider}
                            model={model}
                            configs={
                              configField.value as Partial<LLMPromptConfigsType>
                            }
                            onChange={configField.onChange}
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
                      disableImages={true}
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

            <div className="flex justify-end pt-2">
              <Button
                type="submit"
                size="sm"
                disabled={disableForm || createOptimizationMutation.isPending}
              >
                {createOptimizationMutation.isPending
                  ? "Starting..."
                  : "Run optimization"}
              </Button>
            </div>
          </CardContent>
        </Card>
      </form>
    </Form>
  );
};

export default ConfigureOptimizationSection;
