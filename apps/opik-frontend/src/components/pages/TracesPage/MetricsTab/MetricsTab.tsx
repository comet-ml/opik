import { Button } from "@/components/ui/button";
import { ChartLine as ChartLineIcon } from "lucide-react";
import React, { useRef, useState } from "react";
import RequestChartDialog from "@/components/pages/TracesPage/MetricsTab/RequestChartDialog/RequestChartDialog";
import useTracesList from "@/api/traces/useTracesList";
import useThreadList from "@/api/traces/useThreadsList";
import NoTracesPage from "@/components/pages/TracesPage/NoTracesPage";
import {
  useMetricDateRangeWithQueryAndStorage,
  MetricDateRangeSelect,
} from "@/components/pages-shared/traces/MetricDateRangeSelect";
import ProjectMetricsSection from "./ProjectMetricsSection";
import ThreadMetricsSection from "./ThreadMetricsSection";
import TraceMetricsSection from "./TraceMetricsSection";

const METRICS_TAB_DATE_RANGE_QUERY_KEY = "metrics_time_range";

interface MetricsTabProps {
  projectId: string;
}

const MetricsTab = ({ projectId }: MetricsTabProps) => {
  const [requestChartOpen, setRequestChartOpen] = useState(false);

  const {
    dateRange,
    handleDateRangeChange,
    interval,
    intervalStart,
    intervalEnd,
    minDate,
    maxDate,
  } = useMetricDateRangeWithQueryAndStorage({
    key: METRICS_TAB_DATE_RANGE_QUERY_KEY,
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

  return (
    <div className="px-6 pb-6">
      <div className="flex items-center justify-between">
        <Button
          variant="outline"
          size="sm"
          onClick={() => setRequestChartOpen(true)}
        >
          <ChartLineIcon className="mr-1.5 size-3.5" />
          Request a chart
        </Button>
        <MetricDateRangeSelect
          value={dateRange}
          onChangeValue={handleDateRangeChange}
          minDate={minDate}
          maxDate={maxDate}
          hideAlltime={true}
        />
      </div>

      <ProjectMetricsSection
        projectId={projectId}
        interval={interval}
        intervalStart={intervalStart}
        intervalEnd={intervalEnd}
        hasTraces={hasTraces}
      />

      <ThreadMetricsSection
        projectId={projectId}
        interval={interval}
        intervalStart={intervalStart}
        intervalEnd={intervalEnd}
        hasThreads={hasThreads}
      />

      <TraceMetricsSection
        projectId={projectId}
        interval={interval}
        intervalStart={intervalStart}
        intervalEnd={intervalEnd}
        hasTraces={hasTraces}
      />

      <RequestChartDialog
        key={`request-chart-${resetKeyRef.current}`}
        open={requestChartOpen}
        setOpen={handleRequestChartOpen}
      />
    </div>
  );
};

export default MetricsTab;
