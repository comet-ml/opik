import { StringParam } from "use-query-params";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";
import {
  useMetricDateRangeCore,
  UseMetricDateRangeOptions,
} from "./useMetricDateRangeCore";
import { DEFAULT_DATE_PRESET, DEFAULT_DATE_URL_KEY } from "./constants";

type UseMetricDateRangeWithQueryAndStorageOptions =
  UseMetricDateRangeOptions & {
    key?: string;
  };

export const useMetricDateRangeWithQueryAndStorage = (
  options: UseMetricDateRangeWithQueryAndStorageOptions = {},
) => {
  const { key = DEFAULT_DATE_URL_KEY, ...rest } = options;

  const [value, setValue] = useQueryParamAndLocalStorageState<
    string | null | undefined
  >({
    localStorageKey: `local-${key}`,
    queryKey: key,
    defaultValue: DEFAULT_DATE_PRESET,
    queryParamConfig: StringParam,
    syncQueryWithLocalStorageOnInit: true,
  });

  return useMetricDateRangeCore({
    value: value ?? DEFAULT_DATE_PRESET,
    setValue,
    ...rest,
  });
};
