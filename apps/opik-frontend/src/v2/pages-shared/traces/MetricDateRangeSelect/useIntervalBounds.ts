import { useCallback, useEffect, useState } from "react";
import dayjs from "dayjs";
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
  isOpenEndedDateRange,
  reanchorIntervalBounds,
  serializeDateRange,
} from "./utils";

export const REANCHOR_INTERVAL = 30000;
const LIVE_WINDOW_GC_TIME = 2 * 60 * 1000;

type AnchoredBounds = {
  selectionKey: string;
  dateRange: DateRangeValue;
  bounds: IntervalBounds;
  live: boolean;
};

const anchorToNow = (dateRange: DateRangeValue): AnchoredBounds => ({
  selectionKey: serializeDateRange(dateRange),
  dateRange,
  bounds: calculateIntervalBounds(dateRange),
  live: isLiveDateRange(dateRange),
});

// The range ended at local midnight: close it at the end of its last local day rather than at the last tick, which a
// hidden tab or a sleeping laptop leaves hours early. Not recomputed from the range, whose past-window form lands a
// UTC day early for users east of UTC.
const closeAtEndOfDay = (anchored: AnchoredBounds): AnchoredBounds => ({
  ...anchored,
  live: false,
  bounds: {
    ...anchored.bounds,
    intervalEnd: dayjs(anchored.dateRange.to).endOf("day").utc().format(),
  },
});

const hasEnded = (anchored: AnchoredBounds) =>
  anchored.live && !isLiveDateRange(anchored.dateRange);

const isFetchingWindow = (
  queryClient: QueryClient,
  { intervalStart }: IntervalBounds,
) =>
  queryClient.isFetching({
    predicate: ({ queryKey }) =>
      isObject(queryKey[1]) &&
      Object.values(queryKey[1]).includes(intervalStart),
  }) > 0;

// Open-ended requests only see the start, so the window moves for them only when the start does.
const hasMoved = (
  dateRange: DateRangeValue,
  from: IntervalBounds,
  to: IntervalBounds,
) =>
  isOpenEndedDateRange(dateRange)
    ? from.intervalStart !== to.intervalStart
    : from !== to;

export const useIntervalBounds = (
  dateRange: DateRangeValue,
  isAutoReanchorEnabled = true,
) => {
  const queryClient = useQueryClient();
  const [anchored, setAnchored] = useState(() => anchorToNow(dateRange));

  const isNewSelection =
    anchored.selectionKey !== serializeDateRange(dateRange);
  const selected = isNewSelection ? anchorToNow(dateRange) : anchored;
  // Liveness is the stored window's, not the clock's: a render after midnight closes the window here, where reading
  // the clock alone would stop the timer before it could close it.
  const current = hasEnded(selected) ? closeAtEndOfDay(selected) : selected;
  if (current !== anchored) setAnchored(current);

  const isLive = current.live;
  const isOpenEnded = isOpenEndedDateRange(dateRange);

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
      if (hasEnded(anchored)) {
        setAnchored(closeAtEndOfDay(anchored));
        return;
      }

      const next = anchorToNow(anchored.dateRange);
      if (hasMoved(anchored.dateRange, anchored.bounds, next.bounds)) {
        setAnchored(next);
      }
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
    if (!current.live) return false;
    if (hasEnded(current)) {
      setAnchored(closeAtEndOfDay(current));
      return true;
    }

    const next = reanchorIntervalBounds(current.dateRange, current.bounds);
    if (!hasMoved(current.dateRange, current.bounds, next)) return false;

    setAnchored({ ...current, bounds: next });
    return true;
  }, [current]);

  return {
    intervalStart: current.bounds.intervalStart,
    // A preset has no end, so ids minted ahead of the server clock count, as they do in the traces list (OPIK-8206).
    intervalEnd: isOpenEnded ? undefined : current.bounds.intervalEnd,
    selectionKey: current.selectionKey,
    // An open-ended request does not change as time passes, so a preset is polled; a closed live window moves instead.
    refetchInterval:
      isLive && !isOpenEnded ? (false as const) : REANCHOR_INTERVAL,
    movesByItself: isLive,
    reanchorToNow,
  };
};

export type IntervalWindow = ReturnType<typeof useIntervalBounds>;

export const windowQueryOptions = (
  refetchInterval: number | false,
  selectionKey?: string,
) => ({
  ...(refetchInterval === false
    ? {
        refetchInterval,
        refetchOnWindowFocus: false,
        refetchOnReconnect: false,
        gcTime: LIVE_WINDOW_GC_TIME,
      }
    : { refetchInterval }),
  meta: { windowSelection: selectionKey },
});

type WindowMotion = {
  movesByItself?: boolean;
  selectionKey?: string;
};

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
    previousQuery:
      | { queryKey: QueryKey; meta?: Record<string, unknown> }
      | undefined,
    selectionKey?: string,
  ) => {
    const previousParams = previousQuery?.queryKey[1];

    // A new range selection also changes only the window fields; only a window that moved by itself keeps its data.
    return isObject(previousParams) &&
      previousQuery?.meta?.windowSelection === selectionKey &&
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
  {
    movesByItself = refetchInterval === false,
    selectionKey,
  }: WindowMotion = {},
) =>
  movesByItself
    ? <TData>(
        previousData: TData | undefined,
        previousQuery:
          | { queryKey: QueryKey; meta?: Record<string, unknown> }
          | undefined,
      ) =>
        keepDataWhenOnlyWindowChanged(params, windowFields)(
          previousData,
          previousQuery,
          selectionKey,
        )
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
