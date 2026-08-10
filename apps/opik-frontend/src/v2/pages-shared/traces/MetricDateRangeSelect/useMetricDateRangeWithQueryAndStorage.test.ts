import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook } from "@testing-library/react";
import {
  DATE_RANGE_PRESET_ALLTIME,
  DATE_RANGE_PRESET_PAST_24_HOURS,
  DEFAULT_DATE_PRESET,
} from "./constants";
import { INTERVAL_TYPE } from "@/api/projects/useProjectMetric";

vi.mock("@/hooks/useQueryParamAndLocalStorageState", () => ({
  default: vi.fn(),
}));

import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";
import { useMetricDateRangeWithQueryAndStorage } from "./useMetricDateRangeWithQueryAndStorage";

const mockSetValue = vi.fn();

describe("useMetricDateRangeWithQueryAndStorage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(useQueryParamAndLocalStorageState).mockReturnValue([
      DEFAULT_DATE_PRESET,
      mockSetValue,
    ]);
  });

  describe("excludePresets", () => {
    it("should return DEFAULT_DATE_PRESET when stored value matches an excluded preset", () => {
      vi.mocked(useQueryParamAndLocalStorageState).mockReturnValue([
        DATE_RANGE_PRESET_ALLTIME,
        mockSetValue,
      ]);

      const { result } = renderHook(() =>
        useMetricDateRangeWithQueryAndStorage({
          excludePresets: [DATE_RANGE_PRESET_ALLTIME],
        }),
      );

      expect(result.current.dateRangeValue).toBe(DEFAULT_DATE_PRESET);
    });

    it("should not call setValue when excluded preset is stored (coercion is computed, not synced back)", () => {
      vi.mocked(useQueryParamAndLocalStorageState).mockReturnValue([
        DATE_RANGE_PRESET_ALLTIME,
        mockSetValue,
      ]);

      renderHook(() =>
        useMetricDateRangeWithQueryAndStorage({
          excludePresets: [DATE_RANGE_PRESET_ALLTIME],
        }),
      );

      expect(mockSetValue).not.toHaveBeenCalled();
    });

    it("should preserve stored value when excludePresets is not provided", () => {
      vi.mocked(useQueryParamAndLocalStorageState).mockReturnValue([
        DATE_RANGE_PRESET_ALLTIME,
        mockSetValue,
      ]);

      const { result } = renderHook(() =>
        useMetricDateRangeWithQueryAndStorage(),
      );

      expect(result.current.dateRangeValue).toBe(DATE_RANGE_PRESET_ALLTIME);
      expect(mockSetValue).not.toHaveBeenCalled();
    });

    it("should preserve stored value when excludePresets is empty", () => {
      vi.mocked(useQueryParamAndLocalStorageState).mockReturnValue([
        DATE_RANGE_PRESET_ALLTIME,
        mockSetValue,
      ]);

      const { result } = renderHook(() =>
        useMetricDateRangeWithQueryAndStorage({ excludePresets: [] }),
      );

      expect(result.current.dateRangeValue).toBe(DATE_RANGE_PRESET_ALLTIME);
      expect(mockSetValue).not.toHaveBeenCalled();
    });

    it("should not call setValue when stored value is not excluded", () => {
      const { result } = renderHook(() =>
        useMetricDateRangeWithQueryAndStorage({
          excludePresets: [DATE_RANGE_PRESET_ALLTIME],
        }),
      );

      expect(result.current.dateRangeValue).toBe(DEFAULT_DATE_PRESET);
      expect(mockSetValue).not.toHaveBeenCalled();
    });
  });

  // The seeded demo project is compressed into ~10h so its ids clear the UUIDv7 ingestion
  // window, which only charts as a curve at hourly granularity. Granularity follows the
  // selected range, so the demo project overrides the workspace-wide 30-day default.
  describe("defaultValue", () => {
    it("should pass the caller's default down as the stored-state default", () => {
      renderHook(() =>
        useMetricDateRangeWithQueryAndStorage({
          defaultValue: DATE_RANGE_PRESET_PAST_24_HOURS,
        }),
      );

      expect(useQueryParamAndLocalStorageState).toHaveBeenCalledWith(
        expect.objectContaining({
          defaultValue: DATE_RANGE_PRESET_PAST_24_HOURS,
        }),
      );
    });

    it("should default to DEFAULT_DATE_PRESET when the caller passes none", () => {
      renderHook(() => useMetricDateRangeWithQueryAndStorage());

      expect(useQueryParamAndLocalStorageState).toHaveBeenCalledWith(
        expect.objectContaining({ defaultValue: DEFAULT_DATE_PRESET }),
      );
    });

    it("should fall back to the caller's default when no value is stored", () => {
      vi.mocked(useQueryParamAndLocalStorageState).mockReturnValue([
        undefined,
        mockSetValue,
      ]);

      const { result } = renderHook(() =>
        useMetricDateRangeWithQueryAndStorage({
          defaultValue: DATE_RANGE_PRESET_PAST_24_HOURS,
        }),
      );

      expect(result.current.dateRangeValue).toBe(
        DATE_RANGE_PRESET_PAST_24_HOURS,
      );
    });

    it("should resolve a 24h default to hourly granularity", () => {
      vi.mocked(useQueryParamAndLocalStorageState).mockReturnValue([
        DATE_RANGE_PRESET_PAST_24_HOURS,
        mockSetValue,
      ]);

      const { result } = renderHook(() =>
        useMetricDateRangeWithQueryAndStorage({
          defaultValue: DATE_RANGE_PRESET_PAST_24_HOURS,
        }),
      );

      expect(result.current.interval).toBe(INTERVAL_TYPE.HOURLY);
    });

    it("should still resolve the 30-day default to daily granularity", () => {
      const { result } = renderHook(() =>
        useMetricDateRangeWithQueryAndStorage(),
      );

      expect(result.current.interval).toBe(INTERVAL_TYPE.DAILY);
    });

    it("should coerce an excluded preset to the caller's default, not the global one", () => {
      vi.mocked(useQueryParamAndLocalStorageState).mockReturnValue([
        DATE_RANGE_PRESET_ALLTIME,
        mockSetValue,
      ]);

      const { result } = renderHook(() =>
        useMetricDateRangeWithQueryAndStorage({
          defaultValue: DATE_RANGE_PRESET_PAST_24_HOURS,
          excludePresets: [DATE_RANGE_PRESET_ALLTIME],
        }),
      );

      expect(result.current.dateRangeValue).toBe(
        DATE_RANGE_PRESET_PAST_24_HOURS,
      );
    });

    it("should forward initSyncReady so the URL sync waits for an async default", () => {
      renderHook(() =>
        useMetricDateRangeWithQueryAndStorage({
          defaultValue: DATE_RANGE_PRESET_PAST_24_HOURS,
          initSyncReady: false,
        }),
      );

      expect(useQueryParamAndLocalStorageState).toHaveBeenCalledWith(
        expect.objectContaining({ initSyncReady: false }),
      );
    });
  });

  describe("storageKeySuffix", () => {
    it("should append the suffix to an explicit localStorage key", () => {
      renderHook(() =>
        useMetricDateRangeWithQueryAndStorage({
          localStorageKey: "opik-project-insights-daterange",
          storageKeySuffix: "-some-scope",
        }),
      );

      expect(useQueryParamAndLocalStorageState).toHaveBeenCalledWith(
        expect.objectContaining({
          localStorageKey: "opik-project-insights-daterange-some-scope",
        }),
      );
    });

    it("should append the suffix to the key derived from the URL key", () => {
      renderHook(() =>
        useMetricDateRangeWithQueryAndStorage({
          storageKeySuffix: "-some-scope",
        }),
      );

      expect(useQueryParamAndLocalStorageState).toHaveBeenCalledWith(
        expect.objectContaining({
          localStorageKey: "local-time_range-some-scope",
        }),
      );
    });

    it("should leave the key untouched when no suffix is given", () => {
      renderHook(() =>
        useMetricDateRangeWithQueryAndStorage({
          localStorageKey: "opik-project-insights-daterange",
        }),
      );

      expect(useQueryParamAndLocalStorageState).toHaveBeenCalledWith(
        expect.objectContaining({
          localStorageKey: "opik-project-insights-daterange",
        }),
      );
    });

    // Regression: a sticky range persisted by another project must not mask the demo default.
    // A suffixed key reads a different slot, so the stored value simply is not seen.
    it("should not let a value stored under the unsuffixed key win", () => {
      const storage: Record<string, string> = {
        "opik-project-insights-daterange": DEFAULT_DATE_PRESET,
      };
      vi.mocked(useQueryParamAndLocalStorageState).mockImplementation(
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        ({ localStorageKey, defaultValue }: any) => [
          storage[localStorageKey] ?? defaultValue,
          mockSetValue,
        ],
      );

      const { result } = renderHook(() =>
        useMetricDateRangeWithQueryAndStorage({
          localStorageKey: "opik-project-insights-daterange",
          defaultValue: DATE_RANGE_PRESET_PAST_24_HOURS,
          storageKeySuffix: "-some-scope",
        }),
      );

      expect(result.current.dateRangeValue).toBe(
        DATE_RANGE_PRESET_PAST_24_HOURS,
      );
      expect(result.current.interval).toBe(INTERVAL_TYPE.HOURLY);
    });
  });
});
