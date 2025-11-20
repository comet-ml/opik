import { StringParam } from "use-query-params";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";
import {
  useMetricDateRangeCore,
  UseMetricDateRangeOptions,
} from "./useMetricDateRangeCore";
import { DEFAULT_DATE_PRESET } from "./constants";

type UseMetricDateRangeWithQueryAndStorageOptions =
  UseMetricDateRangeOptions & {
    queryKey: string;
    localStorageKey: string;
  };

export const useMetricDateRangeWithQueryAndStorage = (
  options: UseMetricDateRangeWithQueryAndStorageOptions,
) => {
  const { queryKey, localStorageKey, ...rest } = options;

  const [value, setValue] = useQueryParamAndLocalStorageState<
    string | null | undefined
  >({
    localStorageKey,
    queryKey,
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
