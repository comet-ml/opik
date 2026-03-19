import { useMemo } from "react";
import { useDraftExecutionPolicy } from "@/store/EvaluationSuiteDraftStore";
import useDatasetVersionsList from "@/api/datasets/useDatasetVersionsList";
import {
  DEFAULT_EXECUTION_POLICY,
  ExecutionPolicy,
} from "@/types/evaluation-suites";

export const useEffectiveExecutionPolicy = (
  suiteId: string,
): ExecutionPolicy => {
  const draftPolicy = useDraftExecutionPolicy();
  const { data: versionsData } = useDatasetVersionsList({
    datasetId: suiteId,
    page: 1,
    size: 1,
  });

  return useMemo(() => {
    if (draftPolicy !== null) return draftPolicy;
    return (
      versionsData?.content?.[0]?.execution_policy ?? DEFAULT_EXECUTION_POLICY
    );
  }, [draftPolicy, versionsData]);
};
