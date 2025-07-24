import { Button } from "@/components/ui/button";
import { ChartLine as ChartLineIcon } from "lucide-react";
import React, { useRef, useState } from "react";
import { METRIC_NAME_TYPE } from "@/api/projects/useProjectMetric";
import RequestChartDialog from "@/components/pages/TracesPage/MetricsTab/RequestChartDialog/RequestChartDialog";
import useTracesList from "@/api/traces/useTracesList";
import useThreadList from "@/api/traces/useThreadsList";
import NoTracesPage from "@/components/pages/TracesPage/NoTracesPage";
import { ChartTooltipRenderValueArguments } from "@/components/shared/ChartTooltipContent/ChartTooltipContent";
import { formatCost } from "@/lib/money";
import { formatDuration } from "@/lib/date";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import MetricContainerChart from "./MetricChart/MetricChartContainer";
import {
  useMetricDateRangeWithQuery,
  MetricDateRangeSelect,
} from "@/components/pages-shared/traces/MetricDateRangeSelect";

const DURATION_LABELS_MAP = {
  "duration.p50": "Percentile 50",
  "duration.p90": "Percentile 90",
  "duration.p99": "Percentile 99",
};

const METRICS_DATE_RANGE_KEY = "range";

const renderCostTooltipValue = ({ value }: ChartTooltipRenderValueArguments) =>
  formatCost(value as number);

const renderDurationTooltipValue = ({
  value,
}: ChartTooltipRenderValueArguments) => formatDuration(value as number, false);

const durationYTickFormatter = (value: number) => formatDuration(value, false);

interface MetricsTabProps {
  projectId: string;
}

const MetricsTab = ({ projectId }: MetricsTabProps) => {
  const [requestChartOpen, setRequestChartOpen] = useState(false);
  const isGuardrailsEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.GUARDRAILS_ENABLED,
  );

  const {
    dateRange,
    handleDateRangeChange,
    interval,
    intervalStart,
    intervalEnd,
    minDate,
    maxDate,
  } = useMetricDateRangeWithQuery({
    key: METRICS_DATE_RANGE_KEY,
  });

  const { data: traces } = useTracesList(
    {
      projectId,
      page: 1,
      size: 1,
      truncate: true,
    },
    {
      refetchInterval: 30000,
    },
  );

  const { data: threads } = useThreadList(
    {
      projectId,
      page: 1,
      size: 1,
      truncate: true,
    },
    {
      refetchInterval: 30000,
    },
  );

  const resetKeyRef = useRef(0);
  const hasTraces = Boolean(traces?.total);
  const hasThreads = Boolean(threads?.total);

  const handleRequestChartOpen = (val: boolean) => {
    setRequestChartOpen(val);
    resetKeyRef.current += 1;
  };

  if (!hasTraces && !hasThreads) {
    return <NoTracesPage />;
  }

  const renderCharts = () => {
    const charts = [
      ...(hasThreads
        ? [
            <MetricContainerChart
              chartId="threads_feedback_scores_chart"
              key="threads_feedback_scores_chart"
              name="Threads feedback scores"
              description="Daily averages"
              metricName={METRIC_NAME_TYPE.THREAD_FEEDBACK_SCORES}
              interval={interval}
              intervalStart={intervalStart}
              intervalEnd={intervalEnd}
              projectId={projectId}
              chartType="line"
            />,
            <MetricContainerChart
              chartId="number_of_thread_chart"
              key="number_of_thread_chart"
              name="Number of threads"
              description="Daily totals"
              metricName={METRIC_NAME_TYPE.THREAD_COUNT}
              interval={interval}
              intervalStart={intervalStart}
              intervalEnd={intervalEnd}
              projectId={projectId}
              chartType="line"
            />,
            <MetricContainerChart
              chartId="thread_duration_chart"
              key="thread_duration_chart"
              name="Thread duration"
              description="Daily quantiles in seconds"
              metricName={METRIC_NAME_TYPE.THREAD_DURATION}
              interval={interval}
              intervalStart={intervalStart}
              intervalEnd={intervalEnd}
              projectId={projectId}
              renderValue={renderDurationTooltipValue}
              labelsMap={DURATION_LABELS_MAP}
              customYTickFormatter={durationYTickFormatter}
              chartType="line"
            />,
          ]
        : []),
      ...(hasTraces
        ? [
            <MetricContainerChart
              chartId="feedback_scores_chart"
              key="feedback_scores_chart"
              name="Trace feedback scores"
              description="Daily averages"
              metricName={METRIC_NAME_TYPE.FEEDBACK_SCORES}
              interval={interval}
              intervalStart={intervalStart}
              intervalEnd={intervalEnd}
              projectId={projectId}
              chartType="line"
            />,
            <MetricContainerChart
              chartId="number_of_traces_chart"
              key="number_of_traces_chart"
              name="Number of traces"
              description="Daily totals"
              metricName={METRIC_NAME_TYPE.TRACE_COUNT}
              interval={interval}
              intervalStart={intervalStart}
              intervalEnd={intervalEnd}
              projectId={projectId}
              chartType="line"
            />,
            <MetricContainerChart
              chartId="duration_chart"
              key="duration_chart"
              name="Trace duration"
              description="Daily quantiles in seconds"
              metricName={METRIC_NAME_TYPE.TRACE_DURATION}
              interval={interval}
              intervalStart={intervalStart}
              intervalEnd={intervalEnd}
              projectId={projectId}
              renderValue={renderDurationTooltipValue}
              labelsMap={DURATION_LABELS_MAP}
              customYTickFormatter={durationYTickFormatter}
              chartType="line"
            />,
            <MetricContainerChart
              chartId="token_usage_chart"
              key="token_usage_chart"
              name="Token usage"
              description="Daily totals"
              metricName={METRIC_NAME_TYPE.TOKEN_USAGE}
              interval={interval}
              intervalStart={intervalStart}
              intervalEnd={intervalEnd}
              projectId={projectId}
              chartType="line"
            />,
            <MetricContainerChart
              chartId="estimated_cost_chart"
              key="estimated_cost_chart"
              name="Estimated cost"
              description="Total daily cost in USD"
              metricName={METRIC_NAME_TYPE.COST}
              interval={interval}
              intervalStart={intervalStart}
              intervalEnd={intervalEnd}
              projectId={projectId}
              renderValue={renderCostTooltipValue}
              chartType="line"
            />,
          ]
        : []),
      ...(hasTraces && isGuardrailsEnabled
        ? [
            <MetricContainerChart
              chartId="failed_guardrails_chart"
              key="failed_guardrails_chart"
              name="Failed guardrails"
              description="Daily totals"
              metricName={METRIC_NAME_TYPE.FAILED_GUARDRAILS}
              interval={interval}
              intervalStart={intervalStart}
              intervalEnd={intervalEnd}
              projectId={projectId}
              chartType="bar"
            />,
          ]
        : []),
    ];
    return charts.map((chart, index) => (
      <div
        key={chart.key}
        className={
          charts.length % 2 === 1 && index === charts.length - 1
            ? "md:col-span-2"
            : ""
        }
      >
        {chart}
      </div>
    ));
  };

  return (
    <div className="px-6">
      <div className="flex items-center justify-between">
        <Button
          variant="outline"
          size="sm"
          onClick={() => setRequestChartOpen(true)}
        >
          <ChartLineIcon className="mr-2 size-3.5" />
          Request a chart
        </Button>
        <MetricDateRangeSelect
          value={dateRange}
          onChangeValue={handleDateRangeChange}
          minDate={minDate}
          maxDate={maxDate}
        />
      </div>
      <div
        className="grid grid-cols-1 gap-4 py-4 md:grid-cols-2"
        style={{ "--chart-height": "230px" } as React.CSSProperties}
      >
        {renderCharts()}
      </div>
      <RequestChartDialog
        key={`request-chart-${resetKeyRef.current}`}
        open={requestChartOpen}
        setOpen={handleRequestChartOpen}
      />
    </div>
  );
};

export default MetricsTab;
