import { Button } from "@/components/ui/button";
import { ChartLine as ChartLineIcon } from "lucide-react";
import React, { useEffect, useMemo, useRef, useState } from "react";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import MetricChart from "@/components/pages/TracesPage/MetricsTab/MetricChart/MetricChart";
import useProjectMetric, {
  IntervalType,
} from "@/api/projects/useProjectMetric";
import last from "lodash/last";
import dayjs from "dayjs";
import { StringParam, useQueryParam, withDefault } from "use-query-params";
import RequestChartDialog from "@/components/pages/TracesPage/MetricsTab/RequestChartDialog/RequestChartDialog";
import useTracesOrSpansList, {
  TRACE_DATA_TYPE,
} from "@/hooks/useTracesOrSpansList";
import NoTracesPage from "@/components/pages/TracesPage/NoTracesPage";

const DAYS_OPTIONS = [
  {
    value: "1",
    label: "1 day",
  },
  {
    value: "3",
    label: "3 days",
  },
  {
    value: "7",
    label: "7 days",
  },
  {
    value: "30",
    label: "30 days",
  },
];

const POSSIBLE_DAYS_OPTIONS = DAYS_OPTIONS.map((dayOption) => dayOption.value);

const DEFAULT_DAYS_VALUE = last(DAYS_OPTIONS)!.value;

interface MetricsTabProps {
  projectId: string;
}

const nowUTC = dayjs().utc();
const intervalEnd = nowUTC.format();

const MetricsTab = ({ projectId }: MetricsTabProps) => {
  const [days, setDays] = useQueryParam(
    "days",
    withDefault(StringParam, DEFAULT_DAYS_VALUE),
  );
  const [requestChartOpen, setRequestChartOpen] = useState(false);

  // to show if there is no data
  const { data: traces } = useTracesOrSpansList(
    {
      projectId,
      type: "traces" as TRACE_DATA_TYPE,
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
  const isValidDays = POSSIBLE_DAYS_OPTIONS.includes(days);

  const interval: IntervalType = useMemo(() => {
    if (numDays <= 3) {
      return "HOURLY";
    }

    return "DAILY";
  }, [numDays]);

  const intervalStart = useMemo(() => {
    const startOf = numDays === 1 ? "hour" : "day";

    return nowUTC.subtract(numDays, "days").startOf(startOf).format();
  }, [numDays]);

  const { data: numberOfTraces, isPending: isNumberOfTracesPending } =
    useProjectMetric(
      {
        projectId,
        metricName: "TRACE_COUNT",
        interval,
        interval_start: intervalStart,
        interval_end: intervalEnd,
      },
      {
        enabled: !!projectId && isValidDays,
        refetchInterval: 30000,
      },
    );

  const { data: feedbackScores, isPending: isFeedbackScoresPending } =
    useProjectMetric(
      {
        projectId,
        metricName: "FEEDBACK_SCORES",
        interval,
        interval_start: intervalStart,
        interval_end: intervalEnd,
      },
      {
        enabled: !!projectId && isValidDays,
        refetchInterval: 30000,
      },
    );

  const { data: tokenUsage, isPending: isTokenUsagePending } = useProjectMetric(
    {
      projectId,
      metricName: "TOKEN_USAGE",
      interval,
      interval_start: intervalStart,
      interval_end: intervalEnd,
    },
    {
      enabled: !!projectId && isValidDays,
      refetchInterval: 30000,
    },
  );

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
      <div>
        <div className="flex items-center justify-between">
          <Button variant="outline" onClick={() => setRequestChartOpen(true)}>
            <ChartLineIcon className="mr-2 size-4" />
            Request a chart
          </Button>

          <div className="w-48">
            <SelectBox value={days} onChange={setDays} options={DAYS_OPTIONS} />
          </div>
        </div>

        <div
          className="flex flex-col gap-4 py-4"
          style={{ "--chart-height": "230px" } as React.CSSProperties}
        >
          <div className="flex flex-col gap-4 md:flex-row">
            <div className="flex-1">
              <MetricChart
                name="Feedback scores"
                traces={feedbackScores || []}
                loading={isFeedbackScoresPending}
              />
            </div>
            <div className="flex-1">
              <MetricChart
                name="Number of traces"
                traces={numberOfTraces || []}
                loading={isNumberOfTracesPending}
              />
            </div>
          </div>

          <div className="flex flex-col gap-4 md:flex-row">
            <div className="flex-1">
              <MetricChart
                name="Token usage"
                traces={tokenUsage || []}
                loading={isTokenUsagePending}
              />
            </div>
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
