import React from "react";
import { CellContext } from "@tanstack/react-table";
import { MessageSquareMore } from "lucide-react";
import isNumber from "lodash/isNumber";
import isFunction from "lodash/isFunction";

import { formatNumericData } from "@/lib/utils";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import FeedbackScoreReasonTooltip from "../FeedbackScoreTag/FeedbackScoreReasonTooltip";
import { TraceFeedbackScore } from "@/types/traces";

const FeedbackScoreCell = (context: CellContext<unknown, unknown>) => {
  const feedbackScore = context.getValue() as TraceFeedbackScore | undefined;

  const value = feedbackScore ? feedbackScore.value : "-";
  const reason = feedbackScore?.reason;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="gap-1"
    >
      <div className="truncate">{value}</div>
      {reason && (
        <FeedbackScoreReasonTooltip
          reason={reason}
          lastUpdatedAt={feedbackScore.last_updated_at}
          lastUpdatedBy={feedbackScore.last_updated_by}
        >
          <div className="flex h-[20px] items-center">
            <MessageSquareMore className="mt-0.5 size-3.5 shrink-0 text-light-slate" />
          </div>
        </FeedbackScoreReasonTooltip>
      )}
    </CellWrapper>
  );
};

type CustomMeta = {
  accessorFn?: string;
  dataFormatter?: (value: number) => string;
};

const FeedbackScoreAggregationCell = <TData,>(
  context: CellContext<TData, string>,
) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { accessorFn, dataFormatter = formatNumericData } = (custom ??
    {}) as CustomMeta;

  const rowId = context.row.id;
  const { aggregationMap } = context.table.options.meta ?? {};

  const data = aggregationMap?.[rowId] ?? {};
  const rawValue = isFunction(accessorFn) ? accessorFn(data) : undefined;
  let value = "-";

  if (isNumber(rawValue)) {
    value = dataFormatter(rawValue);
  }

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <span className="truncate text-light-slate">{value}</span>
    </CellWrapper>
  );
};

FeedbackScoreCell.Aggregation = FeedbackScoreAggregationCell;

export default FeedbackScoreCell;
