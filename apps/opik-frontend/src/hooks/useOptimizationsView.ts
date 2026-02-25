import { useMemo } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import get from "lodash/get";
import isObject from "lodash/isObject";

import useGroupedOptimizationsList, {
  GroupedOptimization,
} from "@/hooks/useGroupedOptimizationsList";
import useOptimizationsList from "@/api/optimizations/useOptimizationsList";
import { usePermissions } from "@/contexts/PermissionsContext";
import {
  COLUMN_ID_ID,
  COLUMN_NAME_ID,
  COLUMN_SELECT_ID,
  COLUMN_TYPE,
  ColumnData,
} from "@/types/shared";
import { Optimization } from "@/types/optimizations";
import { ColumnPinningState, RowSelectionState } from "@tanstack/react-table";
import { DEFAULT_GROUPS_PER_PAGE, GROUPING_COLUMN } from "@/constants/groups";
import { checkIsGroupRowType } from "@/lib/groups";
import ResourceCell from "@/components/shared/DataTableCells/ResourceCell";
import IdCell from "@/components/shared/DataTableCells/IdCell";
import FeedbackScoreTagCell from "@/components/shared/DataTableCells/FeedbackScoreTagCell";
import OptimizationStatusCell from "@/components/pages/OptimizationsPage/OptimizationStatusCell";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";
import { formatDate } from "@/lib/date";
import { toString } from "@/lib/utils";
import { getFeedbackScore } from "@/lib/feedback-scores";
import { OPTIMIZATION_OPTIMIZER_KEY } from "@/constants/experiments";
import { getOptimizerLabel } from "@/lib/optimizations";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import { convertColumnDataToColumn } from "@/lib/table";
import {
  generateActionsColumDef,
  generateDataRowCellDef,
  generateGroupedRowCellDef,
  getSharedShiftCheckboxClickHandler,
} from "@/components/shared/DataTable/utils";
import OptimizationRowActionsCell from "@/components/pages/OptimizationsPage/OptimizationRowActionsCell";

const DEFAULT_PAGE_SIZE = 100;

export const DEFAULT_COLUMNS: ColumnData<Optimization>[] = [
  {
    id: COLUMN_ID_ID,
    label: "ID",
    type: COLUMN_TYPE.string,
    cell: IdCell as never,
  },
  {
    id: "created_at",
    label: "Created",
    type: COLUMN_TYPE.time,
    accessorFn: (row) => formatDate(row.created_at),
  },
  {
    id: "created_by",
    label: "Created by",
    type: COLUMN_TYPE.string,
  },
  {
    id: "num_trials",
    label: "Trial count",
    type: COLUMN_TYPE.number,
  },
  {
    id: "optimizer",
    label: "Optimizer",
    type: COLUMN_TYPE.string,
    size: 200,
    accessorFn: (row) => {
      const metadataVal = get(row.metadata ?? {}, OPTIMIZATION_OPTIMIZER_KEY);
      if (metadataVal) {
        return isObject(metadataVal)
          ? JSON.stringify(metadataVal, null, 2)
          : toString(metadataVal);
      }

      const studioVal = row.studio_config?.optimizer?.type;
      return studioVal ? getOptimizerLabel(studioVal) : "-";
    },
    explainer: EXPLAINERS_MAP[EXPLAINER_ID.whats_the_optimizer],
  },
  {
    id: "objective_name",
    label: "Best score",
    type: COLUMN_TYPE.numberDictionary,
    accessorFn: (row) =>
      getFeedbackScore(row.feedback_scores ?? [], row.objective_name),
    cell: FeedbackScoreTagCell as never,
    explainer: EXPLAINERS_MAP[EXPLAINER_ID.whats_the_best_score],
  },
  {
    id: "status",
    label: "Status",
    type: COLUMN_TYPE.string,
    cell: OptimizationStatusCell as never,
  },
];

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
  columnsOrder: string[];
  selectedColumns: string[];
  rowSelection: RowSelectionState;
};

export const useOptimizationsView = ({
  workspaceName,
  datasetId,
  search,
  page,
  groupLimit,
  columnsOrder,
  selectedColumns,
  rowSelection,
}: UseOptimizationsViewParams) => {
  const {
    permissions: { canViewDatasets },
  } = usePermissions();

  const checkboxClickHandler = useMemo(() => {
    return getSharedShiftCheckboxClickHandler();
  }, []);

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
      datasetId: datasetId || "",
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

  const nameColumn = useMemo(
    () =>
      generateDataRowCellDef(
        {
          id: COLUMN_NAME_ID,
          label: "Name",
          type: COLUMN_TYPE.string,
          cell: ResourceCell as never,
          customMeta: {
            nameKey: "name",
            idKey: "dataset_id",
            resource: RESOURCE_TYPE.optimization,
            getSearch: (data: Optimization) => ({
              optimizations: [data.id],
            }),
          },
          headerCheckbox: true,
          size: 200,
        },
        checkboxClickHandler,
      ),
    [checkboxClickHandler],
  );

  const groupingColumn = useMemo(
    () =>
      generateGroupedRowCellDef<GroupedOptimization, unknown>(
        {
          id: GROUPING_COLUMN,
          label: "Dataset",
          type: COLUMN_TYPE.string,
          cell: ResourceCell as never,
          customMeta: {
            nameKey: "dataset_name",
            idKey: "dataset_id",
            resource: RESOURCE_TYPE.dataset,
          },
        },
        checkboxClickHandler,
      ),
    [checkboxClickHandler],
  );

  const defaultColumns = useMemo(() => {
    return convertColumnDataToColumn(DEFAULT_COLUMNS, {
      columnsOrder,
      selectedColumns,
    });
  }, [columnsOrder, selectedColumns]);

  const actionsColumn = useMemo(() => {
    return generateActionsColumDef({
      cell: OptimizationRowActionsCell,
    });
  }, []);

  const columns = useMemo(() => {
    if (canViewDatasets) {
      return [nameColumn, groupingColumn, ...defaultColumns, actionsColumn];
    }

    return [nameColumn, ...defaultColumns, actionsColumn];
  }, [
    canViewDatasets,
    nameColumn,
    groupingColumn,
    defaultColumns,
    actionsColumn,
  ]);

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
    columns,
    columnPinning,
    pageSize,
    checkboxClickHandler,
    refetch,
  };
};
