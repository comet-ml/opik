import { useCallback, useEffect, useState } from "react";
import { focusManager, QueryKey } from "@tanstack/react-query";
import isEqual from "lodash/isEqual";
import isObject from "lodash/isObject";
import { DateRangeValue } from "@/shared/DateRangeSelect";
import {
  calculateIntervalBounds,
  IntervalBounds,
  isEndDateToday,
  reanchorIntervalBounds,
  serializeDateRange,
} from "./utils";

export const REANCHOR_INTERVAL = 30000;

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

export const useIntervalBounds = (
  dateRange: DateRangeValue,
  isAutoReanchorEnabled = true,
) => {
  const [anchored, setAnchored] = useState(() => anchorToNow(dateRange));

  const isNewSelection =
    anchored.selectionKey !== serializeDateRange(dateRange);
  const current = isNewSelection ? anchorToNow(dateRange) : anchored;
  if (isNewSelection) setAnchored(current);

  const isLive = isEndDateToday(dateRange);

  useEffect(() => {
    if (!isLive || !isAutoReanchorEnabled) return;

    const timer = setInterval(() => {
      if (!focusManager.isFocused()) return;

      setAnchored(anchorToNow(anchored.dateRange));
    }, REANCHOR_INTERVAL);

    return () => clearInterval(timer);
  }, [anchored, isLive, isAutoReanchorEnabled]);

  const reanchorToNow = useCallback(() => {
    const next = reanchorIntervalBounds(current.dateRange, current.bounds);
    if (next === current.bounds) return false;

    setAnchored({ ...current, bounds: next });
    return true;
  }, [current]);

  return {
    ...current.bounds,
    refetchInterval: isLive ? (false as const) : REANCHOR_INTERVAL,
    reanchorToNow,
  };
};

export type IntervalWindow = ReturnType<typeof useIntervalBounds>;

const isOnlyWindowEndChange = (
  previousParams: object,
  params: Record<string, unknown>,
  windowEndField: string,
) =>
  Object.entries(previousParams).every(
    ([field, value]) =>
      field === windowEndField || isEqual(value, params[field]),
  );

export const keepDataWhenOnlyWindowEndChanged =
  <TParams extends Record<string, unknown>>(
    params: TParams,
    windowEndField: keyof TParams & string,
  ) =>
  <TData>(
    previousData: TData | undefined,
    previousQuery: { queryKey: QueryKey } | undefined,
  ) => {
    const previousParams = previousQuery?.queryKey[1];

    return isObject(previousParams) &&
      isOnlyWindowEndChange(previousParams, params, windowEndField)
      ? previousData
      : undefined;
  };

export const useIsOnlyWindowEndBehind = <
  TParams extends Record<string, unknown>,
>(
  params: TParams,
  windowEndField: keyof TParams & string,
  isPlaceholderData: boolean,
) => {
  const [settledParams, setSettledParams] = useState(params);
  if (!isPlaceholderData && !isEqual(settledParams, params)) {
    setSettledParams(params);
  }

  return (
    isPlaceholderData &&
    isOnlyWindowEndChange(settledParams, params, windowEndField)
  );
};
