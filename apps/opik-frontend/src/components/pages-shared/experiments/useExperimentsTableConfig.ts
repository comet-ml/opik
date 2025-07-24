import { useMemo } from "react";
import {
  ColumnSort,
  RowSelectionState,
  ColumnPinningState,
  ColumnDefTemplate,
  CellContext,
  OnChangeFn,
} from "@tanstack/react-table";
import useLocalStorageState from "use-local-storage-state";
import get from "lodash/get";

import { Groups } from "@/types/groups";
import {
  COLUMN_NAME_ID,
  ColumnData,
  DynamicColumn,
  COLUMN_TYPE,
} from "@/types/shared";
import { convertColumnDataToColumn, isColumnSortable } from "@/lib/table";
import {
  buildGroupFieldName,
  buildGroupFieldNameForMeta,
  checkIsGroupRowType,
} from "@/lib/groups";
import FeedbackScoreHeader from "@/components/shared/DataTableHeaders/FeedbackScoreHeader";
import FeedbackScoreCell from "@/components/shared/DataTableCells/FeedbackScoreCell";
import ResourceCell from "@/components/shared/DataTableCells/ResourceCell";
import GroupTextCell from "@/components/shared/DataTableCells/GroupTextCell";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";
import {
  generateActionsColumDef,
  generateGroupedCellDef,
  generateGroupedNameColumDef,
  getSharedShiftCheckboxClickHandler,
} from "@/components/shared/DataTable/utils";
import { useDynamicColumnsCache } from "@/hooks/useDynamicColumnsCache";
import { DELETED_DATASET_LABEL } from "@/constants/groups";

export type UseExperimentsTableConfigProps<T> = {
  storageKeyPrefix: string;
  defaultColumns: ColumnData<T>[];
  defaultSelectedColumns: string[];
  groups: Groups;
  sortableBy: string[];
  dynamicScoresColumns: DynamicColumn[];
  experiments: T[];
  rowSelection: RowSelectionState;
  feedbackScoresData?: Array<{ name: string }>;
  actionsCell?: ColumnDefTemplate<CellContext<T, unknown>>;
  sortedColumns: ColumnSort[];
  setSortedColumns: OnChangeFn<ColumnSort[]>;
};

export const useExperimentsTableConfig = <
  T extends {
    id: string;
    dataset_id: string;
    dataset_name: string;
    name: string;
  },
>({
  storageKeyPrefix,
  defaultColumns,
  defaultSelectedColumns,
  groups,
  sortableBy,
  dynamicScoresColumns,
  experiments,
  rowSelection,
  actionsCell,
  sortedColumns,
  setSortedColumns,
}: UseExperimentsTableConfigProps<T>) => {
  const [selectedColumns, setSelectedColumns] = useLocalStorageState<string[]>(
    `${storageKeyPrefix}-selected-columns`,
    {
      defaultValue: defaultSelectedColumns,
    },
  );

  const [columnsOrder, setColumnsOrder] = useLocalStorageState<string[]>(
    `${storageKeyPrefix}-columns-order`,
    {
      defaultValue: [],
    },
  );

  const [scoresColumnsOrder, setScoresColumnsOrder] = useLocalStorageState<
    string[]
  >(`${storageKeyPrefix}-scores-columns-order`, {
    defaultValue: [],
  });

  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(`${storageKeyPrefix}-columns-width`, {
    defaultValue: {},
  });

  const dynamicColumnsIds = useMemo(
    () => dynamicScoresColumns.map((c) => c.id),
    [dynamicScoresColumns],
  );

  useDynamicColumnsCache({
    dynamicColumnsKey: `${storageKeyPrefix}-dynamic-columns`,
    dynamicColumnsIds,
    setSelectedColumns,
  });

  const { checkboxClickHandler } = useMemo(() => {
    return {
      checkboxClickHandler: getSharedShiftCheckboxClickHandler(),
    };
  }, []);

  const scoresColumnsData = useMemo(() => {
    return [
      ...dynamicScoresColumns.map(
        ({ label, id, columnType }) =>
          ({
            id,
            label,
            type: columnType,
            header: FeedbackScoreHeader as never,
            cell: FeedbackScoreCell as never,
            accessorFn: (row: T) =>
              (
                row as T & { feedback_scores?: Array<{ name: string }> }
              ).feedback_scores?.find((f) => f.name === label),
          }) as ColumnData<T>,
      ),
    ];
  }, [dynamicScoresColumns]);

  const selectedRows = useMemo(() => {
    return experiments.filter(
      (row) => rowSelection[row.id] && !checkIsGroupRowType(row.id),
    );
  }, [rowSelection, experiments]);

  const groupFieldNames = useMemo(
    () => groups.map((group) => buildGroupFieldName(group)),
    [groups],
  );

  const columns = useMemo(() => {
    const groupColumns = groups.map((group) => {
      const label = group.field + (group.key ? ` (${group.key})` : "");
      const id = buildGroupFieldName(group);
      const metaKey = buildGroupFieldNameForMeta(group);

      let groupCellDef = {
        id,
        label,
        type: COLUMN_TYPE.string,
        cell: GroupTextCell as never,
        customMeta: {
          valueKey: `${metaKey}.value`,
          labelKey: `${metaKey}.label`,
        },
      } as ColumnData<T>;

      // Handle specific column types
      switch (group.field) {
        case "dataset_id":
          groupCellDef = {
            ...groupCellDef,
            label: "Dataset",
            type: COLUMN_TYPE.string,
            cell: ResourceCell as never,
            customMeta: {
              nameKey: `${metaKey}.label`,
              idKey: `${metaKey}.value`,
              resource: RESOURCE_TYPE.dataset,
              getIsDeleted: (row: T) =>
                get(row, `${metaKey}.label`, row.dataset_name) ===
                DELETED_DATASET_LABEL,
            },
          } as ColumnData<T>;
          break;
        case "metadata":
          groupCellDef = {
            ...groupCellDef,
            label: `config.${group.key}`,
            type: COLUMN_TYPE.dictionary,
          } as ColumnData<T>;
          break;
      }

      return generateGroupedCellDef<T, unknown>(
        groupCellDef,
        checkboxClickHandler,
      );
    });

    const baseColumns = [
      generateGroupedNameColumDef<T>(
        checkboxClickHandler,
        isColumnSortable(COLUMN_NAME_ID, sortableBy),
      ),
      ...groupColumns,
      ...convertColumnDataToColumn<T, T>(defaultColumns, {
        columnsOrder,
        selectedColumns,
        sortableColumns: sortableBy,
      }),
      ...convertColumnDataToColumn<T, T>(scoresColumnsData, {
        columnsOrder: scoresColumnsOrder,
        selectedColumns,
        sortableColumns: sortableBy,
      }),
    ];

    if (actionsCell) {
      baseColumns.push(
        generateActionsColumDef({
          cell: actionsCell,
        }),
      );
    }

    return baseColumns;
  }, [
    checkboxClickHandler,
    sortableBy,
    groups,
    columnsOrder,
    selectedColumns,
    scoresColumnsData,
    scoresColumnsOrder,
    defaultColumns,
    actionsCell,
  ]);

  const sortConfig = useMemo(
    () => ({
      enabled: true,
      sorting: sortedColumns,
      setSorting: setSortedColumns,
    }),
    [setSortedColumns, sortedColumns],
  );

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: setColumnsWidth,
    }),
    [columnsWidth, setColumnsWidth],
  );

  const columnPinningConfig = useMemo(() => {
    return {
      left: [COLUMN_NAME_ID, ...groupFieldNames],
      right: [],
    } as ColumnPinningState;
  }, [groupFieldNames]);

  const groupingConfig = useMemo(() => {
    return {
      groupedColumnMode: false as const,
      grouping: groupFieldNames,
    };
  }, [groupFieldNames]);

  const columnSections = useMemo(() => {
    return [
      {
        title: "Feedback scores",
        columns: scoresColumnsData,
        order: scoresColumnsOrder,
        onOrderChange: setScoresColumnsOrder,
      },
    ];
  }, [scoresColumnsData, scoresColumnsOrder, setScoresColumnsOrder]);

  return {
    // State
    selectedColumns,
    setSelectedColumns,
    columnsOrder,
    setColumnsOrder,
    scoresColumnsOrder,
    setScoresColumnsOrder,
    columnsWidth,
    setColumnsWidth,

    // Computed values
    columns,
    selectedRows,
    scoresColumnsData,
    checkboxClickHandler,
    groupFieldNames,

    // Configs
    sortConfig,
    resizeConfig,
    columnPinningConfig,
    groupingConfig,
    columnSections,
  };
};
