import { useCallback, useMemo } from "react";
import useLocalStorageState from "use-local-storage-state";
import {
  useQueryParam,
  QueryParamConfig,
  QueryParamOptions,
} from "use-query-params";
import { Updater } from "@/types/shared";
import isFunction from "lodash/isFunction";

const QUERY_OPTIONS: QueryParamOptions = {
  updateType: "replaceIn",
};

type UseQueryParamAndLocalStorageStateParams<T> = {
  localStorageKey: string;
  queryKey: string;
  defaultValue: T;
  queryParamConfig: QueryParamConfig<T>;
  queryOptions?: QueryParamOptions;
};

const useQueryParamAndLocalStorageState = <T>({
  localStorageKey,
  queryKey,
  defaultValue,
  queryParamConfig,
  queryOptions = QUERY_OPTIONS,
}: UseQueryParamAndLocalStorageStateParams<T>) => {
  const [localStorageValue, setLocalStorageValue] = useLocalStorageState<T>(
    localStorageKey,
    {
      defaultValue,
    },
  );
  const [queryValue, setQueryValue] = useQueryParam(
    queryKey,
    queryParamConfig,
    queryOptions,
  );

  const combinedValue = useMemo(
    () => (queryValue as T) ?? localStorageValue,
    [queryValue, localStorageValue],
  );

  const setValue = useCallback(
    (value: Updater<T>) => {
      const newValue = isFunction(value) ? value(combinedValue) : value;
      setLocalStorageValue(newValue);
      setQueryValue(newValue);
    },
    [setLocalStorageValue, setQueryValue, combinedValue],
  );

  return [combinedValue, setValue] as const;
};

export default useQueryParamAndLocalStorageState;
