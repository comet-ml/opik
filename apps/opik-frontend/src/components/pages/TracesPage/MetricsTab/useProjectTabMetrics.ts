import useProjectMetric from "@/api/projects/useProjectMetric";

interface UseProjectTabMetricsProps {
  projectId: string;
}

const useProjectTabMetrics = ({ projectId }: UseProjectTabMetricsProps) => {
  const { data: numberOfTraces } = useProjectMetric({
    projectId: projectId,
    metricName: "TRACE_COUNT",
    interval: "HOURLY",
    interval_start: "2024-11-24T12:36:26Z",
    interval_end: "2024-11-26T12:36:26Z",
  });

  return { numberOfTraces };
};

export default useProjectTabMetrics;
