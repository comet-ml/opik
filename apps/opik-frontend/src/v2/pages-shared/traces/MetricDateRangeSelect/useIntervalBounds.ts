import { useCallback, useEffect, useState } from "react";
import {
  focusManager,
  onlineManager,
  QueryClient,
  QueryKey,
  useQueryClient,
} from "@tanstack/react-query";
import isEqual from "lodash/isEqual";
import isObject from "lodash/isObject";
import { DateRangeValue } from "@/shared/DateRangeSelect";
import {
  calculateIntervalBounds,
  IntervalBounds,
  isLiveDateRange,
  reanchorIntervalBounds,
  serializeDateRange,
} from "./utils";

export const REANCHOR_INTERVAL = 30000;
const LIVE_WINDOW_GC_TIME = 2 * 60 * 1000;

type AnchoredBounds = {
  selectionKey: string;
  dateRange: DateRangeValue;
  bounds: IntervalBounds;
};

const anchorToNow = (dateRange: DateRangeValue): AnchoredBounds => ({
  selectionKey: serializeDateRange(dateRange),
  dateRange,
  bounds: calculateIntervalBounds(dateRange),
});

const isFetchingWindow = (
  queryClient: QueryClient,
  { intervalEnd }: IntervalBounds,
) =>
  queryClient.isFetching({
    predicate: ({ queryKey }) =>
      isObject(queryKey[1]) && Object.values(queryKey[1]).includes(intervalEnd),
  }) > 0;

export const useIntervalBounds = (
  dateRange: DateRangeValue,
  isAutoReanchorEnabled = true,
) => {
  const queryClient = useQueryClient();
  const [anchored, setAnchored] = useState(() => anchorToNow(dateRange));

  const isNewSelection =
    anchored.selectionKey !== serializeDateRange(dateRange);
  const current = isNewSelection ? anchorToNow(dateRange) : anchored;
  if (isNewSelection) setAnchored(current);

  const isLive = isLiveDateRange(dateRange);

  useEffect(() => {
    if (!isLive || !isAutoReanchorEnabled) return;

    const reanchorWhenIdle = () => {
      if (
        !focusManager.isFocused() ||
        !onlineManager.isOnline() ||
        isFetchingWindow(queryClient, anchored.bounds)
      ) {
        return;
      }
      if (!isLiveDateRange(anchored.dateRange)) {
        // Same bounds, new object: the render re-reads the liveness, clears this
        // timer and switches the queries to polling the window as it stood before
        // local midnight, instead of re-anchoring a range that is no longer "today".
        setAnchored({ ...anchored });
        return;
      }

      setAnchored(anchorToNow(anchored.dateRange));
    };
    const reanchorOnReturn = (isBack: boolean) => {
      if (isBack) reanchorWhenIdle();
    };

    const timer = setInterval(reanchorWhenIdle, REANCHOR_INTERVAL);
    const unsubscribeFocus = focusManager.subscribe(reanchorOnReturn);
    const unsubscribeOnline = onlineManager.subscribe(reanchorOnReturn);

    return () => {
      clearInterval(timer);
      unsubscribeFocus();
      unsubscribeOnline();
    };
  }, [anchored, isLive, isAutoReanchorEnabled, queryClient]);

  const reanchorToNow = useCallback(() => {
    if (!isLiveDateRange(current.dateRange)) return false;

    const next = reanchorIntervalBounds(current.dateRange, current.bounds);
    if (next === current.bounds) return false;

    setAnchored({ ...current, bounds: next });
    return true;
  }, [current]);

  return {
    ...current.bounds,
    selectionKey: current.selectionKey,
    refetchInterval: isLive ? (false as const) : REANCHOR_INTERVAL,
    reanchorToNow,
  };
};

export type IntervalWindow = ReturnType<typeof useIntervalBounds>;

export const windowQueryOptions = (refetchInterval: number | false) =>
  refetchInterval === false
    ? {
        refetchInterval,
        refetchOnWindowFocus: false,
        refetchOnReconnect: false,
        gcTime: LIVE_WINDOW_GC_TIME,
      }
    : { refetchInterval };

type WindowFields<TParams> = readonly [
  start: keyof TParams & string,
  end: keyof TParams & string,
];

const isOnlyWindowChange = (
  previousParams: object,
  params: Record<string, unknown>,
  windowFields: readonly string[],
) =>
  Object.entries(previousParams).every(
    ([field, value]) =>
      windowFields.includes(field) || isEqual(value, params[field]),
  );

export const keepDataWhenOnlyWindowChanged =
  <TParams extends Record<string, unknown>>(
    params: TParams,
    windowFields: WindowFields<TParams>,
  ) =>
  <TData>(
    previousData: TData | undefined,
    previousQuery: { queryKey: QueryKey } | undefined,
  ) => {
    const previousParams = previousQuery?.queryKey[1];

    return isObject(previousParams) &&
      isOnlyWindowChange(previousParams, params, windowFields)
      ? previousData
      : undefined;
  };

export const keepDataWhileWindowMoves = <
  TParams extends Record<string, unknown>,
>(
  refetchInterval: number | false,
  params: TParams,
  windowFields: WindowFields<TParams>,
) =>
  refetchInterval === false
    ? keepDataWhenOnlyWindowChanged(params, windowFields)
    : undefined;

export const useIsOnlyWindowBehind = <TParams extends Record<string, unknown>>(
  params: TParams,
  windowFields: WindowFields<TParams>,
  isPlaceholderData: boolean,
) => {
  const [settledParams, setSettledParams] = useState(params);
  if (!isPlaceholderData && !isEqual(settledParams, params)) {
    setSettledParams(params);
  }

  return (
    isPlaceholderData && isOnlyWindowChange(settledParams, params, windowFields)
  );
};
