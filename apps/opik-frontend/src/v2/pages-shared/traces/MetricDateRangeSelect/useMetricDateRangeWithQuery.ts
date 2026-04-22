import { useQueryParam, withDefault, StringParam } from "use-query-params";
import {
  useMetricDateRangeCore,
  UseMetricDateRangeOptions,
} from "./useMetricDateRangeCore";
import { DEFAULT_DATE_PRESET, DEFAULT_DATE_URL_KEY } from "./constants";

type UseMetricDateRangeWithQueryOptions = UseMetricDateRangeOptions & {
  key?: string;
};

export const useMetricDateRangeWithQuery = (
  options: UseMetricDateRangeWithQueryOptions = {},
) => {
  const { key = DEFAULT_DATE_URL_KEY, ...rest } = options;

  const [value, setValue] = useQueryParam(
    key,
    withDefault(StringParam, DEFAULT_DATE_PRESET),
    { updateType: "replaceIn" },
  );

  return useMetricDateRangeCore({
    value,
    setValue,
    ...rest,
  });
};
