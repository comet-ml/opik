import { useState, useMemo } from "react";

interface UseDatasetItemFormStateParams {
  datasetItemId?: string;
}

interface UseDatasetItemFormStateReturn {
  hasUnsavedChanges: boolean;
  setHasUnsavedChanges: (value: boolean) => void;
  resetKey: number;
  setResetKey: React.Dispatch<React.SetStateAction<number>>;
  formId: string;
}

export const useDatasetItemFormState = ({
  datasetItemId,
}: UseDatasetItemFormStateParams): UseDatasetItemFormStateReturn => {
  const [hasUnsavedChanges, setHasUnsavedChanges] = useState(false);
  const [resetKey, setResetKey] = useState(0);

  const formId = useMemo(
    () => `dataset-item-editor-form-${datasetItemId || "new"}`,
    [datasetItemId],
  );

  return {
    hasUnsavedChanges,
    setHasUnsavedChanges,
    resetKey,
    setResetKey,
    formId,
  };
};
