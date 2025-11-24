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
  COLUMN_DATASET_ID,
  COLUMN_METADATA_ID,
} from "@/types/shared";
import { convertColumnDataToColumn, isColumnSortable } from "@/lib/table";
import {
  buildGroupFieldName,
  buildGroupFieldNameForMeta,
  calculateGroupLabel,
  checkIsGroupRowType,
} from "@/lib/groups";
import FeedbackScoreHeader from "@/components/shared/DataTableHeaders/FeedbackScoreHeader";
import FeedbackScoreCell from "@/components/shared/DataTableCells/FeedbackScoreCell";
import FeedbackScoreReasonCell from "@/components/shared/DataTableCells/FeedbackScoreReasonCell";
import ResourceCell from "@/components/shared/DataTableCells/ResourceCell";
import TextCell from "@/components/shared/DataTableCells/TextCell";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";
import {
  generateActionsColumDef,
  generateGroupedRowCellDef,
  generateDataRowCellDef,
  getSharedShiftCheckboxClickHandler,
} from "@/components/shared/DataTable/utils";
import { useDynamicColumnsCache } from "@/hooks/useDynamicColumnsCache";
import { DELETED_DATASET_LABEL } from "@/constants/groups";
import { Experiment, ExperimentsAggregations } from "@/types/datasets";

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
  showReasons?: boolean;
};

export const useExperimentsTableConfig = <
  T extends {
    id: string;
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
  showReasons = false,
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

  const dynamicColumnsIds = useMemo(() => {
    // Only manage score column IDs in dynamic columns cache
    // Reason columns are controlled by the global toggle, not individual column settings
    return dynamicScoresColumns.map((c) => c.id);
  }, [dynamicScoresColumns]);

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
    const scoreColumns = dynamicScoresColumns.map(
      ({ label, id, columnType }) =>
        ({
          id,
          label,
          type: columnType,
          header: FeedbackScoreHeader as never,
          cell: FeedbackScoreCell as never,
          aggregatedCell: FeedbackScoreCell.Aggregation as never,
          accessorFn: (row: T) =>
            (
              row as T & { feedback_scores?: Array<{ name: string }> }
            ).feedback_scores?.find((f) => f.name === label),
          customMeta: {
            accessorFn: (aggregation: ExperimentsAggregations) =>
              (
                aggregation as ExperimentsAggregations & {
                  feedback_scores?: Array<{ name: string }>;
                }
              ).feedback_scores?.find((f) => f.name === label)?.value,
          },
        }) as ColumnData<T>,
    );

    const reasonColumns = showReasons
      ? dynamicScoresColumns.map(
          ({ label, id }) =>
            ({
              id: `${id}_reason`,
              label: `${label} (reason)`,
              type: COLUMN_TYPE.string,
              cell: FeedbackScoreReasonCell as never,
              accessorFn: (row: T) =>
                (
                  row as T & { feedback_scores?: Array<{ name: string }> }
                ).feedback_scores?.find((f) => f.name === label),
            }) as ColumnData<T>,
        )
      : [];

    return [...scoreColumns, ...reasonColumns];
  }, [dynamicScoresColumns, showReasons]);

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
      const label = calculateGroupLabel(group);
      const id = buildGroupFieldName(group);
      const metaKey = buildGroupFieldNameForMeta(group);

      let groupCellDef = {
        id,
        label,
        type: COLUMN_TYPE.string,
        cell: TextCell.Group as never,
        customMeta: {
          valueKey: `${metaKey}.value`,
          labelKey: `${metaKey}.label`,
          countAggregationKey: "experiment_count",
          explainer: {
            id: "group-experiments",
            description: `Some of the experiments didn’t match any group`,
          },
        },
      } as ColumnData<T>;

      switch (group.field) {
        case COLUMN_DATASET_ID:
          groupCellDef = {
            ...groupCellDef,
            type: COLUMN_TYPE.string,
            cell: ResourceCell.Group as never,
            customMeta: {
              nameKey: `${metaKey}.label`,
              idKey: `${metaKey}.value`,
              resource: RESOURCE_TYPE.dataset,
              getIsDeleted: (row: T) =>
                get(row, `${metaKey}.label`, "") === DELETED_DATASET_LABEL,
              countAggregationKey: "experiment_count",
              explainer: {
                id: "group-experiments",
                description: `Some experiments reference a dataset that has been deleted`,
              },
            },
          } as ColumnData<T>;
          break;
        case COLUMN_METADATA_ID:
          groupCellDef = {
            ...groupCellDef,
            type: COLUMN_TYPE.dictionary,
          } as ColumnData<T>;
          break;
      }

      return generateGroupedRowCellDef<T, unknown>(
        groupCellDef,
        checkboxClickHandler,
      );
    });

    const baseColumns = [
      generateDataRowCellDef<T>(
        {
          id: COLUMN_NAME_ID,
          label: "Name",
          type: COLUMN_TYPE.string,
          cell: ResourceCell as never,
          customMeta: {
            nameKey: "name",
            idKey: "dataset_id",
            resource: RESOURCE_TYPE.experiment,
            getSearch: (data: Experiment) => ({
              experiments: [data.id],
            }),
          },
          headerCheckbox: true,
          sortable: isColumnSortable(COLUMN_NAME_ID, sortableBy),
          size: 200,
        },
        checkboxClickHandler,
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
