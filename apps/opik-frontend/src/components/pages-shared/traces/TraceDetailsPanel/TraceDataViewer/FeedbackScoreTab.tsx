import React, { useMemo } from "react";
import sortBy from "lodash/sortBy";
import { Span, Trace, TraceFeedbackScore } from "@/types/traces";
import FeedbackScoreRowDeleteCell from "./FeedbackScoreRowDeleteCell";
import FeedbackScoreValueCell from "./FeedbackScoreValueCell";
import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import { convertColumnDataToColumn } from "@/lib/table";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import DataTable from "@/components/shared/DataTable/DataTable";
import FeedbackScoreNameCell from "@/components/shared/DataTableCells/FeedbackScoreNameCell";
import FeedbackScoreReasonCell from "@/components/shared/DataTableCells/FeedbackScoreReasonCell";
import { feedbackScoreSourceMap } from "@/lib/feedback-scores";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import useLocalStorageState from "use-local-storage-state";

const SELECTED_COLUMNS_KEY = "trace-feedback-scores-tab-selected-columns";
const COLUMNS_ORDER_KEY = "trace-feedback-scores-tab-columns-order";

export const DEFAULT_COLUMNS: ColumnData<TraceFeedbackScore>[] = [
  {
    id: "source",
    label: "Source",
    type: COLUMN_TYPE.string,
    size: 100,
    accessorFn: (row) => feedbackScoreSourceMap[row.source],
  },
  {
    id: "name",
    label: "Key",
    type: COLUMN_TYPE.string,
    size: 100,
    cell: FeedbackScoreNameCell as never,
  },
  {
    id: "value",
    label: "Score",
    type: COLUMN_TYPE.string,
    cell: FeedbackScoreValueCell as never,
    size: 100,
  },
  {
    id: "reason",
    label: "Reason",
    type: COLUMN_TYPE.string,
    cell: FeedbackScoreReasonCell as never,
    size: 100,
  },
  {
    id: "created_by",
    label: "Created by",
    type: COLUMN_TYPE.string,
    size: 100,
  },
];

const DEFAULT_SELECTED_COLUMNS = ["name", "value", "reason"];

type FeedbackScoreTabProps = {
  data: Trace | Span;
  spanId?: string;
  traceId: string;
};

const FeedbackScoreTab: React.FunctionComponent<FeedbackScoreTabProps> = ({
  data,
  spanId,
  traceId,
}) => {
  const [selectedColumns, setSelectedColumns] = useLocalStorageState<string[]>(
    SELECTED_COLUMNS_KEY,
    {
      defaultValue: DEFAULT_SELECTED_COLUMNS,
    },
  );

  const [columnsOrder, setColumnsOrder] = useLocalStorageState<string[]>(
    COLUMNS_ORDER_KEY,
    {
      defaultValue: [],
    },
  );

  const columns = useMemo(() => {
    const retVal = convertColumnDataToColumn<
      TraceFeedbackScore,
      TraceFeedbackScore
    >(DEFAULT_COLUMNS, { selectedColumns, columnsOrder });

    retVal.push({
      id: "delete",
      enableHiding: false,
      cell: FeedbackScoreRowDeleteCell,
      meta: {
        custom: {
          traceId,
          spanId,
        },
      },
      size: 48,
      enableResizing: false,
    });

    return retVal;
  }, [traceId, spanId, selectedColumns, columnsOrder]);

  const feedbackScores: TraceFeedbackScore[] = useMemo(
    () => sortBy(data.feedback_scores || [], "name"),
    [data.feedback_scores],
  );

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
        data={feedbackScores}
        noData={<DataTableNoData title="No feedback scores" />}
      />
    </>
  );
};

export default FeedbackScoreTab;
