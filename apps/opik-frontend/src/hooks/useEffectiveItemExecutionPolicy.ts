import { useMemo } from "react";
import { useEditedDatasetItemById } from "@/store/TestSuiteDraftStore";
import { ExecutionPolicy } from "@/types/test-suites";

export const useEffectiveItemExecutionPolicy = (
  itemId: string,
  serverPolicy?: ExecutionPolicy,
): ExecutionPolicy | null => {
  const editedItem = useEditedDatasetItemById(itemId);

  return useMemo(() => {
    const hasEditedPolicy =
      editedItem != null && "execution_policy" in editedItem;
    if (hasEditedPolicy) return editedItem.execution_policy ?? null;
    return serverPolicy ?? null;
  }, [editedItem, serverPolicy]);
};
