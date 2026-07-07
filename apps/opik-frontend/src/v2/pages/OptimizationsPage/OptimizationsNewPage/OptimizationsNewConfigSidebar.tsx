import React from "react";
import { UseFormReturn } from "react-hook-form";
import { ExternalLink } from "lucide-react";
import {
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/ui/form";
import { Button } from "@/ui/button";
import { Label } from "@/ui/label";
import { Separator } from "@/ui/separator";
import { SelectItem } from "@/ui/select";
import SelectBox from "@/shared/SelectBox/SelectBox";
import { OptimizationConfigFormType } from "@/v2/pages-shared/optimizations/OptimizationConfigForm/schema";
import {
  OPTIMIZER_TYPE,
  METRIC_TYPE,
  OptimizerParameters,
  MetricParameters,
  MetricParamErrors,
} from "@/types/optimizations";
import DatasetSelectBox from "@/v2/pages-shared/experiments/DatasetSelectBox/DatasetSelectBox";
import AlgorithmConfigs from "@/v2/pages-shared/optimizations/AlgorithmSettings/AlgorithmConfigs";
import MetricConfigs from "@/v2/pages-shared/optimizations/MetricSettings/MetricConfigs";
import {
  OPTIMIZER_OPTIONS,
  OPTIMIZATION_METRIC_OPTIONS,
  ALGORITHM_ICON_MAP,
  METRIC_ICON_MAP,
  type IconConfig,
} from "@/constants/optimizations";
import { buildDocsUrl } from "@/v2/lib/utils";
import DatasetSamplePreview from "./DatasetSamplePreview";

const renderIconLabel = (cfg: IconConfig | undefined, label: string) => {
  const Icon = cfg?.icon;
  return (
    <div className="flex min-w-0 items-center gap-1.5">
      {Icon && (
        <Icon className="size-3.5 shrink-0" style={{ color: cfg?.color }} />
      )}
      <span className="truncate">{label}</span>
    </div>
  );
};

type OptimizationsNewConfigSidebarProps = {
  form: UseFormReturn<OptimizationConfigFormType>;
  projectId?: string | null;
  optimizerType: OPTIMIZER_TYPE;
  metricType: METRIC_TYPE;
  datasetSample: Record<string, unknown> | null;
  datasetVariables: string[];
  isPreparingDataset: boolean;
  onDatasetChange: (id: string | null) => void;
  onOptimizerTypeChange: (type: OPTIMIZER_TYPE) => void;
  onOptimizerParamsChange: (params: Partial<OptimizerParameters>) => void;
  onMetricTypeChange: (type: METRIC_TYPE) => void;
  onMetricParamsChange: (params: Partial<MetricParameters>) => void;
};

const OptimizationsNewConfigSidebar: React.FC<
  OptimizationsNewConfigSidebarProps
> = ({
  form,
  projectId,
  optimizerType,
  metricType,
  datasetSample,
  datasetVariables,
  isPreparingDataset,
  onDatasetChange,
  onOptimizerTypeChange,
  onOptimizerParamsChange,
  onMetricTypeChange,
  onMetricParamsChange,
}) => {
  return (
    <div className="w-full space-y-3 xl:w-2/5 xl:min-w-[320px]">
      <FormField
        control={form.control}
        name="optimizerType"
        render={({ field }) => (
          <FormItem className="gap-1.5">
            <FormLabel className="comet-body-s-accented">Algorithm</FormLabel>
            <FormControl>
              <div className="flex h-8 items-center rounded-md border border-input bg-background transition-shadow focus-within:border-primary hover:shadow-sm">
                <SelectBox
                  variant="ghost"
                  value={field.value}
                  onChange={onOptimizerTypeChange}
                  options={OPTIMIZER_OPTIONS}
                  placeholder="Select algorithm"
                  className="h-full flex-1 hover:shadow-none"
                  renderTrigger={(value) => {
                    const option = OPTIMIZER_OPTIONS.find(
                      (o) => o.value === value,
                    );
                    if (!option) {
                      return (
                        <span className="text-light-slate">
                          Select algorithm
                        </span>
                      );
                    }
                    return renderIconLabel(
                      ALGORITHM_ICON_MAP[value as OPTIMIZER_TYPE],
                      option.label,
                    );
                  }}
                  renderOption={(option) => (
                    <SelectItem
                      key={option.value}
                      value={option.value}
                      description={option.description}
                      size="sm"
                    >
                      {renderIconLabel(
                        ALGORITHM_ICON_MAP[option.value as OPTIMIZER_TYPE],
                        option.label,
                      )}
                    </SelectItem>
                  )}
                />
                <Separator orientation="vertical" className="h-full" />
                <FormField
                  control={form.control}
                  name="optimizerParams"
                  render={({ field: paramsField }) => (
                    <AlgorithmConfigs
                      size="icon-sm"
                      variant="ghost"
                      className="shrink-0 hover:shadow-none"
                      optimizerType={optimizerType}
                      configs={
                        paramsField.value as Partial<OptimizerParameters>
                      }
                      onChange={onOptimizerParamsChange}
                      promptModel={form.watch("modelName")}
                    />
                  )}
                />
              </div>
            </FormControl>
            <FormMessage />
          </FormItem>
        )}
      />

      <div className="space-y-2">
        <FormField
          control={form.control}
          name="datasetId"
          render={({ field }) => (
            <FormItem className="gap-1.5">
              <FormLabel className="comet-body-s-accented">
                Item source
              </FormLabel>
              <FormControl>
                <DatasetSelectBox
                  value={field.value}
                  onValueChange={(id) => onDatasetChange(id)}
                  projectId={projectId}
                  placeholder={
                    isPreparingDataset
                      ? "Preparing dataset..."
                      : "Select item source"
                  }
                  disabled={isPreparingDataset}
                  className="h-8 w-full"
                  showIcon
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

      <div className="py-1">
        <Separator />
      </div>

      <div className="space-y-3">
        <FormField
          control={form.control}
          name="metricType"
          render={({ field }) => (
            <FormItem className="gap-1.5">
              <div className="flex items-center justify-between gap-2">
                <FormLabel className="comet-body-s-accented">Metric</FormLabel>
                <Button
                  variant="ghost"
                  size="2xs"
                  className="comet-body-xs shrink-0 text-muted-slate"
                  asChild
                >
                  <a
                    href={buildDocsUrl(
                      "/development/optimization-runs/optimization_studio",
                    )}
                    target="_blank"
                    rel="noreferrer"
                  >
                    Go to docs
                    <ExternalLink className="ml-1 size-3.5 shrink-0" />
                  </a>
                </Button>
              </div>
              <FormControl>
                <SelectBox
                  value={field.value}
                  onChange={onMetricTypeChange}
                  options={OPTIMIZATION_METRIC_OPTIONS}
                  placeholder="Select metric"
                  className="h-8 w-full"
                  renderTrigger={(value) => {
                    const option = OPTIMIZATION_METRIC_OPTIONS.find(
                      (o) => o.value === value,
                    );
                    if (!option) {
                      return (
                        <span className="text-light-slate">Select metric</span>
                      );
                    }
                    return renderIconLabel(
                      METRIC_ICON_MAP[value as METRIC_TYPE],
                      option.label,
                    );
                  }}
                  renderOption={(option) => (
                    <SelectItem
                      key={option.value}
                      value={option.value}
                      description={option.description}
                      size="sm"
                    >
                      {renderIconLabel(
                        METRIC_ICON_MAP[option.value as METRIC_TYPE],
                        option.label,
                      )}
                    </SelectItem>
                  )}
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
            <FormItem className="gap-1.5">
              {/* Plain Label (not FormLabel): this is a section title for a
                  group of sub-fields that surface their own errors, so it must
                  not turn red when metricParams has a validation error. */}
              <Label className="comet-body-s-accented">Metric settings</Label>
              <div className="rounded-md border border-border bg-soft-background px-3 py-2">
                <MetricConfigs
                  inline
                  metricType={metricType}
                  configs={paramsField.value as Partial<MetricParameters>}
                  onChange={onMetricParamsChange}
                  datasetVariables={datasetVariables}
                  errors={
                    form.formState.errors.metricParams as
                      | MetricParamErrors
                      | undefined
                  }
                />
              </div>
            </FormItem>
          )}
        />
      </div>
    </div>
  );
};

export default OptimizationsNewConfigSidebar;
