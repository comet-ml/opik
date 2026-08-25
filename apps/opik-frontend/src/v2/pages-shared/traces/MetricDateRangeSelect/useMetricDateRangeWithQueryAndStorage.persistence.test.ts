/**
 * Storage-boundary tests for the demo project's date range.
 *
 * The sibling file mocks `useQueryParamAndLocalStorageState`, which is right for testing this
 * wrapper's own resolution logic but means it cannot prove anything about persistence: a broken
 * storage key or a dropped write would still pass there. These tests use the **real**
 * use-local-storage-state and the real `localStorage` (happy-dom), mocking only `use-query-params`,
 * which needs a router provider.
 *
 * What they pin is the claim this behaviour rests on — the 24h value is only an initial default, and
 * a range the user picks is written to the demo project's own slot and read back from it rather than
 * being reverted.
 */
import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { DEMO_PROJECT_NAME } from "@/constants/shared";
import { PRESET_DATE_RANGES } from "@/shared/DateRangeSelect";
import {
  DATE_RANGE_PRESET_PAST_7_DAYS,
  DATE_RANGE_PRESET_PAST_24_HOURS,
  DEFAULT_DATE_PRESET,
} from "./constants";

const mockSetQueryValue = vi.fn();
let queryValue: string | null | undefined;

// Only the router-coupled half is mocked; storage below is real.
vi.mock("use-query-params", () => ({
  StringParam: {},
  useQueryParam: () => [queryValue, mockSetQueryValue],
}));

import { useMetricDateRangeWithQueryAndStorage } from "./useMetricDateRangeWithQueryAndStorage";

const DEMO_SLOT = `local-time_range-${DEMO_PROJECT_NAME}`;
const SHARED_SLOT = "local-time_range";

const demoOptions = {
  defaultValue: DATE_RANGE_PRESET_PAST_24_HOURS,
  storageKeySuffix: `-${DEMO_PROJECT_NAME}`,
};

// The two real consumers reach the storage key by different branches of
// `localStorageKey ?? \`local-${key}\``: the Logs page lets it derive from the URL key, the dashboard
// passes an explicit base. Both are exercised so a dropped write or failed read is caught on either.
const CONSUMERS = [
  {
    name: "Logs (derived key)",
    options: demoOptions,
    demoSlot: DEMO_SLOT,
    sharedSlot: SHARED_SLOT,
  },
  {
    name: "Dashboards (explicit key)",
    options: {
      ...demoOptions,
      key: "dashboard_time_range",
      localStorageKey: "opik-project-insights-daterange",
    },
    demoSlot: `opik-project-insights-daterange-${DEMO_PROJECT_NAME}`,
    sharedSlot: "opik-project-insights-daterange",
  },
];

describe("demo date range — real storage boundary", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    queryValue = undefined;
    localStorage.clear();
  });

  it("should write a picked range into the demo project's own slot", () => {
    const { result } = renderHook(() =>
      useMetricDateRangeWithQueryAndStorage(demoOptions),
    );

    act(() => {
      result.current.handleDateRangeChange(
        PRESET_DATE_RANGES[DATE_RANGE_PRESET_PAST_7_DAYS],
      );
    });

    expect(localStorage.getItem(DEMO_SLOT)).toBe(
      JSON.stringify(DATE_RANGE_PRESET_PAST_7_DAYS),
    );
  });

  it("should read that range back instead of the 24h default", () => {
    localStorage.setItem(
      DEMO_SLOT,
      JSON.stringify(DATE_RANGE_PRESET_PAST_7_DAYS),
    );

    const { result } = renderHook(() =>
      useMetricDateRangeWithQueryAndStorage(demoOptions),
    );

    expect(result.current.dateRangeValue).toBe(DATE_RANGE_PRESET_PAST_7_DAYS);
  });

  it("should not leak the demo project's choice into the slot real projects use", () => {
    const { result } = renderHook(() =>
      useMetricDateRangeWithQueryAndStorage(demoOptions),
    );

    act(() => {
      result.current.handleDateRangeChange(
        PRESET_DATE_RANGES[DATE_RANGE_PRESET_PAST_7_DAYS],
      );
    });

    expect(localStorage.getItem(SHARED_SLOT)).toBeNull();
  });

  it("should ignore a range stored under the shared slot", () => {
    // A range the user picked on a real project must not decide the demo project's default.
    localStorage.setItem(SHARED_SLOT, JSON.stringify(DEFAULT_DATE_PRESET));

    const { result } = renderHook(() =>
      useMetricDateRangeWithQueryAndStorage(demoOptions),
    );

    expect(result.current.dateRangeValue).toBe(DATE_RANGE_PRESET_PAST_24_HOURS);
  });

  it("should apply the 24h default only while the demo slot is empty", () => {
    const { result: before } = renderHook(() =>
      useMetricDateRangeWithQueryAndStorage(demoOptions),
    );
    expect(before.current.dateRangeValue).toBe(DATE_RANGE_PRESET_PAST_24_HOURS);

    act(() => {
      before.current.handleDateRangeChange(
        PRESET_DATE_RANGES[DATE_RANGE_PRESET_PAST_7_DAYS],
      );
    });

    // A fresh mount, as a later page visit would be.
    const { result: after } = renderHook(() =>
      useMetricDateRangeWithQueryAndStorage(demoOptions),
    );
    expect(after.current.dateRangeValue).toBe(DATE_RANGE_PRESET_PAST_7_DAYS);
  });

  describe.each(CONSUMERS)("$name", ({ options, demoSlot, sharedSlot }) => {
    it("should write a picked range under this consumer's demo slot", () => {
      const { result } = renderHook(() =>
        useMetricDateRangeWithQueryAndStorage(options),
      );

      act(() => {
        result.current.handleDateRangeChange(
          PRESET_DATE_RANGES[DATE_RANGE_PRESET_PAST_7_DAYS],
        );
      });

      expect(localStorage.getItem(demoSlot)).toBe(
        JSON.stringify(DATE_RANGE_PRESET_PAST_7_DAYS),
      );
      expect(localStorage.getItem(sharedSlot)).toBeNull();
    });

    it("should read it back from this consumer's demo slot", () => {
      localStorage.setItem(
        demoSlot,
        JSON.stringify(DATE_RANGE_PRESET_PAST_7_DAYS),
      );

      const { result } = renderHook(() =>
        useMetricDateRangeWithQueryAndStorage(options),
      );

      expect(result.current.dateRangeValue).toBe(DATE_RANGE_PRESET_PAST_7_DAYS);
    });

    it("should not let this consumer's shared slot decide the demo default", () => {
      localStorage.setItem(sharedSlot, JSON.stringify(DEFAULT_DATE_PRESET));

      const { result } = renderHook(() =>
        useMetricDateRangeWithQueryAndStorage(options),
      );

      expect(result.current.dateRangeValue).toBe(
        DATE_RANGE_PRESET_PAST_24_HOURS,
      );
    });
  });

  it("should keep a non-demo project on the shared slot", () => {
    const { result } = renderHook(() =>
      useMetricDateRangeWithQueryAndStorage({
        defaultValue: DEFAULT_DATE_PRESET,
        storageKeySuffix: "",
      }),
    );

    act(() => {
      result.current.handleDateRangeChange(
        PRESET_DATE_RANGES[DATE_RANGE_PRESET_PAST_7_DAYS],
      );
    });

    expect(localStorage.getItem(SHARED_SLOT)).toBe(
      JSON.stringify(DATE_RANGE_PRESET_PAST_7_DAYS),
    );
    expect(localStorage.getItem(DEMO_SLOT)).toBeNull();
  });
});
