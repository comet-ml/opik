import { useQueryParam, withDefault, StringParam } from "use-query-params";
import { DateRangeValue } from "@/components/shared/DateRangeSelect";
import {
  useMetricDateRangeCore,
  UseMetricDateRangeOptions,
} from "./useMetricDateRangeCore";
import { serializeDateRange } from "./utils";
import { DEFAULT_METRICS_DATE_RANGE } from "./constants";

type UseMetricDateRangeWithQueryOptions = UseMetricDateRangeOptions & {
  key: string;
  defaultDateRange?: DateRangeValue;
};

export const useMetricDateRangeWithQuery = (
  options: UseMetricDateRangeWithQueryOptions,
) => {
  const {
    key,
    defaultDateRange = DEFAULT_METRICS_DATE_RANGE,
    ...rest
  } = options;

  const defaultSerialized = serializeDateRange(defaultDateRange);

  const [value, setValue] = useQueryParam(
    key,
    withDefault(StringParam, defaultSerialized),
    { updateType: "replaceIn" },
  );

  return useMetricDateRangeCore({
    value,
    setValue,
    defaultDateRange,
    ...rest,
  });
};
