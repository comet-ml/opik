import React, { useMemo } from "react";
import sortBy from "lodash/sortBy";
import { Span, Trace, TraceFeedbackScore } from "@/types/traces";
import FeedbackScoreRowDeleteCell from "./FeedbackScoreRowDeleteCell";
import FeedbackScoreValueCell from "./FeedbackScoreValueCell";
import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import { convertColumnDataToColumn } from "@/lib/table";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import DataTable from "@/components/shared/DataTable/DataTable";
import TagCell from "@/components/shared/DataTableCells/TagCell";

export const DEFAULT_COLUMNS: ColumnData<TraceFeedbackScore>[] = [
  {
    id: "source",
    label: "Source",
    type: COLUMN_TYPE.list,
    cell: TagCell as never,
    customMeta: {
      colored: false,
    },
    size: 100,
  },
  {
    id: "name",
    label: "Key",
    type: COLUMN_TYPE.string,
    size: 100,
    cell: TagCell as never,
  },
  {
    id: "value",
    label: "Score",
    type: COLUMN_TYPE.number,
    cell: FeedbackScoreValueCell as never,
    size: 100,
  },
  {
    id: "created_by",
    label: "Created by",
    type: COLUMN_TYPE.string,
    size: 100,
  },
];

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
  const columns = useMemo(() => {
    const retVal = convertColumnDataToColumn<
      TraceFeedbackScore,
      TraceFeedbackScore
    >(DEFAULT_COLUMNS, {});

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
  }, [traceId, spanId]);

  const feedbackScores: TraceFeedbackScore[] = useMemo(
    () => sortBy(data.feedback_scores || [], "name"),
    [data.feedback_scores],
  );

  return (
    <DataTable
      columns={columns}
      data={feedbackScores}
      noData={<DataTableNoData title="No feedback scores" />}
    />
  );
};

export default FeedbackScoreTab;
