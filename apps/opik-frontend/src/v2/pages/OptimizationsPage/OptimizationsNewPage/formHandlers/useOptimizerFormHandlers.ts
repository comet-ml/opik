import { useCallback } from "react";
import { UseFormReturn } from "react-hook-form";

import { OPTIMIZER_TYPE, OptimizerParameters } from "@/types/optimizations";
import { getDefaultOptimizerConfig } from "@/lib/optimizations";
import { OptimizationConfigFormType } from "@/v2/pages-shared/optimizations/OptimizationConfigForm/schema";

export const useOptimizerFormHandlers = (
  form: UseFormReturn<OptimizationConfigFormType>,
) => {
  const handleOptimizerTypeChange = useCallback(
    (newOptimizerType: OPTIMIZER_TYPE) => {
      form.setValue("optimizerType", newOptimizerType, {
        shouldDirty: true,
      });
      form.setValue(
        "optimizerParams",
        getDefaultOptimizerConfig(newOptimizerType),
        { shouldDirty: true },
      );
    },
    [form],
  );

  const handleOptimizerParamsChange = useCallback(
    (newParams: Partial<OptimizerParameters>) => {
      form.setValue("optimizerParams", newParams, { shouldDirty: true });
    },
    [form],
  );

  return { handleOptimizerTypeChange, handleOptimizerParamsChange };
};
