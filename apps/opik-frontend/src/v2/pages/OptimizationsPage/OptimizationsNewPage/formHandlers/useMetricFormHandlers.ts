import { useCallback } from "react";
import { UseFormReturn } from "react-hook-form";

import { METRIC_TYPE, MetricParameters } from "@/types/optimizations";
import { getDefaultMetricConfig } from "@/lib/optimizations";
import { OptimizationConfigFormType } from "@/v2/pages-shared/optimizations/OptimizationConfigForm/schema";

/** Metric section: type selection, params editing, and error surfacing. */
export const useMetricFormHandlers = (
  form: UseFormReturn<OptimizationConfigFormType>,
) => {
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

  return {
    handleMetricTypeChange,
    handleMetricParamsChange,
    getFirstMetricParamsError,
  };
};
