import { Button } from "@/components/ui/button";
import { ChartLine as ChartLineIcon } from "lucide-react";
import React, { useEffect, useMemo, useRef, useState } from "react";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import MetricChart from "@/components/pages/TracesPage/MetricsTab/MetricChart/MetricChart";
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

const POSSIBLE_DAYS_OPTIONS = Object.values(DAYS_OPTION_TYPE);
const DEFAULT_DAYS_VALUE = DAYS_OPTION_TYPE.THIRTY_DAYS;

const nowUTC = dayjs().utc();
const intervalEnd = nowUTC.format();

interface MetricsTabProps {
  projectId: string;
}

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
                metricName={METRIC_NAME_TYPE.FEEDBACK_SCORES}
                interval={interval}
                intervalStart={intervalStart}
                intervalEnd={intervalEnd}
                projectId={projectId}
                disableLoadingData={!isValidDays}
              />
            </div>
            <div className="flex-1">
              <MetricChart
                name="Number of traces"
                metricName={METRIC_NAME_TYPE.TRACE_COUNT}
                interval={interval}
                intervalStart={intervalStart}
                intervalEnd={intervalEnd}
                projectId={projectId}
                disableLoadingData={!isValidDays}
              />
            </div>
          </div>

          <div className="flex flex-col gap-4 md:flex-row">
            <div className="flex-1">
              <MetricChart
                name="Token usage"
                metricName={METRIC_NAME_TYPE.TOKEN_USAGE}
                interval={interval}
                intervalStart={intervalStart}
                intervalEnd={intervalEnd}
                projectId={projectId}
                disableLoadingData={!isValidDays}
              />
            </div>

            <div className="flex-1">
              <MetricChart
                name="Estimated cost"
                metricName={METRIC_NAME_TYPE.COST}
                interval={interval}
                intervalStart={intervalStart}
                intervalEnd={intervalEnd}
                projectId={projectId}
                disableLoadingData={!isValidDays}
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
