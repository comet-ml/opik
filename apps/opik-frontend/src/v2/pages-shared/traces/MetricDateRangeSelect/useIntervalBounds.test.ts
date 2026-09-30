import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { act, renderHook } from "@testing-library/react";
import dayjs from "dayjs";
import utc from "dayjs/plugin/utc";
import { DateRangeValue, PRESET_DATE_RANGES } from "@/shared/DateRangeSelect";
import { useIntervalBounds } from "./useIntervalBounds";

dayjs.extend(utc);

const now = dayjs(PRESET_DATE_RANGES.past24hours.to)
  .startOf("day")
  .add(12, "hours")
  .add(30, "minutes");
const later = now.add(90, "minutes");
const muchLater = now.add(3, "hours");

const renderIntervalBounds = (dateRange: DateRangeValue) =>
  renderHook(
    ({ range }: { range: DateRangeValue }) => useIntervalBounds(range),
    { initialProps: { range: dateRange } },
  );

describe("useIntervalBounds", () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(now.toDate());
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("should keep a preset window frozen between refreshes", () => {
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
    const { result } = renderIntervalBounds({
      from: new Date("2024-01-03"),
      to: new Date("2024-01-10"),
    });
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
});
