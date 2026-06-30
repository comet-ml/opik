import React from "react";
import { UseFormReturn } from "react-hook-form";
import {
  Braces,
  Code,
  Dna,
  Equal,
  ExternalLink,
  GitBranch,
  Network,
  Sigma,
  Sparkles,
  SpellCheck,
  type LucideIcon,
} from "lucide-react";
import {
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/ui/form";
import { Button } from "@/ui/button";
import { Separator } from "@/ui/separator";
import { SelectItem } from "@/ui/select";
import SelectBox from "@/shared/SelectBox/SelectBox";
import { OptimizationConfigFormType } from "@/v2/pages-shared/optimizations/OptimizationConfigForm/schema";
import {
  OPTIMIZER_TYPE,
  METRIC_TYPE,
  OptimizerParameters,
  MetricParameters,
} from "@/types/optimizations";
import DatasetSelectBox from "@/v2/pages-shared/experiments/DatasetSelectBox/DatasetSelectBox";
import AlgorithmConfigs from "@/v2/pages-shared/optimizations/AlgorithmSettings/AlgorithmConfigs";
import MetricConfigs from "@/v2/pages-shared/optimizations/MetricSettings/MetricConfigs";
import { FormFieldCard } from "@/v2/pages-shared/llm/FormFieldCard";
import {
  OPTIMIZER_OPTIONS,
  OPTIMIZATION_METRIC_OPTIONS,
} from "@/constants/optimizations";
import { buildDocsUrl } from "@/v2/lib/utils";
import DatasetSamplePreview from "./DatasetSamplePreview";

type IconConfig = { icon: LucideIcon; color: string };

const ALGORITHM_ICON_MAP: Partial<Record<OPTIMIZER_TYPE, IconConfig>> = {
  [OPTIMIZER_TYPE.GEPA]: { icon: Dna, color: "var(--optimizer-icon-gepa)" },
  [OPTIMIZER_TYPE.HIERARCHICAL_REFLECTIVE]: {
    icon: Network,
    color: "var(--optimizer-icon-hierarchical)",
  },
  [OPTIMIZER_TYPE.EVOLUTIONARY]: {
    icon: GitBranch,
    color: "var(--optimizer-icon-evolutionary)",
  },
};

const METRIC_ICON_MAP: Record<METRIC_TYPE, IconConfig> = {
  [METRIC_TYPE.EQUALS]: { icon: Equal, color: "var(--metric-icon-equals)" },
  [METRIC_TYPE.JSON_SCHEMA_VALIDATOR]: {
    icon: Braces,
    color: "var(--metric-icon-json-schema)",
  },
  [METRIC_TYPE.G_EVAL]: { icon: Sparkles, color: "var(--metric-icon-g-eval)" },
  [METRIC_TYPE.LEVENSHTEIN]: {
    icon: SpellCheck,
    color: "var(--metric-icon-levenshtein)",
  },
  [METRIC_TYPE.NUMERICAL_SIMILARITY]: {
    icon: Sigma,
    color: "var(--metric-icon-numerical)",
  },
  [METRIC_TYPE.CODE]: { icon: Code, color: "var(--metric-icon-code)" },
};

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
  getFirstMetricParamsError: () => string | null;
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
  getFirstMetricParamsError,
}) => {
  return (
    <div className="w-full space-y-3 xl:w-[500px] xl:shrink-0">
      <FormField
        control={form.control}
        name="optimizerType"
        render={({ field }) => (
          <FormItem className="space-y-1">
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
            <FormItem className="space-y-1">
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
            <FormItem className="space-y-1">
              <div className="flex items-center justify-between gap-2">
                <FormLabel className="comet-body-s-accented">Metric</FormLabel>
                <Button
                  variant="link"
                  size="sm"
                  className="comet-body-s h-auto shrink-0 p-0 text-muted-slate"
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
                    <ExternalLink className="ml-1 size-3.5" />
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
            <FormFieldCard title="Metric settings" bodyClassName="px-3">
              <MetricConfigs
                inline
                metricType={metricType}
                configs={paramsField.value as Partial<MetricParameters>}
                onChange={onMetricParamsChange}
                datasetVariables={datasetVariables}
              />
              {form.formState.errors.metricParams && (
                <p className="comet-body-s mt-2 text-destructive">
                  {getFirstMetricParamsError()}
                </p>
              )}
            </FormFieldCard>
          )}
        />
      </div>
    </div>
  );
};

export default OptimizationsNewConfigSidebar;
