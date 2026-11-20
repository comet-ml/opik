import { useMemo } from "react";

import { DatasetItem, DatasetItemColumn } from "@/types/datasets";
import { DYNAMIC_COLUMN_TYPE } from "@/types/shared";
import { useAddedItems, useEditedItems } from "@/store/TestSuiteDraftStore";

const getValueType = (value: unknown): DYNAMIC_COLUMN_TYPE => {
  if (value === null || value === undefined) return DYNAMIC_COLUMN_TYPE.null;
  if (Array.isArray(value)) return DYNAMIC_COLUMN_TYPE.array;
  switch (typeof value) {
    case "number":
      return DYNAMIC_COLUMN_TYPE.number;
    case "boolean":
      return DYNAMIC_COLUMN_TYPE.boolean;
    case "object":
      return DYNAMIC_COLUMN_TYPE.object;
    default:
      return DYNAMIC_COLUMN_TYPE.string;
  }
};

// The API only reports columns of the saved version, so fields introduced in
// unsaved draft records would otherwise stay invisible until the next save.
export const mergeColumnsWithDraftData = (
  columns: DatasetItemColumn[],
  draftData: Array<object | undefined>,
): DatasetItemColumn[] => {
  const known = new Set(columns.map((column) => column.name));
  const extra = new Map<string, Set<DYNAMIC_COLUMN_TYPE>>();

  draftData.forEach((data) => {
    if (!data) return;
    Object.entries(data).forEach(([key, value]) => {
      if (known.has(key)) return;
      const types = extra.get(key) ?? new Set<DYNAMIC_COLUMN_TYPE>();
      types.add(getValueType(value));
      extra.set(key, types);
    });
  });

  if (extra.size === 0) return columns;

  return [
    ...columns,
    ...Array.from(extra, ([name, types]) => ({ name, types: [...types] })),
  ];
};

export const useColumnsWithDraftKeys = (
  columns: DatasetItemColumn[],
): DatasetItemColumn[] => {
  const addedItems = useAddedItems();
  const editedItems = useEditedItems();

  return useMemo(
    () =>
      mergeColumnsWithDraftData(columns, [
        ...Array.from(addedItems.values(), (item: DatasetItem) => item.data),
        ...Array.from(editedItems.values(), (item) => item.data),
      ]),
    [columns, addedItems, editedItems],
  );
};
