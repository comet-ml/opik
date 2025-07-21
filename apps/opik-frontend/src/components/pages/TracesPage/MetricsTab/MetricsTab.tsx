import { Button } from "@/components/ui/button";
import { ChartLine as ChartLineIcon } from "lucide-react";
import React, { useEffect, useMemo, useRef, useState } from "react";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import {
  INTERVAL_TYPE,
  METRIC_NAME_TYPE,
} from "@/api/projects/useProjectMetric";
import dayjs from "dayjs";
import { StringParam, useQueryParam, withDefault } from "use-query-params";
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

enum DAYS_OPTION_TYPE {
  ONE_DAY = "1",
  THREE_DAYS = "3",
  SEVEN_DAYS = "7",
  THIRTY_DAYS = "30",
}

const DAYS_OPTIONS = [
  {
    value: DAYS_OPTION_TYPE.ONE_DAY,
    label: "1 day",
  },
  {
    value: DAYS_OPTION_TYPE.THREE_DAYS,
    label: "3 days",
  },
  {
    value: DAYS_OPTION_TYPE.SEVEN_DAYS,
    label: "7 days",
  },
  {
    value: DAYS_OPTION_TYPE.THIRTY_DAYS,
    label: "30 days",
  },
];

const DURATION_LABELS_MAP = {
  "duration.p50": "Percentile 50",
  "duration.p90": "Percentile 90",
  "duration.p99": "Percentile 99",
};

const POSSIBLE_DAYS_OPTIONS = Object.values(DAYS_OPTION_TYPE);
const DEFAULT_DAYS_VALUE = DAYS_OPTION_TYPE.THIRTY_DAYS;

const nowUTC = dayjs().utc();
const intervalEnd = nowUTC.format();

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
  const [days, setDays] = useQueryParam(
    "days",
    withDefault(StringParam, DEFAULT_DAYS_VALUE),
  );
  const [requestChartOpen, setRequestChartOpen] = useState(false);
  const isGuardrailsEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.GUARDRAILS_ENABLED,
  );

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
  const numDays = Number(days);
  const isValidDays = POSSIBLE_DAYS_OPTIONS.includes(days as DAYS_OPTION_TYPE);
  const hasTraces = Boolean(traces?.total);
  const hasThreads = Boolean(threads?.total);

  const interval: INTERVAL_TYPE = useMemo(() => {
    if (numDays <= 3) {
      return INTERVAL_TYPE.HOURLY;
    }

    return INTERVAL_TYPE.DAILY;
  }, [numDays]);

  const intervalStart = useMemo(() => {
    const startOf = numDays === 1 ? "hour" : "day";

    return nowUTC.subtract(numDays, "days").startOf(startOf).format();
  }, [numDays]);

  const handleRequestChartOpen = (val: boolean) => {
    setRequestChartOpen(val);
    resetKeyRef.current += 1;
  };

  useEffect(() => {
    if (!isValidDays) {
      setDays(DEFAULT_DAYS_VALUE);
    }
  }, [isValidDays, setDays]);

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
              disableLoadingData={!isValidDays}
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
              disableLoadingData={!isValidDays}
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
              disableLoadingData={!isValidDays}
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
              disableLoadingData={!isValidDays}
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
              disableLoadingData={!isValidDays}
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
              disableLoadingData={!isValidDays}
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
              disableLoadingData={!isValidDays}
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
              disableLoadingData={!isValidDays}
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
              disableLoadingData={!isValidDays}
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

        <div className="w-48">
          <SelectBox
            value={days}
            onChange={setDays}
            options={DAYS_OPTIONS}
            className="h-8"
          />
        </div>
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
