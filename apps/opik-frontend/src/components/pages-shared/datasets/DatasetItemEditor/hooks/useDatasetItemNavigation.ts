import { useCallback, useMemo } from "react";
import findIndex from "lodash/findIndex";
import { DatasetItem } from "@/types/datasets";

interface UseDatasetItemNavigationParams {
  activeRowId: string;
  rows: DatasetItem[];
  setActiveRowId: (id: string) => void;
  checkUnsavedChanges?: (action: () => void) => void;
  onBeforeNavigate?: () => void;
}

interface HorizontalNavigationConfig {
  hasPrevious: boolean;
  hasNext: boolean;
  onChange: (shift: 1 | -1) => void;
}

interface UseDatasetItemNavigationReturn {
  horizontalNavigation: HorizontalNavigationConfig;
}

export const useDatasetItemNavigation = ({
  activeRowId,
  rows,
  setActiveRowId,
  checkUnsavedChanges,
  onBeforeNavigate,
}: UseDatasetItemNavigationParams): UseDatasetItemNavigationReturn => {
  const rowIndex = useMemo(
    () => findIndex(rows, (row) => activeRowId === row.id),
    [activeRowId, rows],
  );

  const hasNext = rowIndex >= 0 ? rowIndex < rows.length - 1 : false;
  const hasPrevious = rowIndex >= 0 ? rowIndex > 0 : false;

  const handleRowChange = useCallback(
    (shift: number) => {
      setActiveRowId(rows[rowIndex + shift]?.id ?? "");
    },
    [rowIndex, rows, setActiveRowId],
  );

  const handleNavigate = useCallback(
    (shift: 1 | -1) => {
      onBeforeNavigate?.();

      if (checkUnsavedChanges) {
        checkUnsavedChanges(() => handleRowChange(shift));
      } else {
        handleRowChange(shift);
      }
    },
    [checkUnsavedChanges, handleRowChange, onBeforeNavigate],
  );

  const horizontalNavigation: HorizontalNavigationConfig = useMemo(
    () => ({
      hasPrevious,
      hasNext,
      onChange: handleNavigate,
    }),
    [hasPrevious, hasNext, handleNavigate],
  );

  return {
    horizontalNavigation,
  };
};
