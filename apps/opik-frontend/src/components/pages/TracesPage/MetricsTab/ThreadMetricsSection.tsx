import React, { useMemo } from "react";
import { JsonParam, useQueryParam } from "use-query-params";

import {
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_ID_ID,
  COLUMN_TYPE,
  ColumnData,
} from "@/types/shared";
import { Thread } from "@/types/traces";
import {
  METRIC_NAME_TYPE,
  INTERVAL_TYPE,
} from "@/api/projects/useProjectMetric";
import FiltersButton from "@/components/shared/FiltersButton/FiltersButton";
import ThreadsFeedbackScoresSelect from "@/components/pages-shared/traces/TracesOrSpansFeedbackScoresSelect/ThreadsFeedbackScoresSelect";

import MetricContainerChart from "./MetricChart/MetricChartContainer";
import { CHART_TYPE } from "@/constants/chart";
import {
  DURATION_LABELS_MAP,
  INTERVAL_DESCRIPTIONS,
  renderDurationTooltipValue,
  durationYTickFormatter,
  tokenYTickFormatter,
} from "./utils";
import { renderScoreTooltipValue } from "@/lib/feedback-scores";

const THREAD_FILTER_COLUMNS: ColumnData<Thread>[] = [
  {
    id: COLUMN_ID_ID,
    label: "ID",
    type: COLUMN_TYPE.string,
  },
  {
    id: "first_message",
    label: "First message",
    type: COLUMN_TYPE.string,
  },
  {
    id: "last_message",
    label: "Last message",
    type: COLUMN_TYPE.string,
  },
  {
    id: "number_of_messages",
    label: "Message count",
    type: COLUMN_TYPE.number,
  },
  {
    id: "status",
    label: "Status",
    type: COLUMN_TYPE.category,
  },
  {
    id: "created_at",
    label: "Created at",
    type: COLUMN_TYPE.time,
  },
  {
    id: "last_updated_at",
    label: "Last updated",
    type: COLUMN_TYPE.time,
  },
  {
    id: "duration",
    label: "Duration",
    type: COLUMN_TYPE.duration,
  },
  {
    id: "tags",
    label: "Tags",
    type: COLUMN_TYPE.list,
    iconType: "tags",
  },
  {
    id: "start_time",
    label: "Start time",
    type: COLUMN_TYPE.time,
  },
  {
    id: "end_time",
    label: "End time",
    type: COLUMN_TYPE.time,
  },
  {
    id: COLUMN_FEEDBACK_SCORES_ID,
    label: "Feedback scores",
    type: COLUMN_TYPE.numberDictionary,
  },
];

interface ThreadMetricsSectionProps {
  projectId: string;
  interval: INTERVAL_TYPE;
  intervalStart: string | undefined;
  intervalEnd: string | undefined;
  hasThreads: boolean;
}

const ThreadMetricsSection: React.FC<ThreadMetricsSectionProps> = ({
  projectId,
  interval,
  intervalStart,
  intervalEnd,
  hasThreads,
}) => {
  const [threadFilters = [], setThreadFilters] = useQueryParam(
    "threads_metrics_filters",
    JsonParam,
    {
      updateType: "replaceIn",
    },
  );

  const threadFiltersConfig = useMemo(
    () => ({
      rowsMap: {
        [COLUMN_FEEDBACK_SCORES_ID]: {
          keyComponent: ThreadsFeedbackScoresSelect as React.FC<unknown> & {
            placeholder: string;
            value: string;
            onValueChange: (value: string) => void;
          },
          keyComponentProps: {
            projectId,
            placeholder: "Select score",
          },
        },
      },
    }),
    [projectId],
  );

  if (!hasThreads) {
    return null;
  }

  return (
    <div className="pt-6">
      <div className="sticky top-0 z-10 flex items-center justify-between bg-soft-background pb-3 pt-2">
        <h2 className="comet-title-s truncate break-words">Thread metrics</h2>
        <FiltersButton
          columns={THREAD_FILTER_COLUMNS}
          filters={threadFilters}
          onChange={setThreadFilters}
          config={threadFiltersConfig}
        />
      </div>
      <div
        className="grid grid-cols-1 gap-4 md:grid-cols-2"
        style={{ "--chart-height": "230px" } as React.CSSProperties}
      >
        <div>
          <MetricContainerChart
            chartId="threads_feedback_scores_chart"
            key="threads_feedback_scores_chart"
            name="Threads feedback scores"
            description={INTERVAL_DESCRIPTIONS.AVERAGES[interval]}
            metricName={METRIC_NAME_TYPE.THREAD_FEEDBACK_SCORES}
            interval={interval}
            intervalStart={intervalStart}
            intervalEnd={intervalEnd}
            projectId={projectId}
            renderValue={renderScoreTooltipValue}
            chartType={CHART_TYPE.line}
            threadFilters={threadFilters}
          />
        </div>
        <div>
          <MetricContainerChart
            chartId="number_of_thread_chart"
            key="number_of_thread_chart"
            name="Number of threads"
            description={INTERVAL_DESCRIPTIONS.TOTALS[interval]}
            metricName={METRIC_NAME_TYPE.THREAD_COUNT}
            interval={interval}
            intervalStart={intervalStart}
            intervalEnd={intervalEnd}
            projectId={projectId}
            customYTickFormatter={tokenYTickFormatter}
            chartType={CHART_TYPE.line}
            threadFilters={threadFilters}
          />
        </div>
        <div className="md:col-span-2">
          <MetricContainerChart
            chartId="thread_duration_chart"
            key="thread_duration_chart"
            name="Thread duration"
            description={INTERVAL_DESCRIPTIONS.QUANTILES[interval]}
            metricName={METRIC_NAME_TYPE.THREAD_DURATION}
            interval={interval}
            intervalStart={intervalStart}
            intervalEnd={intervalEnd}
            projectId={projectId}
            renderValue={renderDurationTooltipValue}
            labelsMap={DURATION_LABELS_MAP}
            customYTickFormatter={durationYTickFormatter}
            chartType={CHART_TYPE.line}
            threadFilters={threadFilters}
          />
        </div>
      </div>
    </div>
  );
};

export default ThreadMetricsSection;
