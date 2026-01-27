import React, { useEffect, useMemo } from "react";
import { UseFormReturn } from "react-hook-form";
import get from "lodash/get";

import { EvaluationRuleFormType } from "@/components/pages-shared/automations/AddEditRuleDialog/schema";
import LLMPromptMessagesVariables from "@/components/pages-shared/llm/LLMPromptMessagesVariables/LLMPromptMessagesVariables";
import {
  FormControl,
  FormField,
  FormItem,
  FormMessage,
} from "@/components/ui/form";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Skeleton } from "@/components/ui/skeleton";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import useCommonMetricsQuery from "@/api/automations/useCommonMetricsQuery";
import {
  CommonMetric,
  EVALUATORS_RULE_SCOPE,
  InitParameter,
  ScoreParameter,
} from "@/types/automations";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { Info } from "lucide-react";

type CommonMetricRuleDetailsProps = {
  form: UseFormReturn<EvaluationRuleFormType>;
  projectName?: string;
  datasetColumnNames?: string[];
};

const CommonMetricRuleDetails: React.FC<CommonMetricRuleDetailsProps> = ({
  form,
  projectName,
  datasetColumnNames,
}) => {
  const { data: metricsData, isLoading, error } = useCommonMetricsQuery();

  const scope = form.watch("scope");
  const isThreadScope = scope === EVALUATORS_RULE_SCOPE.thread;
  const isSpanScope = scope === EVALUATORS_RULE_SCOPE.span;

  // Determine the type for autocomplete based on scope
  const autocompleteType = isSpanScope
    ? TRACE_DATA_TYPE.spans
    : TRACE_DATA_TYPE.traces;

  // Get the selected metric ID from form state
  const selectedMetricId = form.watch("commonMetricDetails.metricId");

  // Auto-select the first metric if none is selected
  useEffect(() => {
    if (metricsData?.content && metricsData.content.length > 0) {
      const currentMetricId = form.getValues("commonMetricDetails.metricId");
      if (!currentMetricId) {
        // Set the first metric as default
        form.setValue(
          "commonMetricDetails.metricId",
          metricsData.content[0].id,
        );
      }
    }
  }, [metricsData?.content, form]);

  // Find the selected metric
  const selectedMetric = useMemo(() => {
    if (!metricsData?.content || !selectedMetricId) return null;
    return metricsData.content.find((m) => m.id === selectedMetricId) || null;
  }, [metricsData?.content, selectedMetricId]);

  // Update form values when metric changes
  useEffect(() => {
    if (selectedMetric) {
      // Set up arguments based on metric score_parameters (only for non-thread scope)
      // Only include mappable parameters (which map to trace/span fields)
      if (!isThreadScope) {
        const newArguments: Record<string, string> = {};
        selectedMetric.score_parameters
          .filter((p) => p.mappable && p.required)
          .forEach((param) => {
            // Try to preserve existing argument values
            const currentArguments = form.getValues(
              "pythonCodeDetails.arguments",
            );
            newArguments[param.name] = currentArguments?.[param.name] ?? "";
          });

        form.setValue("pythonCodeDetails.arguments", newArguments);
        form.setValue("pythonCodeDetails.parsingArgumentsError", false);

        // Set up scoreConfig for non-mappable score parameters
        // These are static values that go directly to score(), not __init__()
        const newScoreConfig: Record<string, string> = {};
        selectedMetric.score_parameters
          .filter((p) => !p.mappable)
          .forEach((param) => {
            // Try to preserve existing score config values
            const currentScoreConfig = form.getValues(
              "pythonCodeDetails.score_config",
            );
            newScoreConfig[param.name] = currentScoreConfig?.[param.name] ?? "";
          });
        form.setValue("pythonCodeDetails.score_config", newScoreConfig);
      }

      // Set up init config with default values from __init__ parameters
      const newInitConfig: Record<string, string | boolean | number | null> =
        {};

      // Add init parameters only
      selectedMetric.init_parameters?.forEach((param) => {
        // Try to preserve existing config values
        const currentConfig = form.getValues("commonMetricDetails.initConfig");
        if (currentConfig?.[param.name] !== undefined) {
          newInitConfig[param.name] = currentConfig[param.name];
        } else {
          // Set default value based on type
          newInitConfig[param.name] = parseDefaultValue(
            param.default_value,
            param.type,
          );
        }
      });

      form.setValue("commonMetricDetails.initConfig", newInitConfig);
    }
  }, [selectedMetric, form, isThreadScope]);

  // Helper to parse default values from Python strings
  const parseDefaultValue = (
    defaultValue: string | null | undefined,
    type: string,
  ): string | boolean | number | null => {
    // Handle null, undefined, or "None" string
    if (
      defaultValue === null ||
      defaultValue === undefined ||
      defaultValue === "None"
    ) {
      return null;
    }
    const lowerType = type.toLowerCase();
    if (lowerType === "bool" || lowerType === "boolean") {
      return defaultValue === "True";
    }
    if (
      lowerType === "int" ||
      lowerType === "float" ||
      lowerType === "number"
    ) {
      const num = parseFloat(defaultValue);
      return isNaN(num) ? null : num;
    }
    // For strings, remove quotes if present
    if (
      (defaultValue.startsWith('"') && defaultValue.endsWith('"')) ||
      (defaultValue.startsWith("'") && defaultValue.endsWith("'"))
    ) {
      return defaultValue.slice(1, -1);
    }
    return defaultValue;
  };

  if (isLoading) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-20 w-full" />
      </div>
    );
  }

  if (error) {
    return (
      <Alert variant="destructive">
        <AlertDescription>
          Failed to load common metrics. Please try again later.
        </AlertDescription>
      </Alert>
    );
  }

  const metrics = metricsData?.content || [];

  return (
    <>
      <FormField
        control={form.control}
        name="commonMetricDetails.metricId"
        render={({ field }) => {
          return (
            <FormItem>
              <Label>Select metric</Label>
              <FormControl>
                <Select value={field.value} onValueChange={field.onChange}>
                  <SelectTrigger>
                    <SelectValue placeholder="Choose a metric..." />
                  </SelectTrigger>
                  <SelectContent>
                    {metrics.map((metric: CommonMetric) => (
                      <SelectItem key={metric.id} value={metric.id}>
                        {metric.name}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </FormControl>
              <FormMessage />
            </FormItem>
          );
        }}
      />

      {selectedMetric && (
        <>
          <div className="rounded-md border border-border bg-muted/50 p-4">
            <p className="text-sm text-muted-foreground">
              {selectedMetric.description}
            </p>
          </div>

          {/* Configuration Parameters (Init Parameters only) */}
          {selectedMetric.init_parameters.length > 0 && (
            <div className="space-y-4">
              <div className="flex items-center gap-1">
                <Label className="text-sm font-medium">Configuration</Label>
                <TooltipWrapper content="These settings configure how the metric behaves. They are set once when the rule is created.">
                  <button
                    type="button"
                    className="inline-flex cursor-help"
                    tabIndex={0}
                  >
                    <Info className="size-4 text-muted-foreground" />
                  </button>
                </TooltipWrapper>
              </div>
              <div className="grid gap-4">
                {/* Render init parameters */}
                {selectedMetric.init_parameters.map((param: InitParameter) => (
                  <InitParameterInput
                    key={param.name}
                    param={param}
                    form={form}
                  />
                ))}
              </div>
            </div>
          )}

          {/* Non-mappable score parameters (static values for score() method) */}
          {!isThreadScope &&
            selectedMetric.score_parameters.some((p) => !p.mappable) && (
              <div className="space-y-4">
                <div className="flex items-center gap-1">
                  <Label className="text-sm font-medium">
                    Score Parameters
                  </Label>
                  <TooltipWrapper content="These are static values passed to the metric's score() method. Unlike variable mappings, these values don't change per trace/span.">
                    <button
                      type="button"
                      className="inline-flex cursor-help"
                      tabIndex={0}
                    >
                      <Info className="size-4 text-muted-foreground" />
                    </button>
                  </TooltipWrapper>
                </div>
                <div className="grid gap-4">
                  {selectedMetric.score_parameters
                    .filter((p) => !p.mappable)
                    .map((param) => (
                      <ScoreParameterConfigInput
                        key={param.name}
                        param={param}
                        form={form}
                      />
                    ))}
                </div>
              </div>
            )}

          {/* Variable Mappings (mappable score method parameters only) */}
          {!isThreadScope &&
            selectedMetric.score_parameters.some((p) => p.mappable) && (
              <FormField
                control={form.control}
                name="pythonCodeDetails.arguments"
                render={({ field, formState }) => {
                  const validationErrors = get(formState.errors, [
                    "pythonCodeDetails",
                    "arguments",
                  ]);

                  return (
                    <LLMPromptMessagesVariables
                      parsingError={false}
                      validationErrors={validationErrors}
                      projectId={form.watch("projectIds")[0] || ""}
                      variables={field.value}
                      onChange={field.onChange}
                      description="Map the metric parameters to trace/span fields. Required parameters are extracted from the selected metric's score method."
                      errorText=""
                      projectName={projectName}
                      datasetColumnNames={datasetColumnNames}
                      type={autocompleteType}
                      includeIntermediateNodes
                    />
                  );
                }}
              />
            )}
        </>
      )}
    </>
  );
};

// Sub-component for rendering individual init parameter inputs
type InitParameterInputProps = {
  param: InitParameter;
  form: UseFormReturn<EvaluationRuleFormType>;
};

const InitParameterInput: React.FC<InitParameterInputProps> = ({
  param,
  form,
}) => {
  const lowerType = param.type.toLowerCase();
  const isBool = lowerType === "bool" || lowerType === "boolean";

  // Watch the entire initConfig object and extract the specific value
  const initConfig = form.watch("commonMetricDetails.initConfig");
  const currentValue = initConfig?.[param.name];

  const handleChange = (value: string | boolean | number | null) => {
    const currentConfig =
      form.getValues("commonMetricDetails.initConfig") || {};
    form.setValue(
      "commonMetricDetails.initConfig",
      {
        ...currentConfig,
        [param.name]: value,
      },
      { shouldDirty: true },
    );
  };

  const renderInput = () => {
    if (isBool) {
      return (
        <div className="flex items-center gap-2">
          <Switch
            checked={currentValue === true}
            onCheckedChange={(checked) => handleChange(checked)}
          />
          <span className="text-sm text-muted-foreground">
            {currentValue === true ? "True" : "False"}
          </span>
        </div>
      );
    }

    // For string/other types
    return (
      <Input
        value={currentValue?.toString() ?? ""}
        onChange={(e) => {
          const val = e.target.value;
          // Try to parse as number if the type suggests it
          if (
            lowerType === "int" ||
            lowerType === "float" ||
            lowerType === "number"
          ) {
            const num = parseFloat(val);
            handleChange(isNaN(num) ? val : num);
          } else {
            handleChange(val || null);
          }
        }}
        placeholder={param.default_value ?? `Enter ${param.name}`}
      />
    );
  };

  return (
    <div className="grid gap-2">
      <div className="flex items-center gap-1">
        <Label className="text-sm">
          {param.name}
          {param.required && <span className="text-destructive">*</span>}
        </Label>
        {param.description && (
          <TooltipWrapper content={param.description}>
            <button
              type="button"
              className="inline-flex cursor-help"
              tabIndex={0}
            >
              <Info className="size-3.5 text-muted-foreground" />
            </button>
          </TooltipWrapper>
        )}
      </div>
      {renderInput()}
    </div>
  );
};

// Sub-component for rendering non-mappable score parameters
// These values go to score_config and are passed directly to the score() method
type ScoreParameterConfigInputProps = {
  param: ScoreParameter;
  form: UseFormReturn<EvaluationRuleFormType>;
};

const ScoreParameterConfigInput: React.FC<ScoreParameterConfigInputProps> = ({
  param,
  form,
}) => {
  // Watch the score_config object and extract the specific value
  const scoreConfig = form.watch("pythonCodeDetails.score_config");
  const currentValue = scoreConfig?.[param.name] ?? "";

  const handleChange = (value: string) => {
    const currentConfig =
      form.getValues("pythonCodeDetails.score_config") || {};
    form.setValue(
      "pythonCodeDetails.score_config",
      {
        ...currentConfig,
        [param.name]: value,
      },
      { shouldDirty: true },
    );
  };

  return (
    <div className="grid gap-2">
      <div className="flex items-center gap-1">
        <Label className="text-sm">
          {param.name}
          {param.required && <span className="text-destructive">*</span>}
        </Label>
        {param.description && (
          <TooltipWrapper content={param.description}>
            <button
              type="button"
              className="inline-flex cursor-help"
              tabIndex={0}
            >
              <Info className="size-3.5 text-muted-foreground" />
            </button>
          </TooltipWrapper>
        )}
      </div>
      <Input
        value={currentValue}
        onChange={(e) => handleChange(e.target.value)}
        placeholder={`Enter ${param.name}`}
      />
    </div>
  );
};

export default CommonMetricRuleDetails;
