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
  /**
   * Holds back the init sync until the caller's `defaultValue` is settled.
   *
   * The sync below writes the current value to the URL once, on the first render where it is
   * eligible — by default that is mount, but `false` here pushes it to the first render that
   * passes. A caller whose `defaultValue` depends on async data (say, a fetched project) would
   * otherwise have the placeholder default pinned into the URL before the real one arrives, and
   * the URL then wins forever. Ignored unless `syncQueryWithLocalStorageOnInit` is set.
   */
  initSyncReady?: boolean;
  syncLocalStorageAcrossTabs?: boolean;
};

const useQueryParamAndLocalStorageState = <T>({
  localStorageKey,
  queryKey,
  defaultValue,
  queryParamConfig,
  queryOptions = QUERY_OPTIONS,
  syncQueryWithLocalStorageOnInit = false,
  initSyncReady = true,
  syncLocalStorageAcrossTabs = true,
}: UseQueryParamAndLocalStorageStateParams<T>) => {
  const [localStorageValue, setLocalStorageValue] = useLocalStorageState<T>(
    localStorageKey,
    {
      defaultValue,
      storageSync: syncLocalStorageAcrossTabs,
    },
  );

  const [queryValue, setQueryValue] = useQueryParam(
    queryKey,
    queryParamConfig,
    queryOptions,
  );

  // sync localStorage → URL once, on the first render where initSyncReady passes (mount unless a
  // caller defers it), and only when the URL has no value
  useEffect(() => {
    if (
      syncQueryWithLocalStorageOnInit &&
      initSyncReady &&
      !isUndefined(localStorageValue) &&
      isUndefined(queryValue)
    ) {
      setQueryValue(localStorageValue);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [syncQueryWithLocalStorageOnInit, initSyncReady]);

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
