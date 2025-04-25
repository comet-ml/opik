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
import useTracesOrSpansList, {
  TRACE_DATA_TYPE,
} from "@/hooks/useTracesOrSpansList";
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
}: ChartTooltipRenderValueArguments) => formatDuration(value as number);

const durationYTickFormatter = (value: number) => formatDuration(value);

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

  // to show if there is no data
  const { data: traces } = useTracesOrSpansList(
    {
      projectId,
      type: TRACE_DATA_TYPE.traces,
      filters: [],
      page: 1,
      size: 1,
      search: "",
      truncate: true,
    },
    {
      refetchInterval: 30000,
    },
  );

  const resetKeyRef = useRef(0);
  const numDays = Number(days);
  const isValidDays = POSSIBLE_DAYS_OPTIONS.includes(days as DAYS_OPTION_TYPE);

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

  if (traces?.total === 0) {
    return <NoTracesPage />;
  }

  return (
    <>
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
          className="flex flex-col gap-4 py-4"
          style={{ "--chart-height": "230px" } as React.CSSProperties}
        >
          <div className="flex flex-col gap-4 md:flex-row">
            <div className="flex-1">
              <MetricContainerChart
                chartId="feedback_scores_chart"
                name="Feedback scores"
                description="Daily averages"
                metricName={METRIC_NAME_TYPE.FEEDBACK_SCORES}
                interval={interval}
                intervalStart={intervalStart}
                intervalEnd={intervalEnd}
                projectId={projectId}
                disableLoadingData={!isValidDays}
                chartType="line"
              />
            </div>
            <div className="flex-1">
              <MetricContainerChart
                chartId="number_of_traces_chart"
                name="Number of traces"
                description="Daily totals"
                metricName={METRIC_NAME_TYPE.TRACE_COUNT}
                interval={interval}
                intervalStart={intervalStart}
                intervalEnd={intervalEnd}
                projectId={projectId}
                disableLoadingData={!isValidDays}
                chartType="line"
              />
            </div>
          </div>

          <div className="flex flex-col gap-4 md:flex-row">
            <div className="flex-1">
              <MetricContainerChart
                chartId="duration_chart"
                name="Duration"
                description="Daily quantiles in seconds"
                metricName={METRIC_NAME_TYPE.DURATION}
                interval={interval}
                intervalStart={intervalStart}
                intervalEnd={intervalEnd}
                projectId={projectId}
                disableLoadingData={!isValidDays}
                renderValue={renderDurationTooltipValue}
                labelsMap={DURATION_LABELS_MAP}
                customYTickFormatter={durationYTickFormatter}
                chartType="line"
              />
            </div>

            <div className="flex-1">
              <MetricContainerChart
                chartId="token_usage_chart"
                name="Token usage"
                description="Daily totals"
                metricName={METRIC_NAME_TYPE.TOKEN_USAGE}
                interval={interval}
                intervalStart={intervalStart}
                intervalEnd={intervalEnd}
                projectId={projectId}
                disableLoadingData={!isValidDays}
                chartType="line"
              />
            </div>
          </div>
          <div className="flex flex-col gap-4 md:flex-row">
            <div className="flex-1">
              <MetricContainerChart
                chartId="estimated_cost_chart"
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
              />
            </div>
            {isGuardrailsEnabled && (
              <div className="flex-1">
                <MetricContainerChart
                  chartId="failed_guardrails_chart"
                  name="Failed guardrails"
                  description="Daily totals"
                  metricName={METRIC_NAME_TYPE.FAILED_GUARDRAILS}
                  interval={interval}
                  intervalStart={intervalStart}
                  intervalEnd={intervalEnd}
                  projectId={projectId}
                  disableLoadingData={!isValidDays}
                  chartType="bar"
                />
              </div>
            )}
          </div>
        </div>
      </div>
      <RequestChartDialog
        key={`request-chart-${resetKeyRef.current}`}
        open={requestChartOpen}
        setOpen={handleRequestChartOpen}
      />
    </>
  );
};

export default MetricsTab;
