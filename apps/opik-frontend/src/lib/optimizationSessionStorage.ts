import { useCallback } from "react";

const LAST_OPTIMIZATION_RUN_KEY = "opik_last_optimization_run_id";

export const useLastOptimizationRun = () => {
  const getLastSessionRunId = useCallback(() => {
    return sessionStorage.getItem(LAST_OPTIMIZATION_RUN_KEY);
  }, []);

  const setLastSessionRunId = useCallback((id: string) => {
    sessionStorage.setItem(LAST_OPTIMIZATION_RUN_KEY, id);
  }, []);

  const clearLastSessionRunId = useCallback(() => {
    sessionStorage.removeItem(LAST_OPTIMIZATION_RUN_KEY);
  }, []);

  return {
    getLastSessionRunId,
    setLastSessionRunId,
    clearLastSessionRunId,
  };
};
