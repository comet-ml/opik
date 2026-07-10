import { useOptimizationExperiments } from "./useOptimizationExperiments";
import { useOptimizationTableState } from "./useOptimizationTableState";
import { useTrialSidebarState } from "./TrialSidebar/useTrialSidebarState";

export {
  MAX_EXPERIMENTS_LOADED,
  CANDIDATE_SORT_FIELD_MAP,
  sortCandidates,
} from "@/lib/optimizations";

export const useOptimizationData = () => {
  const experimentsData = useOptimizationExperiments();

  const { optimization, optimizationId, candidates } = experimentsData;

  const title = optimization?.name || optimizationId;

  const trialSidebar = useTrialSidebarState();

  const tableState = useOptimizationTableState({
    candidates,
    onRowClick: trialSidebar.openTrial,
  });

  return {
    ...experimentsData,
    ...tableState,
    trialSidebar,
    title,
  };
};
