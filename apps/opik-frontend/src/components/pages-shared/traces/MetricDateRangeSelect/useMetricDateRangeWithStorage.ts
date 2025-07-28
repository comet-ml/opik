import useLocalStorageState from "use-local-storage-state";
import { DateRangeValue } from "@/components/shared/DateRangeSelect";
import {
  useMetricDateRangeCore,
  UseMetricDateRangeOptions,
} from "./useMetricDateRangeCore";
import { serializeDateRange } from "./utils";
import { DEFAULT_METRICS_DATE_RANGE } from "./constants";

type UseMetricDateRangeWithStorageOptions = UseMetricDateRangeOptions & {
  key: string;
  defaultDateRange?: DateRangeValue;
};

export const useMetricDateRangeWithStorage = (
  options: UseMetricDateRangeWithStorageOptions,
) => {
  const {
    key,
    defaultDateRange = DEFAULT_METRICS_DATE_RANGE,
    ...rest
  } = options;

  const defaultSerialized = serializeDateRange(defaultDateRange);

  const [value, setValue] = useLocalStorageState<string>(key, {
    defaultValue: defaultSerialized,
  });

  return useMetricDateRangeCore({
    value,
    setValue,
    defaultDateRange,
    ...rest,
  });
};
