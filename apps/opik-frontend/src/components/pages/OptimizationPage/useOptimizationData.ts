import { useOptimizationExperiments } from "./useOptimizationExperiments";
import { useOptimizationTableState } from "./useOptimizationTableState";

export {
  MAX_EXPERIMENTS_LOADED,
  CANDIDATE_SORT_FIELD_MAP,
  sortCandidates,
} from "@/lib/optimizations";

export const useOptimizationData = () => {
  const experimentsData = useOptimizationExperiments();

  const { workspaceName, optimizationId, optimization, candidates } =
    experimentsData;

  const title = optimization?.name || optimizationId;

  const tableState = useOptimizationTableState({
    candidates,
    workspaceName,
    optimizationId,
  });

  return {
    ...experimentsData,
    ...tableState,
    title,
  };
};
