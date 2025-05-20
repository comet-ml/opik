import { useCallback, useEffect, useMemo } from "react";
import useLocalStorageState from "use-local-storage-state";
import {
  useQueryParam,
  QueryParamConfig,
  QueryParamOptions,
} from "use-query-params";
import isFunction from "lodash/isFunction";
import isUndefined from "lodash/isUndefined";

import { Updater } from "@/types/shared";

const QUERY_OPTIONS: QueryParamOptions = {
  updateType: "replaceIn",
};

type UseQueryParamAndLocalStorageStateParams<T> = {
  localStorageKey: string;
  queryKey: string;
  defaultValue: T;
  queryParamConfig: QueryParamConfig<T>;
  queryOptions?: QueryParamOptions;
  syncQueryWithLocalStorageOnInit?: boolean;
};

const useQueryParamAndLocalStorageState = <T>({
  localStorageKey,
  queryKey,
  defaultValue,
  queryParamConfig,
  queryOptions = QUERY_OPTIONS,
  syncQueryWithLocalStorageOnInit = false,
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

  // update query param when local storage is initialized on hook mount
  useEffect(() => {
    if (syncQueryWithLocalStorageOnInit && !isUndefined(localStorageValue)) {
      setQueryValue(localStorageValue);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [syncQueryWithLocalStorageOnInit, localStorageValue]);

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
