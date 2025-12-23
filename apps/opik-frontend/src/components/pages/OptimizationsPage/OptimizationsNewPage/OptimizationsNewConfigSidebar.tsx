import React from "react";
import { UseFormReturn } from "react-hook-form";
import {
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import { OptimizationConfigFormType } from "@/components/pages-shared/optimizations/OptimizationConfigForm/schema";
import {
  OPTIMIZER_TYPE,
  METRIC_TYPE,
  OptimizerParameters,
  MetricParameters,
} from "@/types/optimizations";
import DatasetSelectBox from "@/components/pages-shared/llm/DatasetSelectBox/DatasetSelectBox";
import AlgorithmConfigs from "@/components/pages-shared/optimizations/AlgorithmSettings/AlgorithmConfigs";
import MetricConfigs from "@/components/pages-shared/optimizations/MetricSettings/MetricConfigs";
import {
  OPTIMIZER_OPTIONS,
  OPTIMIZATION_METRIC_OPTIONS,
} from "@/constants/optimizations";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import DatasetSamplePreview from "./DatasetSamplePreview";

type OptimizationsNewConfigSidebarProps = {
  form: UseFormReturn<OptimizationConfigFormType>;
  workspaceName: string;
  optimizerType: OPTIMIZER_TYPE;
  metricType: METRIC_TYPE;
  datasetSample: Record<string, unknown> | null;
  datasetVariables: string[];
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
  workspaceName,
  optimizerType,
  metricType,
  datasetSample,
  datasetVariables,
  onDatasetChange,
  onOptimizerTypeChange,
  onOptimizerParamsChange,
  onMetricTypeChange,
  onMetricParamsChange,
  getFirstMetricParamsError,
}) => {
  return (
    <div className="w-[500px] shrink-0 space-y-6">
      <FormField
        control={form.control}
        name="optimizerType"
        render={({ field }) => (
          <FormItem>
            <FormLabel className="comet-body-s-accented flex items-center gap-1">
              Algorithm
              <ExplainerIcon
                {...EXPLAINERS_MAP[EXPLAINER_ID.whats_the_algorithm_section]}
              />
            </FormLabel>
            <FormControl>
              <div className="flex items-center gap-2">
                <SelectBox
                  value={field.value}
                  onChange={onOptimizerTypeChange}
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

      <div className="space-y-3">
        <FormField
          control={form.control}
          name="datasetId"
          render={({ field }) => (
            <FormItem>
              <FormLabel className="comet-body-s-accented flex items-center gap-1">
                Dataset
                <ExplainerIcon
                  {...EXPLAINERS_MAP[EXPLAINER_ID.whats_the_dataset_section]}
                />
              </FormLabel>
              <FormControl>
                <DatasetSelectBox
                  value={field.value}
                  onChange={onDatasetChange}
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
                  onChange={onMetricTypeChange}
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
                onChange={onMetricParamsChange}
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
  );
};

export default OptimizationsNewConfigSidebar;
