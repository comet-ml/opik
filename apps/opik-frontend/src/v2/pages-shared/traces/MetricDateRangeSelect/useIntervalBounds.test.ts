import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { focusManager } from "@tanstack/react-query";
import dayjs from "dayjs";
import utc from "dayjs/plugin/utc";
import { DateRangeValue, PRESET_DATE_RANGES } from "@/shared/DateRangeSelect";
import {
  keepDataWhenOnlyWindowEndChanged,
  REANCHOR_INTERVAL,
  useIntervalBounds,
  useIsOnlyWindowEndBehind,
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

const renderIntervalBounds = (dateRange: DateRangeValue) =>
  renderHook(
    ({ range }: { range: DateRangeValue }) => useIntervalBounds(range),
    { initialProps: { range: dateRange } },
  );

const tick = (ms = REANCHOR_INTERVAL) =>
  act(() => {
    vi.advanceTimersByTime(ms);
  });

describe("useIntervalBounds", () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ["Date", "setInterval", "clearInterval"] });
    vi.setSystemTime(now.toDate());
  });

  afterEach(() => {
    focusManager.setFocused(undefined);
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
      const { result } = renderHook(() =>
        useIntervalBounds(PRESET_DATE_RANGES.past7days, false),
      );

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
});

describe("keepDataWhenOnlyWindowEndChanged", () => {
  const previousData = { stats: [] };
  const windowStart = "2024-01-03T00:00:00Z";
  const windowEnd = "2024-01-10T12:00:00Z";
  const movedWindowEnd = "2024-01-10T12:00:30Z";
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
    intervalStart: windowStart,
    intervalEnd: windowEnd,
  };
  const previousQuery = (params: Record<string, unknown>) => ({
    queryKey: ["key", params],
  });

  it("should keep the data when only the window end moved", () => {
    const placeholder = keepDataWhenOnlyWindowEndChanged(
      { ...statsParams, toTime: movedWindowEnd },
      "toTime",
    );

    expect(placeholder(previousData, previousQuery(statsParams))).toBe(
      previousData,
    );
  });

  it.each([
    ["window start", { fromTime: "2024-01-04T00:00:00Z" }],
    ["filters", { filters: [] }],
    ["search", { search: "error" }],
    ["project", { projectId: "project-2" }],
  ])("should drop the data when the %s changed", (_, change) => {
    const placeholder = keepDataWhenOnlyWindowEndChanged(
      { ...statsParams, ...change, toTime: movedWindowEnd },
      "toTime",
    );

    expect(placeholder(previousData, previousQuery(statsParams))).toBe(
      undefined,
    );
  });

  it("should keep the chart data when only its interval end moved", () => {
    const placeholder = keepDataWhenOnlyWindowEndChanged(
      { ...chartParams, intervalEnd: movedWindowEnd },
      "intervalEnd",
    );

    expect(placeholder(previousData, previousQuery(chartParams))).toBe(
      previousData,
    );
  });

  it("should drop the chart data when the metric changed", () => {
    const placeholder = keepDataWhenOnlyWindowEndChanged(
      { ...chartParams, metricName: "THREAD_COST" },
      "intervalEnd",
    );

    expect(placeholder(previousData, previousQuery(chartParams))).toBe(
      undefined,
    );
  });

  it("should compare only the fields the previous query was keyed on", () => {
    const placeholder = keepDataWhenOnlyWindowEndChanged(
      { ...statsParams, type: "spans", toTime: movedWindowEnd },
      "toTime",
    );

    expect(placeholder(previousData, previousQuery(statsParams))).toBe(
      previousData,
    );
  });

  it("should drop the data when there is no previous query", () => {
    const placeholder = keepDataWhenOnlyWindowEndChanged(statsParams, "toTime");

    expect(placeholder(undefined, undefined)).toBe(undefined);
  });
});

describe("useIsOnlyWindowEndBehind", () => {
  type ListParams = { page: number; toTime: string };
  type Props = { params: ListParams; isPlaceholderData: boolean };

  const renderIsOnlyWindowEndBehind = (initialProps: Props) =>
    renderHook(
      ({ params, isPlaceholderData }: Props) =>
        useIsOnlyWindowEndBehind(params, "toTime", isPlaceholderData),
      { initialProps },
    );

  it("should be false while the rows on screen are the requested ones", () => {
    const { result } = renderIsOnlyWindowEndBehind({
      params: { page: 1, toTime: "t0" },
      isPlaceholderData: false,
    });

    expect(result.current).toBe(false);
  });

  it("should be true when the rows on screen differ only by an older window end", () => {
    const { result, rerender } = renderIsOnlyWindowEndBehind({
      params: { page: 1, toTime: "t0" },
      isPlaceholderData: false,
    });

    rerender({ params: { page: 1, toTime: "t1" }, isPlaceholderData: true });

    expect(result.current).toBe(true);
  });

  it("should be false when the user changed something else", () => {
    const { result, rerender } = renderIsOnlyWindowEndBehind({
      params: { page: 1, toTime: "t0" },
      isPlaceholderData: false,
    });

    rerender({ params: { page: 2, toTime: "t0" }, isPlaceholderData: true });

    expect(result.current).toBe(false);
  });

  it("should compare against the rows that settled last", () => {
    const { result, rerender } = renderIsOnlyWindowEndBehind({
      params: { page: 1, toTime: "t0" },
      isPlaceholderData: false,
    });
    rerender({ params: { page: 2, toTime: "t0" }, isPlaceholderData: true });
    rerender({ params: { page: 2, toTime: "t0" }, isPlaceholderData: false });

    rerender({ params: { page: 2, toTime: "t1" }, isPlaceholderData: true });

    expect(result.current).toBe(true);
  });
});
