import React, { useMemo } from "react";
import { TraceFeedbackScore } from "@/types/traces";
import { convertColumnDataToColumn } from "@/lib/table";
import DataTable from "@/components/shared/DataTable/DataTable";
import FeedbackScoreTableNoData from "../FeedbackScoreTableNoData";
import {
  CONFIGURABLE_COLUMNS,
  DEFAULT_SELECTED_COLUMNS,
  DEFAULT_SELECTED_COLUMNS_WITH_TYPE,
  NON_CONFIGURABLE_COLUMNS,
  ENTITY_TYPE_TO_STORAGE_KEYS,
  getConfigurableColumnsWithoutType,
  getStorageKeyType,
} from "./constants";
import { ExpandingFeedbackScoreRow } from "./types";
import { mapFeedbackScoresToRowsWithExpanded } from "./utils";
import { ExpandedState, ColumnSizingState } from "@tanstack/react-table";
import { generateActionsColumDef } from "@/components/shared/DataTable/utils";
import ActionsCell from "./cells/ActionsCell";
import DeleteFeedbackScoreValueDialog from "./DeleteFeedbackScoreValueDialog";
import { useFeedbackScoreDeletePreference } from "./hooks/useFeedbackScoreDeletePreference";
import useLocalStorageState from "use-local-storage-state";

export type FeedbackScoreTableProps = {
  onDeleteFeedbackScore?: (
    name: string,
    author?: string,
    spanId?: string,
  ) => void;
  onAddHumanReview: () => void;
  feedbackScores?: TraceFeedbackScore[];
  entityType: "trace" | "thread" | "span" | "experiment";
  selectedColumns?: string[];
  columnsOrder?: string[];
  /**
   * Whether this table is showing aggregated span scores at trace level.
   * When true, the Type column will be shown. When false or undefined, Type column is hidden.
   */
  isAggregatedSpanScores?: boolean;
};

const FeedbackScoreTable: React.FunctionComponent<FeedbackScoreTableProps> = ({
  onAddHumanReview,
  feedbackScores = [],
  entityType,
  onDeleteFeedbackScore,
  selectedColumns,
  columnsOrder,
  isAggregatedSpanScores = false,
}) => {
  // Use "span" storage keys for aggregated span scores, otherwise use entityType
  const storageKeyType = getStorageKeyType(entityType, isAggregatedSpanScores);
  const storageKeys = ENTITY_TYPE_TO_STORAGE_KEYS[storageKeyType];

  // Default columns: include Type only for aggregated span scores at trace level
  // Match mockup (Key, Type, Score, Scored by) for span scores at trace level
  const defaultSelectedColumns = isAggregatedSpanScores
    ? DEFAULT_SELECTED_COLUMNS_WITH_TYPE
    : DEFAULT_SELECTED_COLUMNS;

  const [expanded, setExpanded] = React.useState<ExpandedState>({});
  const [columnSizing, setColumnSizing] =
    useLocalStorageState<ColumnSizingState>(storageKeys.columnSizing, {
      defaultValue: {},
    });
  const [rowToDelete, setRowToDelete] =
    React.useState<ExpandingFeedbackScoreRow | null>(null);
  const [dontAskAgain] = useFeedbackScoreDeletePreference();

  const finalSelectedColumns = selectedColumns ?? defaultSelectedColumns;
  const finalColumnsOrder = columnsOrder ?? defaultSelectedColumns;

  const rows = useMemo(() => {
    return mapFeedbackScoresToRowsWithExpanded(
      feedbackScores,
      entityType,
      isAggregatedSpanScores,
    );
  }, [feedbackScores, entityType, isAggregatedSpanScores]);

  const handleDeleteClick = React.useCallback(
    (row: ExpandingFeedbackScoreRow) => {
      if (!onDeleteFeedbackScore) return;
      if (dontAskAgain) {
        // For child rows grouped by span type, pass span_id
        onDeleteFeedbackScore(row.name, row.author, row.span_id);
      } else {
        setRowToDelete(row);
      }
    },
    [dontAskAgain, onDeleteFeedbackScore],
  );

  const columns = useMemo(() => {
    // Filter out Type column if not aggregated span scores at trace level
    // Type column should only appear when isAggregatedSpanScores is true
    const filteredConfigurableColumns = isAggregatedSpanScores
      ? CONFIGURABLE_COLUMNS
      : getConfigurableColumnsWithoutType();

    const baseColumns = [
      ...convertColumnDataToColumn<
        ExpandingFeedbackScoreRow,
        ExpandingFeedbackScoreRow
      >(NON_CONFIGURABLE_COLUMNS, {}),
      ...convertColumnDataToColumn<
        ExpandingFeedbackScoreRow,
        ExpandingFeedbackScoreRow
      >(filteredConfigurableColumns, {
        selectedColumns: finalSelectedColumns,
        columnsOrder: finalColumnsOrder,
      }),
    ];

    // Only add actions column if deletion is enabled
    if (onDeleteFeedbackScore) {
      baseColumns.push(
        generateActionsColumDef({
          cell: ActionsCell,
          customMeta: {
            onDelete: handleDeleteClick,
          },
        }),
      );
    }

    return baseColumns;
  }, [
    finalSelectedColumns,
    finalColumnsOrder,
    handleDeleteClick,
    onDeleteFeedbackScore,
    isAggregatedSpanScores,
  ]);

  return (
    <>
      <DataTable
        columns={columns}
        data={rows}
        expandingConfig={{
          expanded,
          setExpanded,
          autoResetExpanded: false,
        }}
        resizeConfig={{
          enabled: true,
          columnSizing,
          onColumnResize: setColumnSizing,
        }}
        getRowId={(row: ExpandingFeedbackScoreRow) => row.id}
        getSubRows={(row: ExpandingFeedbackScoreRow) => row?.subRows}
        noData={
          <FeedbackScoreTableNoData
            entityType={entityType}
            onAddHumanReview={onAddHumanReview}
          />
        }
      />

      {onDeleteFeedbackScore && (
        <DeleteFeedbackScoreValueDialog
          open={!!rowToDelete}
          setOpen={(open) => setRowToDelete(open ? rowToDelete : null)}
          onDeleteFeedbackScore={onDeleteFeedbackScore}
          row={rowToDelete!}
          entityType={entityType}
        />
      )}
    </>
  );
};

export default FeedbackScoreTable;
