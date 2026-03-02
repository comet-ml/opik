import { useMemo } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { ColumnPinningState, RowSelectionState } from "@tanstack/react-table";
import useGroupedOptimizationsList from "@/hooks/useGroupedOptimizationsList";
import useOptimizationsList from "@/api/optimizations/useOptimizationsList";
import { usePermissions } from "@/contexts/PermissionsContext";
import { COLUMN_NAME_ID, COLUMN_SELECT_ID } from "@/types/shared";
import { Optimization } from "@/types/optimizations";
import { DEFAULT_GROUPS_PER_PAGE, GROUPING_COLUMN } from "@/constants/groups";
import { checkIsGroupRowType } from "@/lib/groups";

const DEFAULT_PAGE_SIZE = 100;

const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_NAME_ID, GROUPING_COLUMN],
  right: [],
};

const UNGROUPED_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID, COLUMN_NAME_ID],
  right: [],
};

type UseOptimizationsViewParams = {
  workspaceName: string;
  datasetId?: string;
  search?: string;
  page: number;
  groupLimit?: Record<string, number>;
  rowSelection: RowSelectionState;
};

export const useOptimizationsView = ({
  workspaceName,
  datasetId,
  search,
  page,
  groupLimit,
  rowSelection,
}: UseOptimizationsViewParams) => {
  const {
    permissions: { canViewDatasets },
  } = usePermissions();

  const {
    data: groupedData,
    isPending: isGroupedPending,
    isPlaceholderData: isGroupedPlaceholderData,
    isFetching: isGroupedFetching,
    refetch: refetchGrouped,
  } = useGroupedOptimizationsList(
    {
      workspaceName,
      groupLimit,
      datasetId: datasetId || "",
      search: search || "",
      page,
      size: DEFAULT_GROUPS_PER_PAGE,
      polling: true,
    },
    {
      enabled: canViewDatasets,
    },
  );

  const {
    data: ungroupedData,
    isPending: isUngroupedPending,
    isFetching: isUngroupedFetching,
    refetch: refetchUngrouped,
  } = useOptimizationsList(
    {
      workspaceName,
      datasetId: "",
      search: search || "",
      page,
      size: DEFAULT_PAGE_SIZE,
    },
    {
      enabled: !canViewDatasets,
      placeholderData: keepPreviousData,
    },
  );

  const optimizations = useMemo(
    () =>
      canViewDatasets
        ? groupedData?.content ?? []
        : ungroupedData?.content ?? [],
    [canViewDatasets, groupedData?.content, ungroupedData?.content],
  );

  const groupIds = useMemo(
    () => (canViewDatasets ? groupedData?.groupIds ?? [] : []),
    [canViewDatasets, groupedData?.groupIds],
  );

  const total = canViewDatasets
    ? groupedData?.total ?? 0
    : ungroupedData?.total ?? 0;

  const isPending = canViewDatasets ? isGroupedPending : isUngroupedPending;
  const isPlaceholderData = canViewDatasets ? isGroupedPlaceholderData : false;
  const isFetching = canViewDatasets ? isGroupedFetching : isUngroupedFetching;
  const refetch = canViewDatasets ? refetchGrouped : refetchUngrouped;

  const selectedRows: Array<Optimization> = useMemo(() => {
    return optimizations.filter((row) => {
      if (canViewDatasets) {
        return rowSelection[row.id] && !checkIsGroupRowType(row.id);
      }
      return rowSelection[row.id];
    });
  }, [rowSelection, optimizations, canViewDatasets]);

  const columnPinning = canViewDatasets
    ? DEFAULT_COLUMN_PINNING
    : UNGROUPED_COLUMN_PINNING;

  const pageSize = canViewDatasets
    ? DEFAULT_GROUPS_PER_PAGE
    : DEFAULT_PAGE_SIZE;

  return {
    optimizations,
    groupIds,
    total,
    selectedRows,
    isPending,
    isPlaceholderData,
    isFetching,
    columnPinning,
    pageSize,
    refetch,
  };
};
