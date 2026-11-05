import {
  useMetricDateRangeWithQueryAndStorage,
  useIntervalBounds,
  DATE_RANGE_PRESET_ALLTIME,
} from "@/v2/pages-shared/traces/MetricDateRangeSelect";
import { ProjectDateRangeConfig } from "@/v2/pages-shared/traces/resolveProjectDateRangeConfig";

const useLogsIntervalWindow = (
  dateRangeConfig: ProjectDateRangeConfig,
  isAutoReanchorEnabled = true,
) => {
  const { dateRange } = useMetricDateRangeWithQueryAndStorage({
    excludePresets: [DATE_RANGE_PRESET_ALLTIME],
    ...dateRangeConfig,
  });

  return useIntervalBounds(dateRange, isAutoReanchorEnabled);
};

export default useLogsIntervalWindow;
