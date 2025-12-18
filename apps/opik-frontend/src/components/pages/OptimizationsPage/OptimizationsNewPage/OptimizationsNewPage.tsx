import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { useNavigate } from "@tanstack/react-router";
import { FormProvider, useForm, useFormContext } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useQueryParam, StringParam } from "use-query-params";
import { UnfoldVertical, FoldVertical } from "lucide-react";
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
import useDatasetsList from "@/api/datasets/useDatasetsList";
import useDatasetItemsList from "@/api/datasets/useDatasetItemsList";
import useGetOrCreateDemoDataset from "@/api/datasets/useGetOrCreateDemoDataset";
import useOptimizationCreateMutation from "@/api/optimizations/useOptimizationCreateMutation";
import {
  OptimizationConfigFormType,
  OptimizationConfigSchema,
  convertFormDataToStudioConfig,
  convertOptimizationStudioToFormData,
} from "@/components/pages/OptimizationStudioPage/ConfigureOptimizationSection/schema";
import {
  OPTIMIZATION_STATUS,
  OPTIMIZER_TYPE,
  METRIC_TYPE,
  OptimizerParameters,
  MetricParameters,
} from "@/types/optimizations";
import { PROVIDER_MODEL_TYPE, LLMPromptConfigsType } from "@/types/providers";
import { LLM_MESSAGE_ROLE, LLMMessage } from "@/types/llm";
import { LLM_MESSAGE_ROLE_NAME_MAP } from "@/constants/llm";
import { DEMO_TEMPLATES } from "@/constants/optimizations";
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
import OptimizationTemperatureConfig from "@/components/pages/OptimizationStudioPage/ConfigureOptimizationSection/OptimizationTemperatureConfig";
import CodeHighlighter, {
  SUPPORTED_LANGUAGE,
} from "@/components/shared/CodeHighlighter/CodeHighlighter";

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

const OPTIMIZER_OPTIONS = [
  { value: OPTIMIZER_TYPE.GEPA, label: "GEPA optimizer" },
  {
    value: OPTIMIZER_TYPE.HIERARCHICAL_REFLECTIVE,
    label: "Hierarchical Reflective",
  },
];

const METRIC_OPTIONS = [
  { value: METRIC_TYPE.EQUALS, label: "Equals" },
  { value: METRIC_TYPE.JSON_SCHEMA_VALIDATOR, label: "JSON Schema Validator" },
  { value: METRIC_TYPE.G_EVAL, label: "Custom (G-Eval)" },
  { value: METRIC_TYPE.LEVENSHTEIN, label: "Levenshtein" },
];

const OptimizationsNewPageContent: React.FC = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const navigate = useNavigate();
  const { setLastSessionRunId } = useLastOptimizationRun();
  const createOptimizationMutation = useOptimizationCreateMutation();
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [name, setName] = useState("Optimization studio run");
  const [isSampleExpanded, setIsSampleExpanded] = useState(false);

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

  const { data: datasetItemsData } = useDatasetItemsList(
    {
      datasetId: datasetId || "",
      page: 1,
      size: 1,
      truncate: true,
    },
    {
      enabled: Boolean(datasetId),
    },
  );

  const datasetSample = useMemo(() => {
    if (!datasetItemsData?.content?.[0]) return null;
    return datasetItemsData.content[0].data;
  }, [datasetItemsData]);

  const { calculateModelProvider } = useLLMProviderModelsData();

  const optimizerConfigsCache = useRef<
    Record<OPTIMIZER_TYPE, Partial<OptimizerParameters>>
  >({
    [OPTIMIZER_TYPE.GEPA]: {},
    [OPTIMIZER_TYPE.EVOLUTIONARY]: {},
    [OPTIMIZER_TYPE.HIERARCHICAL_REFLECTIVE]: {},
  });

  const handleDatasetChange = useCallback(
    (id: string | null) => {
      form.setValue("datasetId", id || "", { shouldValidate: true });
    },
    [form],
  );

  const handleOptimizerTypeChange = useCallback(
    (newOptimizerType: OPTIMIZER_TYPE) => {
      const currentOptimizerType = form.getValues("optimizerType");
      const currentParams = form.getValues("optimizerParams");

      if (currentOptimizerType && currentParams) {
        optimizerConfigsCache.current[currentOptimizerType] = currentParams;
      }

      form.setValue("optimizerType", newOptimizerType, {
        shouldValidate: true,
      });

      const cachedConfig = optimizerConfigsCache.current[newOptimizerType];

      if (Object.keys(cachedConfig).length > 0) {
        form.setValue("optimizerParams", cachedConfig, {
          shouldValidate: true,
        });
      } else {
        const defaultConfig = getDefaultOptimizerConfig(newOptimizerType);
        form.setValue("optimizerParams", defaultConfig, {
          shouldValidate: true,
        });
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
      // Set metricType first so the discriminated union validates against the correct schema
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

    let datasetNameValue = "";
    if (formData.datasetId) {
      const selectedDs = datasets.find((ds) => ds.id === formData.datasetId);
      datasetNameValue = selectedDs?.name || "";
    }

    if (!datasetNameValue) {
      form.setError("datasetId", { message: "Dataset is required" });
      return;
    }

    setIsSubmitting(true);

    try {
      const studioConfig = convertFormDataToStudioConfig(
        formData,
        datasetNameValue,
      );

      const result = await createOptimizationMutation.mutateAsync({
        optimization: {
          name: name || undefined,
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
    name,
    createOptimizationMutation,
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

  return (
    <div className="py-6">
      <div className="mb-6 flex items-center justify-between">
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

      <div className="flex gap-6">
        {/* Left Column - Name and Prompt */}
        <div className="flex-1 space-y-6">
          {/* Name Field */}
          <div>
            <Label className="comet-body-s-accented mb-2 block">Name</Label>
            <Input
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="Enter optimization name"
              className="h-10"
            />
          </div>

          {/* Prompt Section */}
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
                const hintMessage = datasetSample
                  ? `Use {variable_name} syntax to reference dataset variables in your prompt: ${Object.keys(
                      datasetSample,
                    )
                      .map((key) => `{${key}}`)
                      .join(", ")}`
                  : "";

                return (
                  <FormItem>
                    <LLMPromptMessages
                      messages={messages}
                      possibleTypes={MESSAGE_TYPE_OPTIONS}
                      hidePromptActions
                      disableMedia
                      hint={hintMessage}
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

        {/* Right Column - Configuration */}
        <div className="w-[500px] shrink-0 space-y-6">
          {/* Algorithm */}
          <FormField
            control={form.control}
            name="optimizerType"
            render={({ field }) => (
              <FormItem>
                <FormLabel className="comet-body-s-accented">
                  Algorithm
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

          {/* Optimization samples section */}
          <div className="space-y-3">
            {/* Dataset */}
            <FormField
              control={form.control}
              name="datasetId"
              render={({ field }) => (
                <FormItem>
                  <FormLabel className="comet-body-s-accented">
                    Dataset
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

            {/* Dataset item sample */}
            {datasetSample && (
              <div>
                <Button
                  type="button"
                  variant="link"
                  size="sm"
                  className="h-auto p-0 text-muted-slate"
                  onClick={() => setIsSampleExpanded(!isSampleExpanded)}
                >
                  {isSampleExpanded ? (
                    <>
                      <FoldVertical className="mr-1 size-4" />
                      Collapse dataset item sample
                    </>
                  ) : (
                    <>
                      <UnfoldVertical className="mr-1 size-4" />
                      View dataset item sample
                    </>
                  )}
                </Button>
                {isSampleExpanded && (
                  <div className="mt-2 rounded-md border border-border">
                    <div className="flex h-10 items-center justify-between border-b border-border px-4">
                      <span className="comet-body-s text-muted-slate">
                        Payload
                      </span>
                    </div>
                    <CodeHighlighter
                      data={JSON.stringify(datasetSample, null, 2)}
                      language={SUPPORTED_LANGUAGE.json}
                    />
                  </div>
                )}
              </div>
            )}
          </div>

          {/* Metric */}
          <div className="space-y-3">
            <FormField
              control={form.control}
              name="metricType"
              render={({ field }) => (
                <FormItem>
                  <FormLabel className="comet-body-s-accented">
                    Metric
                  </FormLabel>
                  <FormControl>
                    <SelectBox
                      value={field.value}
                      onChange={handleMetricTypeChange}
                      options={METRIC_OPTIONS}
                      placeholder="Select metric"
                      className="w-full"
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            {/* Inline Metric Config */}
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

const OptimizationsNewPage: React.FC = () => {
  const [templateId] = useQueryParam("template", StringParam);
  const { getOrCreateDataset } = useGetOrCreateDemoDataset();
  const datasetCreationRef = useRef<string | null>(null);

  const templateData = useMemo(() => {
    if (templateId) {
      return DEMO_TEMPLATES.find((t) => t.id === templateId) || null;
    }
    return null;
  }, [templateId]);

  const defaultValues = useMemo(() => {
    return convertOptimizationStudioToFormData(templateData);
  }, [templateData]);

  const form = useForm<OptimizationConfigFormType>({
    resolver: zodResolver(OptimizationConfigSchema),
    defaultValues,
    mode: "onChange",
  });

  // Reset form when template changes
  useEffect(() => {
    form.reset(convertOptimizationStudioToFormData(templateData));
    datasetCreationRef.current = null;
  }, [templateData, form]);

  // Create demo dataset when template with dataset_items is selected
  useEffect(() => {
    const templateIdToCreate = templateData?.id;
    if (
      templateData?.dataset_items &&
      templateIdToCreate &&
      datasetCreationRef.current !== templateIdToCreate
    ) {
      datasetCreationRef.current = templateIdToCreate;

      getOrCreateDataset(templateData).then((dataset) => {
        if (dataset?.id) {
          form.setValue("datasetId", dataset.id, { shouldValidate: true });
        }
      });
    }
  }, [templateData, getOrCreateDataset, form]);

  return (
    <FormProvider {...form}>
      <OptimizationsNewPageContent />
    </FormProvider>
  );
};

export default OptimizationsNewPage;
