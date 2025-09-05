import React, { useMemo } from "react";
import { TraceFeedbackScore } from "@/types/traces";
import { convertColumnDataToColumn } from "@/lib/table";
import DataTable from "@/components/shared/DataTable/DataTable";
import FeedbackScoreTableNoData from "../FeedbackScoreTableNoData";
import { DEFAULT_COLUMNS, DEFAULT_SELECTED_COLUMNS } from "./constants";
import { ExpandingFeedbackScoreRow } from "./types";
import { mapFeedbackScoresToRowsWithExpanded } from "./utils";
import { ExpandedState } from "@tanstack/react-table";
import { generateActionsColumDef } from "@/components/shared/DataTable/utils";
import ActionsCell from "./cells/ActionsCell";
import DeleteFeedbackScoreValueDialog from "./DeleteFeedbackScoreValueDialog";
import { useFeedbackScoreDeletePreference } from "./hooks/useFeedbackScoreDeletePreference";

export type FeedbackScoreTableProps = {
  onDeleteFeedbackScore: (name: string, author?: string) => void;
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
  const [expanded, setExpanded] = React.useState<ExpandedState>({});
  const [rowToDelete, setRowToDelete] =
    React.useState<ExpandingFeedbackScoreRow | null>(null);
  const [dontAskAgain] = useFeedbackScoreDeletePreference();

  const rows = useMemo(() => {
    return mapFeedbackScoresToRowsWithExpanded(feedbackScores);
  }, [feedbackScores]);

  const handleDeleteClick = React.useCallback(
    (row: ExpandingFeedbackScoreRow) => {
      if (dontAskAgain) {
        onDeleteFeedbackScore(row.name, row.author);
      } else {
        setRowToDelete(row);
      }
    },
    [dontAskAgain, onDeleteFeedbackScore],
  );

  const columns = useMemo(() => {
    return [
      ...convertColumnDataToColumn<
        ExpandingFeedbackScoreRow,
        ExpandingFeedbackScoreRow
      >(DEFAULT_COLUMNS, { selectedColumns, columnsOrder }),
      generateActionsColumDef({
        cell: ActionsCell,
        customMeta: {
          onDelete: handleDeleteClick,
        },
      }),
    ];
  }, [selectedColumns, columnsOrder, handleDeleteClick]);

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
        getRowId={(row: ExpandingFeedbackScoreRow) => row.id}
        getSubRows={(row: ExpandingFeedbackScoreRow) => row?.subRows}
        noData={
          <FeedbackScoreTableNoData
            entityType={entityType}
            onAddHumanReview={onAddHumanReview}
          />
        }
      />

      <DeleteFeedbackScoreValueDialog
        open={!!rowToDelete}
        setOpen={(open) => setRowToDelete(open ? rowToDelete : null)}
        onDeleteFeedbackScore={onDeleteFeedbackScore}
        row={rowToDelete!}
        entityType={entityType}
      />
    </>
  );
};

export default FeedbackScoreTable;
