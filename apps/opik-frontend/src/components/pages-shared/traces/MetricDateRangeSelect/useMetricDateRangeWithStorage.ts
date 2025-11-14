import useLocalStorageState from "use-local-storage-state";
import {
  useMetricDateRangeCore,
  UseMetricDateRangeOptions,
} from "./useMetricDateRangeCore";
import { DEFAULT_DATE_PRESET } from "./constants";

type UseMetricDateRangeWithStorageOptions = UseMetricDateRangeOptions & {
  key: string;
};

export const useMetricDateRangeWithStorage = (
  options: UseMetricDateRangeWithStorageOptions,
) => {
  const { key, ...rest } = options;

  const [value, setValue] = useLocalStorageState<string>(key, {
    defaultValue: DEFAULT_DATE_PRESET,
  });

  return useMetricDateRangeCore({
    value,
    setValue,
    ...rest,
  });
};
