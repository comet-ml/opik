import React, { useMemo } from "react";
import { TraceFeedbackScore } from "@/types/traces";
import { convertColumnDataToColumn } from "@/lib/table";
import DataTable from "@/components/shared/DataTable/DataTable";
import FeedbackScoreTableNoData from "../FeedbackScoreTableNoData";
import {
  CONFIGURABLE_COLUMNS,
  DEFAULT_SELECTED_COLUMNS,
  NON_CONFIGURABLE_COLUMNS,
  ENTITY_TYPE_TO_STORAGE_KEYS,
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
  onDeleteFeedbackScore?: (name: string, author?: string) => void;
  onAddHumanReview: () => void;
  feedbackScores?: TraceFeedbackScore[];
  entityType: "trace" | "thread" | "span" | "experiment";
  selectedColumns?: string[];
  columnsOrder?: string[];
};

const FeedbackScoreTable: React.FunctionComponent<FeedbackScoreTableProps> = ({
  onAddHumanReview,
  feedbackScores = [],
  entityType,
  onDeleteFeedbackScore,
  selectedColumns = DEFAULT_SELECTED_COLUMNS,
  columnsOrder = DEFAULT_SELECTED_COLUMNS,
}) => {
  const storageKeys = ENTITY_TYPE_TO_STORAGE_KEYS[entityType];

  const [expanded, setExpanded] = React.useState<ExpandedState>({});
  const [columnSizing, setColumnSizing] =
    useLocalStorageState<ColumnSizingState>(storageKeys.columnSizing, {
      defaultValue: {},
    });
  const [rowToDelete, setRowToDelete] =
    React.useState<ExpandingFeedbackScoreRow | null>(null);
  const [dontAskAgain] = useFeedbackScoreDeletePreference();

  const rows = useMemo(() => {
    return mapFeedbackScoresToRowsWithExpanded(feedbackScores);
  }, [feedbackScores]);

  const handleDeleteClick = React.useCallback(
    (row: ExpandingFeedbackScoreRow) => {
      if (!onDeleteFeedbackScore) return;
      if (dontAskAgain) {
        onDeleteFeedbackScore(row.name, row.author);
      } else {
        setRowToDelete(row);
      }
    },
    [dontAskAgain, onDeleteFeedbackScore],
  );

  const columns = useMemo(() => {
    const baseColumns = [
      ...convertColumnDataToColumn<
        ExpandingFeedbackScoreRow,
        ExpandingFeedbackScoreRow
      >(NON_CONFIGURABLE_COLUMNS, {}),
      ...convertColumnDataToColumn<
        ExpandingFeedbackScoreRow,
        ExpandingFeedbackScoreRow
      >(CONFIGURABLE_COLUMNS, { selectedColumns, columnsOrder }),
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
  }, [selectedColumns, columnsOrder, handleDeleteClick, onDeleteFeedbackScore]);

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
