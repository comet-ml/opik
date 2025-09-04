import React, { useMemo } from "react";
import { TraceFeedbackScore } from "@/types/traces";
import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import { convertColumnDataToColumn } from "@/lib/table";
import DataTable from "@/components/shared/DataTable/DataTable";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import useLocalStorageState from "use-local-storage-state";
import FeedbackScoreTableNoData from "../FeedbackScoreTableNoData";
import { DEFAULT_SELECTED_COLUMNS } from "./constants";
import { ExpandingFeedbackScoreRow } from "./types";
import { mapFeedbackScoresToRowsWithExpanded } from "./utils";
import NameCell from "./cells/NameCell";
import { ExpandedState } from "@tanstack/react-table";
import AuthorCell from "./cells/AuthorCell";
import ValueCell from "./cells/ValueCell";
import ReasonCell from "./cells/ReasonCell";
import SourceCell from "./cells/SourceCell";
const SELECTED_COLUMNS_KEY = "feedback-scores-tab-selected-columns";
const COLUMNS_ORDER_KEY = "feedback-scores-tab-columns-order";

export const DEFAULT_COLUMNS: ColumnData<ExpandingFeedbackScoreRow>[] = [
  {
    id: "source",
    label: "Source",
    type: COLUMN_TYPE.string,
    size: 100,
    cell: SourceCell as never,
  },
  {
    id: "name",
    label: "Key",
    type: COLUMN_TYPE.string,
    size: 100,
    cell: NameCell as never,
  },
  {
    id: "value",
    label: "Score",
    type: COLUMN_TYPE.string,
    cell: ValueCell as never,
    size: 100,
  },
  {
    id: "reason",
    label: "Reason",
    type: COLUMN_TYPE.string,
    cell: ReasonCell as never,
    size: 100,
  },
  {
    id: "created_by",
    label: "Scored by",
    type: COLUMN_TYPE.string,
    cell: AuthorCell as never,
    size: 100,
  },
];

type FeedbackScoreTableProps = {
  onDeleteFeedbackScore: (name: string) => void;
  onAddHumanReview: () => void;
  entityName: string;
  feedbackScores?: TraceFeedbackScore[];
  entityType: "trace" | "thread";
};

const FeedbackScoreTable: React.FunctionComponent<FeedbackScoreTableProps> = ({
  onAddHumanReview,
  feedbackScores = [],
  entityType,
}) => {
  const [expanded, setExpanded] = React.useState<ExpandedState>({});
  const expandingConfig = {
    expanded,
    setExpanded,
    autoResetExpanded: false,
  };

  const [selectedColumns, setSelectedColumns] = useLocalStorageState<string[]>(
    `${entityType}-${SELECTED_COLUMNS_KEY}`,
    {
      defaultValue: DEFAULT_SELECTED_COLUMNS,
    },
  );

  const [columnsOrder, setColumnsOrder] = useLocalStorageState<string[]>(
    `${entityType}-${COLUMNS_ORDER_KEY}`,
    {
      defaultValue: [],
    },
  );

  const rows = useMemo(() => {
    return mapFeedbackScoresToRowsWithExpanded(feedbackScores);
  }, [feedbackScores]);

  const columns = useMemo(() => {
    return convertColumnDataToColumn<
      ExpandingFeedbackScoreRow,
      ExpandingFeedbackScoreRow
    >(DEFAULT_COLUMNS, { selectedColumns, columnsOrder });
  }, [selectedColumns, columnsOrder]);

  return (
    <>
      <div className="mb-4 flex justify-end">
        <ColumnsButton
          columns={DEFAULT_COLUMNS}
          selectedColumns={selectedColumns}
          onSelectionChange={setSelectedColumns}
          order={columnsOrder}
          onOrderChange={setColumnsOrder}
        ></ColumnsButton>
      </div>

      <DataTable
        columns={columns}
        data={rows}
        expandingConfig={expandingConfig}
        getRowId={(row: ExpandingFeedbackScoreRow) => row.id}
        getSubRows={(row: ExpandingFeedbackScoreRow) => row?.subRows}
        noData={
          <FeedbackScoreTableNoData
            entityType={entityType}
            onAddHumanReview={onAddHumanReview}
          />
        }
      />
    </>
  );
};

export default FeedbackScoreTable;
