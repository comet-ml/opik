import { useCallback } from "react";
import { UseFormReturn } from "react-hook-form";

import { OPTIMIZER_TYPE, OptimizerParameters } from "@/types/optimizations";
import { getDefaultOptimizerConfig } from "@/lib/optimizations";
import { OptimizationConfigFormType } from "@/v2/pages-shared/optimizations/OptimizationConfigForm/schema";

/** Optimizer (algorithm) section: type selection + params editing. */
export const useOptimizerFormHandlers = (
  form: UseFormReturn<OptimizationConfigFormType>,
) => {
  const handleOptimizerTypeChange = useCallback(
    (newOptimizerType: OPTIMIZER_TYPE) => {
      form.setValue("optimizerType", newOptimizerType, {
        shouldValidate: true,
      });
      form.setValue(
        "optimizerParams",
        getDefaultOptimizerConfig(newOptimizerType),
        { shouldValidate: true },
      );
    },
    [form],
  );

  const handleOptimizerParamsChange = useCallback(
    (newParams: Partial<OptimizerParameters>) => {
      form.setValue("optimizerParams", newParams);
    },
    [form],
  );

  return { handleOptimizerTypeChange, handleOptimizerParamsChange };
};
