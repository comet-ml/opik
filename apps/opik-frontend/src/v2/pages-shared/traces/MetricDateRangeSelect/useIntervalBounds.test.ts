import React from "react";
import {
  describe,
  expect,
  it,
  vi,
  beforeEach,
  afterEach,
  onTestFinished,
} from "vitest";
import { act, renderHook } from "@testing-library/react";
import {
  focusManager,
  onlineManager,
  QueryClient,
  QueryClientProvider,
  useQuery,
} from "@tanstack/react-query";
import dayjs from "dayjs";
import utc from "dayjs/plugin/utc";
import { DateRangeValue, PRESET_DATE_RANGES } from "@/shared/DateRangeSelect";
import {
  keepDataWhenOnlyWindowChanged,
  REANCHOR_INTERVAL,
  useIntervalBounds,
  useIsOnlyWindowBehind,
  windowQueryOptions,
  keepDataWhileWindowMoves,
} from "./useIntervalBounds";

dayjs.extend(utc);

const now = dayjs(PRESET_DATE_RANGES.past24hours.to)
  .startOf("day")
  .add(12, "hours")
  .add(30, "minutes");
const later = now.add(90, "minutes");
const muchLater = now.add(3, "hours");

const pastCustomRange: DateRangeValue = {
  from: new Date("2024-01-03"),
  to: new Date("2024-01-10"),
};

const createWrapper = () => {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const Wrapper = ({ children }: { children: React.ReactNode }) =>
    React.createElement(QueryClientProvider, { client: queryClient }, children);
  return Wrapper;
};

const renderIntervalBounds = (dateRange: DateRangeValue) =>
  renderHook(
    ({ range }: { range: DateRangeValue }) => useIntervalBounds(range),
    { initialProps: { range: dateRange }, wrapper: createWrapper() },
  );

type FetchWindow = (toTime: string, signal: AbortSignal) => Promise<object>;

const renderWindowQuery = (fetchWindow: FetchWindow) =>
  renderHook(
    () => {
      const intervalWindow = useIntervalBounds(PRESET_DATE_RANGES.past7days);
      useQuery({
        queryKey: [
          "window-query",
          { toTime: intervalWindow.intervalEnd },
        ] as const,
        queryFn: ({ queryKey: [, { toTime }], signal }) =>
          fetchWindow(toTime, signal),
        ...windowQueryOptions(intervalWindow.refetchInterval),
      });
      return intervalWindow;
    },
    { wrapper: createWrapper() },
  );

const tick = (ms = REANCHOR_INTERVAL) =>
  act(() => {
    vi.advanceTimersByTime(ms);
  });

const settle = () =>
  act(() => new Promise((resolve) => setTimeout(resolve, 0)));

const mockPageVisibility = () => {
  let visibilityState: DocumentVisibilityState = "visible";
  Object.defineProperty(document, "visibilityState", {
    configurable: true,
    get: () => visibilityState,
  });
  onTestFinished(() => {
    Reflect.deleteProperty(document, "visibilityState");
  });

  return (visibility: DocumentVisibilityState) =>
    act(async () => {
      visibilityState = visibility;
      window.dispatchEvent(new Event("visibilitychange"));
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
};

const fetchedWindowEnds = (fetchWindow: ReturnType<typeof vi.fn>) =>
  fetchWindow.mock.calls.map(([toTime]) => toTime);

describe("useIntervalBounds", () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ["Date", "setInterval", "clearInterval"] });
    vi.setSystemTime(now.toDate());
  });

  afterEach(() => {
    focusManager.setFocused(undefined);
    onlineManager.setOnline(true);
    vi.useRealTimers();
  });

  it("should keep a preset window across re-renders", () => {
    const { result, rerender } = renderIntervalBounds(
      PRESET_DATE_RANGES.past7days,
    );
    vi.setSystemTime(later.toDate());

    rerender({ range: PRESET_DATE_RANGES.past7days });

    expect(result.current.intervalEnd).toBe(now.utc().format());
  });

  it("should move a preset window to the current time on reanchor", () => {
    const { result } = renderIntervalBounds(PRESET_DATE_RANGES.past7days);
    vi.setSystemTime(later.toDate());

    let moved = false;
    act(() => {
      moved = result.current.reanchorToNow();
    });

    expect(moved).toBe(true);
    expect(result.current.intervalEnd).toBe(later.utc().format());
  });

  it("should report no move and keep a past custom window", () => {
    const { result } = renderIntervalBounds(pastCustomRange);
    const { intervalStart, intervalEnd } = result.current;
    vi.setSystemTime(later.toDate());

    let moved = true;
    act(() => {
      moved = result.current.reanchorToNow();
    });

    expect(moved).toBe(false);
    expect(result.current.intervalStart).toBe(intervalStart);
    expect(result.current.intervalEnd).toBe(intervalEnd);
  });

  it("should start a fresh window when the range is picked again after a reanchor", () => {
    const { result, rerender } = renderIntervalBounds(
      PRESET_DATE_RANGES.past7days,
    );
    vi.setSystemTime(later.toDate());
    act(() => {
      result.current.reanchorToNow();
    });

    rerender({ range: PRESET_DATE_RANGES.past30days });
    vi.setSystemTime(muchLater.toDate());
    rerender({ range: PRESET_DATE_RANGES.past7days });

    expect(result.current.intervalEnd).toBe(muchLater.utc().format());
  });

  it("should keep the window when the same range arrives as a new object", () => {
    const { result, rerender } = renderIntervalBounds(
      PRESET_DATE_RANGES.past7days,
    );
    vi.setSystemTime(later.toDate());

    rerender({ range: { ...PRESET_DATE_RANGES.past7days } });

    expect(result.current.intervalEnd).toBe(now.utc().format());
  });

  it("should keep a reanchored window when the same range arrives as a new object", () => {
    const { result, rerender } = renderIntervalBounds(
      PRESET_DATE_RANGES.past7days,
    );
    vi.setSystemTime(later.toDate());
    act(() => {
      result.current.reanchorToNow();
    });

    vi.setSystemTime(muchLater.toDate());
    rerender({ range: { ...PRESET_DATE_RANGES.past7days } });

    expect(result.current.intervalEnd).toBe(later.utc().format());
  });

  describe("periodic reanchor", () => {
    it("should move a preset window's end forward on every tick", () => {
      const { result } = renderIntervalBounds(PRESET_DATE_RANGES.past7days);
      const { intervalStart } = result.current;

      tick();
      expect(result.current.intervalEnd).toBe(
        now.add(REANCHOR_INTERVAL, "ms").utc().format(),
      );

      tick();
      expect(result.current.intervalEnd).toBe(
        now
          .add(2 * REANCHOR_INTERVAL, "ms")
          .utc()
          .format(),
      );
      expect(result.current.intervalStart).toBe(intervalStart);
    });

    it("should not move the window on the tick when the automatic reanchor is off", () => {
      const { result } = renderHook(
        () => useIntervalBounds(PRESET_DATE_RANGES.past7days, false),
        { wrapper: createWrapper() },
      );

      tick();

      expect(result.current.intervalEnd).toBe(now.utc().format());
    });

    it("should not move the window while the connection is down", () => {
      const { result } = renderIntervalBounds(PRESET_DATE_RANGES.past7days);
      onlineManager.setOnline(false);

      tick();

      expect(result.current.intervalEnd).toBe(now.utc().format());
    });

    it("should keep a past custom window on the tick", () => {
      const { result } = renderIntervalBounds(pastCustomRange);
      const { intervalStart, intervalEnd } = result.current;

      tick();

      expect(result.current.intervalStart).toBe(intervalStart);
      expect(result.current.intervalEnd).toBe(intervalEnd);
    });

    it("should leave the refetch to the tick for a live window and to the query for a past one", () => {
      const { result: live } = renderIntervalBounds(
        PRESET_DATE_RANGES.past7days,
      );
      const { result: past } = renderIntervalBounds(pastCustomRange);

      expect(live.current.refetchInterval).toBe(false);
      expect(past.current.refetchInterval).toBe(REANCHOR_INTERVAL);
    });

    it("should not move the window while the page is in the background", () => {
      const { result } = renderIntervalBounds(PRESET_DATE_RANGES.past7days);
      focusManager.setFocused(false);

      tick();

      expect(result.current.intervalEnd).toBe(now.utc().format());
    });

    it("should restart the cadence after a manual reanchor", () => {
      const { result } = renderIntervalBounds(PRESET_DATE_RANGES.past7days);
      const halfTick = REANCHOR_INTERVAL / 2;
      tick(halfTick);
      act(() => {
        result.current.reanchorToNow();
      });

      tick(halfTick);
      expect(result.current.intervalEnd).toBe(
        now.add(halfTick, "ms").utc().format(),
      );

      tick(halfTick);
      expect(result.current.intervalEnd).toBe(
        now
          .add(REANCHOR_INTERVAL + halfTick, "ms")
          .utc()
          .format(),
      );
    });
  });

  describe("across local midnight", () => {
    const midnight = dayjs(PRESET_DATE_RANGES.past24hours.to)
      .startOf("day")
      .add(1, "day");
    const beforeMidnight = midnight.subtract(20, "seconds");
    const ticksUntil = (time: dayjs.Dayjs) =>
      Math.max(0, Math.ceil(time.diff(dayjs()) / REANCHOR_INTERVAL)) *
      REANCHOR_INTERVAL;

    beforeEach(() => {
      vi.setSystemTime(beforeMidnight.toDate());
    });

    it("should keep a preset live and roll its start forward like a fresh load", () => {
      const { result } = renderIntervalBounds(PRESET_DATE_RANGES.past7days);
      const startAtLoad = result.current.intervalStart;

      tick();
      expect(dayjs().isAfter(midnight)).toBe(true);
      expect(result.current.intervalEnd).toBe(dayjs().utc().format());
      expect(result.current.refetchInterval).toBe(false);

      tick(ticksUntil(beforeMidnight.utc().endOf("day")));
      expect(result.current.intervalEnd).toBe(dayjs().utc().format());
      expect(result.current.intervalStart).toBe(
        dayjs().utc().subtract(6, "days").startOf("day").format(),
      );
      expect(result.current.intervalStart).not.toBe(startAtLoad);

      vi.setSystemTime(dayjs().add(10, "seconds").toDate());
      let moved = false;
      act(() => {
        moved = result.current.reanchorToNow();
      });
      expect(moved).toBe(true);
      expect(result.current.intervalEnd).toBe(dayjs().utc().format());
    });

    it("should keep the last live window of a custom range ending today after midnight and poll it", () => {
      const endingToday: DateRangeValue = {
        from: midnight.subtract(4, "days").toDate(),
        to: midnight.subtract(1, "ms").toDate(),
      };
      const { result } = renderIntervalBounds(endingToday);
      const { intervalStart, intervalEnd } = result.current;
      expect(intervalEnd).toBe(dayjs().utc().format());
      expect(result.current.refetchInterval).toBe(false);

      tick();
      expect(dayjs().isAfter(midnight)).toBe(true);
      tick(5 * REANCHOR_INTERVAL);

      expect(result.current.intervalStart).toBe(intervalStart);
      expect(result.current.intervalEnd).toBe(intervalEnd);
      expect(result.current.refetchInterval).toBe(REANCHOR_INTERVAL);

      let moved = true;
      act(() => {
        moved = result.current.reanchorToNow();
      });
      expect(moved).toBe(false);
      expect(result.current.intervalEnd).toBe(intervalEnd);
    });
  });

  describe("with the window's queries", () => {
    it("should not move the window while one of its requests is in flight, then move on the next tick", async () => {
      let resolveRequest: (data: object) => void = () => {};
      const fetchWindow = vi.fn<FetchWindow>(
        () => new Promise((resolve) => (resolveRequest = resolve)),
      );
      const { result } = renderWindowQuery(fetchWindow);
      const [, signal] = fetchWindow.mock.calls[0];

      tick();
      tick();
      expect(result.current.intervalEnd).toBe(now.utc().format());
      expect(signal.aborted).toBe(false);

      await act(async () => resolveRequest({}));
      tick();

      expect(result.current.intervalEnd).toBe(
        now
          .add(3 * REANCHOR_INTERVAL, "ms")
          .utc()
          .format(),
      );
      expect(fetchedWindowEnds(fetchWindow)).toEqual([
        now.utc().format(),
        result.current.intervalEnd,
      ]);
    });

    it("should move the window as soon as the page is visible again, with one request for the new window", async () => {
      const setPageVisibility = mockPageVisibility();
      const fetchWindow = vi.fn<FetchWindow>().mockResolvedValue({});
      const { result } = renderWindowQuery(fetchWindow);
      await settle();
      await setPageVisibility("hidden");
      tick(2 * REANCHOR_INTERVAL);
      expect(result.current.intervalEnd).toBe(now.utc().format());

      await setPageVisibility("visible");

      const returnedAt = now
        .add(2 * REANCHOR_INTERVAL, "ms")
        .utc()
        .format();
      expect(result.current.intervalEnd).toBe(returnedAt);
      expect(fetchedWindowEnds(fetchWindow)).toEqual([
        now.utc().format(),
        returnedAt,
      ]);
    });

    it("should move the window as soon as the connection is back, with one request for the new window", async () => {
      const fetchWindow = vi.fn<FetchWindow>().mockResolvedValue({});
      const { result } = renderWindowQuery(fetchWindow);
      await settle();
      onlineManager.setOnline(false);
      tick(2 * REANCHOR_INTERVAL);
      expect(result.current.intervalEnd).toBe(now.utc().format());

      await act(async () => {
        onlineManager.setOnline(true);
        await new Promise((resolve) => setTimeout(resolve, 0));
      });

      const reconnectedAt = now
        .add(2 * REANCHOR_INTERVAL, "ms")
        .utc()
        .format();
      expect(result.current.intervalEnd).toBe(reconnectedAt);
      expect(fetchedWindowEnds(fetchWindow)).toEqual([
        now.utc().format(),
        reconnectedAt,
      ]);
    });
  });
});

describe("windowQueryOptions", () => {
  it("should leave focus, reconnect and a short cache life to a moving window", () => {
    expect(windowQueryOptions(false)).toEqual({
      refetchInterval: false,
      refetchOnWindowFocus: false,
      refetchOnReconnect: false,
      gcTime: 2 * 60 * 1000,
    });
  });

  it("should keep the query defaults for a fixed window", () => {
    expect(windowQueryOptions(REANCHOR_INTERVAL)).toEqual({
      refetchInterval: REANCHOR_INTERVAL,
    });
  });
});

describe("keepDataWhileWindowMoves", () => {
  const params = { projectId: "project-1", fromTime: "a", toTime: "b" };

  it("should keep data across a slide only while the window moves by itself", () => {
    const keep = keepDataWhileWindowMoves(false, params, [
      "fromTime",
      "toTime",
    ]);
    const previousQuery = { queryKey: ["stats", params] };

    expect(keep).toBeTypeOf("function");
    expect(keep?.({ stats: [] }, previousQuery)).toEqual({ stats: [] });
  });

  it("should leave a fixed window with the query default of no placeholder", () => {
    expect(
      keepDataWhileWindowMoves(REANCHOR_INTERVAL, params, [
        "fromTime",
        "toTime",
      ]),
    ).toBeUndefined();
  });
});

describe("keepDataWhenOnlyWindowChanged", () => {
  const previousData = { stats: [] };
  const windowStart = "2024-01-10T11:00:00Z";
  const windowEnd = "2024-01-10T11:59:50Z";
  const movedWindowEnd = "2024-01-10T12:00:20Z";
  const rolledWindowStart = "2024-01-10T12:00:00Z";
  const statsParams = {
    projectId: "project-1",
    filters: [{ field: "tags", operator: "contains", value: "a" }],
    search: "",
    fromTime: windowStart,
    toTime: windowEnd,
  };
  const chartParams = {
    projectId: "project-1",
    metricName: "THREAD_COUNT",
    interval: "HOURLY",
    intervalStart: windowStart,
    intervalEnd: windowEnd,
  };
  const previousQuery = (params: Record<string, unknown>) => ({
    queryKey: ["key", params],
  });

  it.each([
    ["only the window end moved", { toTime: movedWindowEnd }],
    [
      "the window start rolled forward with its end",
      { fromTime: rolledWindowStart, toTime: movedWindowEnd },
    ],
  ])("should keep the data when %s", (_, change) => {
    const placeholder = keepDataWhenOnlyWindowChanged(
      { ...statsParams, ...change },
      ["fromTime", "toTime"],
    );

    expect(placeholder(previousData, previousQuery(statsParams))).toBe(
      previousData,
    );
  });

  it.each([
    ["filters", { filters: [] }],
    ["search", { search: "error" }],
    ["project", { projectId: "project-2" }],
  ])("should drop the data when the %s changed", (_, change) => {
    const placeholder = keepDataWhenOnlyWindowChanged(
      {
        ...statsParams,
        ...change,
        fromTime: rolledWindowStart,
        toTime: movedWindowEnd,
      },
      ["fromTime", "toTime"],
    );

    expect(placeholder(previousData, previousQuery(statsParams))).toBe(
      undefined,
    );
  });

  it("should keep the chart data when its interval rolled forward", () => {
    const placeholder = keepDataWhenOnlyWindowChanged(
      {
        ...chartParams,
        intervalStart: rolledWindowStart,
        intervalEnd: movedWindowEnd,
      },
      ["intervalStart", "intervalEnd"],
    );

    expect(placeholder(previousData, previousQuery(chartParams))).toBe(
      previousData,
    );
  });

  it.each([
    ["metric", { metricName: "THREAD_COST" }],
    ["interval", { interval: "DAILY" }],
  ])("should drop the chart data when the %s changed", (_, change) => {
    const placeholder = keepDataWhenOnlyWindowChanged(
      { ...chartParams, ...change, intervalEnd: movedWindowEnd },
      ["intervalStart", "intervalEnd"],
    );

    expect(placeholder(previousData, previousQuery(chartParams))).toBe(
      undefined,
    );
  });

  it("should compare only the fields the previous query was keyed on", () => {
    const placeholder = keepDataWhenOnlyWindowChanged(
      { ...statsParams, type: "spans", toTime: movedWindowEnd },
      ["fromTime", "toTime"],
    );

    expect(placeholder(previousData, previousQuery(statsParams))).toBe(
      previousData,
    );
  });

  it("should drop the data when there is no previous query", () => {
    const placeholder = keepDataWhenOnlyWindowChanged(statsParams, [
      "fromTime",
      "toTime",
    ]);

    expect(placeholder(undefined, undefined)).toBe(undefined);
  });
});

describe("useIsOnlyWindowBehind", () => {
  type ListParams = {
    page: number;
    fromTime: string;
    toTime: string;
    selectionKey: string;
  };
  type Props = { params: ListParams; isPlaceholderData: boolean };

  const settledParams: ListParams = {
    page: 1,
    fromTime: "s0",
    toTime: "t0",
    selectionKey: "past24hours",
  };

  const renderIsOnlyWindowBehind = (initialProps: Props) =>
    renderHook(
      ({ params, isPlaceholderData }: Props) =>
        useIsOnlyWindowBehind(
          params,
          ["fromTime", "toTime"],
          isPlaceholderData,
        ),
      { initialProps },
    );

  it("should be false while the rows on screen are the requested ones", () => {
    const { result } = renderIsOnlyWindowBehind({
      params: settledParams,
      isPlaceholderData: false,
    });

    expect(result.current).toBe(false);
  });

  it.each([
    ["an older window end", { toTime: "t1" }],
    ["an older window start and end", { fromTime: "s1", toTime: "t1" }],
  ])(
    "should be true when the rows on screen differ only by %s",
    (_, change) => {
      const { result, rerender } = renderIsOnlyWindowBehind({
        params: settledParams,
        isPlaceholderData: false,
      });

      rerender({
        params: { ...settledParams, ...change },
        isPlaceholderData: true,
      });

      expect(result.current).toBe(true);
    },
  );

  it.each([
    ["the page", { page: 2 }],
    ["the range selection", { selectionKey: "past7days", fromTime: "s1" }],
  ])("should be false when the user changed %s", (_, change) => {
    const { result, rerender } = renderIsOnlyWindowBehind({
      params: settledParams,
      isPlaceholderData: false,
    });

    rerender({
      params: { ...settledParams, ...change },
      isPlaceholderData: true,
    });

    expect(result.current).toBe(false);
  });

  it("should compare against the rows that settled last", () => {
    const { result, rerender } = renderIsOnlyWindowBehind({
      params: settledParams,
      isPlaceholderData: false,
    });
    const secondPage = { ...settledParams, page: 2 };
    rerender({ params: secondPage, isPlaceholderData: true });
    rerender({ params: secondPage, isPlaceholderData: false });

    rerender({
      params: { ...secondPage, toTime: "t1" },
      isPlaceholderData: true,
    });

    expect(result.current).toBe(true);
  });
});
