import { useEffect, useRef } from "react";
import useLocalStorageState from "use-local-storage-state";
import useDatasetById from "@/api/datasets/useDatasetById";
import { PLAYGROUND_SELECTED_DATASET_KEY } from "@/constants/llm";

/**
 * Hook that manages dataset ID from localStorage with automatic validation.
 * Clears invalid dataset IDs automatically on mount.
 */
export const useValidatedDatasetId = () => {
  const [datasetId, setDatasetId] = useLocalStorageState<string | null>(
    PLAYGROUND_SELECTED_DATASET_KEY,
    {
      defaultValue: null,
    },
  );

  // Track if we've already cleared this ID to prevent loops
  const clearedIdRef = useRef<string | null>(null);

  // Validate dataset exists on mount and when window regains focus
  const { isError: isDatasetError } = useDatasetById(
    { datasetId: datasetId || "" },
    {
      enabled: !!datasetId,
      retry: false,
    },
  );

  // Clear invalid dataset ID
  useEffect(() => {
    if (isDatasetError && datasetId && clearedIdRef.current !== datasetId) {
      clearedIdRef.current = datasetId;
      setDatasetId(null);
    }
  }, [isDatasetError, datasetId, setDatasetId]);

  return [datasetId, setDatasetId] as const;
};
