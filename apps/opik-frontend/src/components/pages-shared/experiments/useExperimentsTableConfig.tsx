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
import uniqBy from "lodash/uniqBy";

import { Groups } from "@/types/groups";
import {
  COLUMN_SELECT_ID,
  ColumnData,
  DynamicColumn,
  COLUMN_TYPE,
  COLUMN_DATASET_ID,
  COLUMN_PROJECT_ID,
  COLUMN_METADATA_ID,
  COLUMN_NAME_ID,
  SCORE_TYPE_FEEDBACK,
} from "@/types/shared";
import {
  getExperimentScore,
  parseScoreColumnId,
  RowWithScores,
} from "@/lib/feedback-scores";
import { convertColumnDataToColumn, migrateSelectedColumns } from "@/lib/table";
import {
  buildGroupFieldName,
  buildGroupFieldNameForMeta,
  calculateGroupLabel,
  checkIsGroupRowType,
} from "@/lib/groups";
import FeedbackScoreHeader from "@/components/shared/DataTableHeaders/FeedbackScoreHeader";
import FeedbackScoreCell from "@/components/shared/DataTableCells/FeedbackScoreCell";
import ResourceCell from "@/components/shared/DataTableCells/ResourceCell";
import TextCell from "@/components/shared/DataTableCells/TextCell";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";
import {
  generateActionsColumDef,
  generateDataRowCellDef,
  generateGroupedRowCellDef,
  generateSelectColumDef,
  getSharedShiftCheckboxClickHandler,
} from "@/components/shared/DataTable/utils";
import { useDynamicColumnsCache } from "@/hooks/useDynamicColumnsCache";
import { DELETED_ENTITY_LABEL, GROUPING_KEY } from "@/constants/groups";
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
}: UseExperimentsTableConfigProps<T>) => {
  const [selectedColumns, setSelectedColumns] = useLocalStorageState<string[]>(
    `${storageKeyPrefix}-selected-columns-v2`,
    {
      defaultValue: migrateSelectedColumns(
        `${storageKeyPrefix}-selected-columns`,
        defaultSelectedColumns,
        [COLUMN_NAME_ID],
      ),
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

  /**
   * Generates a unique row ID for an experiment.
   * When grouping is active, the same experiment can appear in multiple groups
   * (e.g., when grouping by tags, an experiment with multiple tags appears once per tag).
   * This method creates unique IDs by combining the experiment ID with grouping field values.
   *
   * @param row - The experiment row
   * @returns A unique row ID (either simple "experimentId" or compound "experimentId|field:value")
   */
  const getExperimentRowId = useMemo(() => {
    return (row: T) => {
      // Find all grouping fields in the row
      const groupingFields = Object.keys(row).filter((key) =>
        key.startsWith(GROUPING_KEY),
      );

      if (groupingFields.length > 0) {
        // Create a unique ID by combining the experiment ID with all grouping field values
        const groupParts = groupingFields
          .map((field) => `${field}:${row[field as keyof T]}`)
          .join("|");
        return `${row.id}|${groupParts}`;
      }

      return row.id;
    };
  }, []);

  const { checkboxClickHandler } = useMemo(() => {
    return {
      checkboxClickHandler: getSharedShiftCheckboxClickHandler(),
    };
  }, []);

  const scoresColumnsData = useMemo(() => {
    return [
      ...dynamicScoresColumns.map(
        ({ label, id, columnType, type: scoreType }) => {
          const actualType = scoreType || SCORE_TYPE_FEEDBACK;
          const scoreName = parseScoreColumnId(id)?.scoreName;

          const columnData: ColumnData<T> = {
            id,
            label,
            type: columnType,
            scoreType: actualType,
            header: FeedbackScoreHeader as never,
            cell: FeedbackScoreCell as never,
            aggregatedCell: FeedbackScoreCell.Aggregation as never,
            accessorFn: (row) => getExperimentScore(id, row as RowWithScores),
            customMeta: {
              accessorFn: (aggregation: ExperimentsAggregations) =>
                getExperimentScore(id, aggregation)?.value,
              scoreName,
            },
          };

          return columnData;
        },
      ),
    ];
  }, [dynamicScoresColumns]);

  const selectedRows = useMemo(() => {
    const selected = experiments.filter((row) => {
      if (checkIsGroupRowType(row.id)) {
        return false;
      }

      // Check if this experiment is selected using either simple or compound ID
      // When grouping is active, the rowSelection keys are compound IDs like "experimentId|grouping_field:value"
      // We need to check both the simple ID and all possible compound IDs
      if (rowSelection[row.id]) {
        return true;
      }

      // Check if any compound ID containing this experiment ID is selected
      return Object.keys(rowSelection).some((selectionKey) => {
        return (
          selectionKey.startsWith(`${row.id}|`) && rowSelection[selectionKey]
        );
      });
    });

    // Deduplicate by experiment ID since the same experiment can appear in multiple groups
    return uniqBy(selected, "id");
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
                get(row, `${metaKey}.label`, "") === DELETED_ENTITY_LABEL,
              countAggregationKey: "experiment_count",
              explainer: {
                id: "group-experiments",
                description: `Some experiments reference a dataset that has been deleted`,
              },
            },
          } as ColumnData<T>;
          break;
        case COLUMN_PROJECT_ID:
          groupCellDef = {
            ...groupCellDef,
            type: COLUMN_TYPE.string,
            cell: ResourceCell.Group as never,
            customMeta: {
              nameKey: `${metaKey}.label`,
              idKey: `${metaKey}.value`,
              resource: RESOURCE_TYPE.project,
              getIsDeleted: (row: T) =>
                get(row, `${metaKey}.label`, "") === DELETED_ENTITY_LABEL,
              countAggregationKey: "experiment_count",
              explainer: {
                id: "group-experiments",
                description: `Some experiments reference a project that has been deleted`,
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

    const hasGrouping = groups.length > 0;
    const nameColumn = defaultColumns.find((col) => col.id === COLUMN_NAME_ID);
    const columnsWithoutName = defaultColumns.filter(
      (col) => col.id !== COLUMN_NAME_ID,
    );

    const firstColumn =
      hasGrouping && nameColumn
        ? generateDataRowCellDef<T>(
            {
              ...nameColumn,
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
            },
            checkboxClickHandler,
          )
        : generateSelectColumDef<T>();

    const regularColumns = convertColumnDataToColumn<T, T>(
      hasGrouping && nameColumn ? columnsWithoutName : defaultColumns,
      {
        columnsOrder,
        selectedColumns,
        sortableColumns: sortableBy,
      },
    );

    const scoresColumns = convertColumnDataToColumn<T, T>(scoresColumnsData, {
      columnsOrder: scoresColumnsOrder,
      selectedColumns,
      sortableColumns: sortableBy,
    });

    const baseColumns = [
      firstColumn,
      ...groupColumns,
      ...regularColumns,
      ...scoresColumns,
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
    groups,
    sortableBy,
    checkboxClickHandler,
    defaultColumns,
    columnsOrder,
    selectedColumns,
    scoresColumnsData,
    scoresColumnsOrder,
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
      left:
        groupFieldNames.length > 0
          ? [COLUMN_NAME_ID, ...groupFieldNames]
          : [COLUMN_SELECT_ID],
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

    // Utility methods
    getExperimentRowId,

    // Configs
    sortConfig,
    resizeConfig,
    columnPinningConfig,
    groupingConfig,
    columnSections,
  };
};
