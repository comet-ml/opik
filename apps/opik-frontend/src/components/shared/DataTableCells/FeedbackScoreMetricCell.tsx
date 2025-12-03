import React from "react";
import { CellContext } from "@tanstack/react-table";
import isNumber from "lodash/isNumber";
import isFunction from "lodash/isFunction";
import useLocalStorageState from "use-local-storage-state";

import { formatNumericData } from "@/lib/utils";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { FeedbackScoreMetric } from "@/types/datasets";
import { FEEDBACK_SCORE_PERCENTILE_STORAGE_KEY_PREFIX } from "@/components/shared/DataTableHeaders/FeedbackScoreMetricsHeader";
import { ExperimentsAggregations } from "@/types/datasets";

export type PercentileValue = "p50" | "p90" | "p99";

type CustomMeta = {
  feedbackKey?: string;
};

const FeedbackScoreMetricCell = <TData,>(
  context: CellContext<TData, unknown>,
) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { feedbackKey } = (custom ?? {}) as CustomMeta;

  const storageKey = `${FEEDBACK_SCORE_PERCENTILE_STORAGE_KEY_PREFIX}-${feedbackKey}`;
  const [selectedPercentile] = useLocalStorageState<PercentileValue>(
    storageKey,
    {
      defaultValue: "p50",
    },
  );

  // Get metrics from the row
  const metrics = (
    context.row.original as { feedback_score_metrics?: FeedbackScoreMetric[] }
  ).feedback_score_metrics;

  const metric = metrics?.find((m) => m.name === feedbackKey);
  const percentileValue = metric?.values?.[selectedPercentile];

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {isNumber(percentileValue) ? formatNumericData(percentileValue) : "-"}
    </CellWrapper>
  );
};

type AggregationCustomMeta = {
  feedbackKey?: string;
  accessorFn?: (data: ExperimentsAggregations) => number | undefined;
  dataFormatter?: (value: number) => string;
};

const FeedbackScoreMetricAggregationCell = <TData,>(
  context: CellContext<TData, string>,
) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const {
    feedbackKey,
    accessorFn,
    dataFormatter = formatNumericData,
  } = (custom ?? {}) as AggregationCustomMeta;

  const storageKey = `${FEEDBACK_SCORE_PERCENTILE_STORAGE_KEY_PREFIX}-${feedbackKey}`;
  const [selectedPercentile] = useLocalStorageState<PercentileValue>(
    storageKey,
    {
      defaultValue: "p50",
    },
  );

  const rowId = context.row.id;
  const { aggregationMap } = context.table.options.meta ?? {};

  const data = aggregationMap?.[rowId] ?? {};

  // First try to get from feedback_score_metrics
  const metrics = (data as { feedback_score_metrics?: FeedbackScoreMetric[] })
    .feedback_score_metrics;
  const metric = metrics?.find((m) => m.name === feedbackKey);
  const percentileValue = metric?.values?.[selectedPercentile];

  if (isNumber(percentileValue)) {
    return (
      <CellWrapper
        metadata={context.column.columnDef.meta}
        tableMetadata={context.table.options.meta}
      >
        <span className="truncate text-light-slate">
          {dataFormatter(percentileValue)}
        </span>
      </CellWrapper>
    );
  }

  // Fall back to accessorFn for average scores
  const rawValue = isFunction(accessorFn)
    ? accessorFn(data as ExperimentsAggregations)
    : undefined;
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

FeedbackScoreMetricCell.Aggregation = FeedbackScoreMetricAggregationCell;

export default FeedbackScoreMetricCell;
