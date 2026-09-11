import { useMemo } from "react";
import difference from "lodash/difference";

import { ColumnData } from "@/types/shared";

interface UseColumnsCountParams<TColumnData> {
  columns: ColumnData<TColumnData>[];
  selectedColumns: string[];
  sections?: { columns: ColumnData<TColumnData>[] }[];
  excludeFromSelectAll?: string[];
}

export const useColumnsCount = <TColumnData>({
  columns,
  selectedColumns,
  sections,
  excludeFromSelectAll = [],
}: UseColumnsCountParams<TColumnData>) => {
  const allColumnsIds = useMemo(
    () =>
      [{ columns }, ...(sections || [])].flatMap(({ columns: group = [] }) =>
        group.map((col) => col.id),
      ),
    [columns, sections],
  );

  const selectAllColumnsIds = useMemo(
    () => difference(allColumnsIds, excludeFromSelectAll),
    [allColumnsIds, excludeFromSelectAll],
  );

  const allColumnsSelected = useMemo(
    () =>
      selectAllColumnsIds.length > 0 &&
      selectAllColumnsIds.every((id) => selectedColumns.includes(id)),
    [selectedColumns, selectAllColumnsIds],
  );

  const selectedCount = selectedColumns.filter((id) =>
    selectAllColumnsIds.includes(id),
  ).length;

  const totalCount = selectAllColumnsIds.length;

  return {
    allColumnsIds,
    selectAllColumnsIds,
    allColumnsSelected,
    selectedCount,
    totalCount,
  };
};
