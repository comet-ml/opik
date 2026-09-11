import { useCallback } from "react";
import { useNavigate } from "@tanstack/react-router";
import { UseFormReturn } from "react-hook-form";

import useAppStore, { useActiveProjectId } from "@/store/AppStore";
import useOptimizationCreateMutation from "@/api/optimizations/useOptimizationCreateMutation";
import { useLastOptimizationRun } from "@/lib/optimizationSessionStorage";
import { extractMetricNameFromCode } from "@/lib/optimizations";
import { OPTIMIZATION_STATUS, METRIC_TYPE } from "@/types/optimizations";
import { Dataset } from "@/types/datasets";
import {
  OptimizationConfigFormType,
  convertFormDataToStudioConfig,
} from "@/v2/pages-shared/optimizations/OptimizationConfigForm/schema";

type UseSubmitOptimizationParams = {
  form: UseFormReturn<OptimizationConfigFormType>;
  selectedDataset?: Dataset;
};

// Wrapped by RHF's `form.handleSubmit(...)`, which validates first and tracks
// `formState.isSubmitting` — so this hook neither re-validates nor owns a busy flag.
export const useSubmitOptimization = ({
  form,
  selectedDataset,
}: UseSubmitOptimizationParams) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const activeProjectId = useActiveProjectId();
  const navigate = useNavigate();
  const { setLastSessionRunId } = useLastOptimizationRun();
  const { mutateAsync: createOptimization } = useOptimizationCreateMutation();

  const submitOptimization = useCallback(async () => {
    const datasetNameValue = selectedDataset?.name || "";
    if (!datasetNameValue) return;

    const formData = form.getValues();
    const studioConfig = convertFormDataToStudioConfig(
      formData,
      datasetNameValue,
    );

    // For code metrics, the objective name is the metric class extracted from
    // the user's code; for everything else it's the metric type.
    const metricConfig = studioConfig.evaluation.metrics[0];
    let objectiveName: string = metricConfig.type;
    if (
      metricConfig.type === METRIC_TYPE.CODE &&
      metricConfig.parameters &&
      "code" in metricConfig.parameters
    ) {
      objectiveName = extractMetricNameFromCode(metricConfig.parameters.code);
    }

    const result = await createOptimization({
      optimization: {
        name: formData.name || undefined,
        studio_config: studioConfig,
        dataset_name: datasetNameValue,
        objective_name: objectiveName,
        status: OPTIMIZATION_STATUS.INITIALIZED,
        project_id: activeProjectId ?? undefined,
      },
    });

    if (result?.id) {
      setLastSessionRunId(result.id);
      navigate({
        to: "/$workspaceName/projects/$projectId/optimizations/$optimizationId",
        params: {
          workspaceName,
          projectId: activeProjectId!,
          optimizationId: result.id,
        },
      });
    }
  }, [
    form,
    selectedDataset,
    createOptimization,
    navigate,
    workspaceName,
    activeProjectId,
    setLastSessionRunId,
  ]);

  return { submitOptimization };
};
