import { useState, useMemo, useCallback, useEffect, useRef } from "react";
import { debounce } from "lodash";
import useDatasetItemUpdateMutation from "@/api/datasets/useDatasetItemUpdateMutation";

interface UseAutosaveParams {
  datasetId: string;
  datasetItemId?: string;
  debounceMs?: number;
}

interface UseAutosaveReturn {
  handleAutosave: (data: Record<string, unknown>) => void;
  isAutoSaving: boolean;
  lastSavedAt: Date | null;
  hasError: boolean;
  cancelPendingSave: () => void;
  flushPendingSave: () => void;
  resetSaveState: () => void;
}

export const useAutosave = ({
  datasetId,
  datasetItemId,
  debounceMs = 1000,
}: UseAutosaveParams): UseAutosaveReturn => {
  const [isAutoSaving, setIsAutoSaving] = useState(false);
  const [lastSavedAt, setLastSavedAt] = useState<Date | null>(null);
  const [hasError, setHasError] = useState(false);
  const updateMutation = useDatasetItemUpdateMutation();
  const debouncedSaveRef = useRef<ReturnType<typeof debounce>>();

  const performSave = useCallback(
    (data: Record<string, unknown>) => {
      if (!datasetItemId) return;

      setIsAutoSaving(true);
      setHasError(false); // Reset error on new save attempt

      updateMutation.mutate(
        {
          datasetId,
          itemId: datasetItemId,
          item: { data },
        },
        {
          onSuccess: () => {
            setIsAutoSaving(false);
            setLastSavedAt(new Date());
          },
          onError: () => {
            setIsAutoSaving(false);
            setHasError(true);
          },
        },
      );
    },
    [datasetId, datasetItemId, updateMutation],
  );

  const debouncedSave = useMemo(() => {
    const debounced = debounce(performSave, debounceMs);
    debouncedSaveRef.current = debounced;
    return debounced;
  }, [performSave, debounceMs]);

  const handleAutosave = useCallback(
    (data: Record<string, unknown>) => {
      debouncedSave(data);
    },
    [debouncedSave],
  );

  const cancelPendingSave = useCallback(() => {
    debouncedSaveRef.current?.cancel();
  }, []);

  const flushPendingSave = useCallback(() => {
    if (debouncedSaveRef.current?.flush) {
      debouncedSaveRef.current.flush();
    }
  }, []);

  const resetSaveState = useCallback(() => {
    setLastSavedAt(null);
    setHasError(false);
  }, []);

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      debouncedSaveRef.current?.cancel();
    };
  }, []);

  return {
    handleAutosave,
    isAutoSaving,
    lastSavedAt,
    hasError,
    cancelPendingSave,
    flushPendingSave,
    resetSaveState,
  };
};
