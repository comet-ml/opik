import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook } from "@testing-library/react";
import {
  DATE_RANGE_PRESET_ALLTIME,
  DATE_RANGE_PRESET_PAST_7_DAYS,
  DATE_RANGE_PRESET_PAST_24_HOURS,
  DEFAULT_DATE_PRESET,
} from "./constants";
import { INTERVAL_TYPE } from "@/api/projects/useProjectMetric";
import { PRESET_DATE_RANGES } from "@/shared/DateRangeSelect";
import { DEMO_PROJECT_NAME } from "@/constants/shared";

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

  // Entity-scoped logs (experiment Logs tab, playground, trials, annotation queues) are already
  // narrowed to one entity, so a trailing 30-day window only hides traces of anything older. They
  // pass their own default instead.
  describe("caller-supplied defaultValue", () => {
    it("seeds the stored default with the caller's preset", () => {
      renderHook(() =>
        useMetricDateRangeWithQueryAndStorage({
          defaultValue: DATE_RANGE_PRESET_ALLTIME,
        }),
      );

      expect(useQueryParamAndLocalStorageState).toHaveBeenCalledWith(
        expect.objectContaining({ defaultValue: DATE_RANGE_PRESET_ALLTIME }),
      );
    });

    it("falls back to the caller's preset when nothing is stored", () => {
      vi.mocked(useQueryParamAndLocalStorageState).mockReturnValue([
        undefined,
        mockSetValue,
      ]);

      const { result } = renderHook(() =>
        useMetricDateRangeWithQueryAndStorage({
          defaultValue: DATE_RANGE_PRESET_ALLTIME,
        }),
      );

      expect(result.current.dateRangeValue).toBe(DATE_RANGE_PRESET_ALLTIME);
    });

    it("lets an explicitly chosen range win over the caller's preset", () => {
      vi.mocked(useQueryParamAndLocalStorageState).mockReturnValue([
        DATE_RANGE_PRESET_PAST_7_DAYS,
        mockSetValue,
      ]);

      const { result } = renderHook(() =>
        useMetricDateRangeWithQueryAndStorage({
          defaultValue: DATE_RANGE_PRESET_ALLTIME,
        }),
      );

      expect(result.current.dateRangeValue).toBe(DATE_RANGE_PRESET_PAST_7_DAYS);
      expect(mockSetValue).not.toHaveBeenCalled();
    });

    it("keeps the trailing 30-day window when no defaultValue is supplied", () => {
      vi.mocked(useQueryParamAndLocalStorageState).mockReturnValue([
        undefined,
        mockSetValue,
      ]);

      const { result } = renderHook(() =>
        useMetricDateRangeWithQueryAndStorage(),
      );

      expect(useQueryParamAndLocalStorageState).toHaveBeenCalledWith(
        expect.objectContaining({ defaultValue: DEFAULT_DATE_PRESET }),
      );
      expect(result.current.dateRangeValue).toBe(DEFAULT_DATE_PRESET);
    });

    it("still coerces an excluded preset to the caller's default", () => {
      vi.mocked(useQueryParamAndLocalStorageState).mockReturnValue([
        DATE_RANGE_PRESET_ALLTIME,
        mockSetValue,
      ]);

      const { result } = renderHook(() =>
        useMetricDateRangeWithQueryAndStorage({
          defaultValue: DATE_RANGE_PRESET_PAST_7_DAYS,
          excludePresets: [DATE_RANGE_PRESET_ALLTIME],
        }),
      );

      expect(result.current.dateRangeValue).toBe(DATE_RANGE_PRESET_PAST_7_DAYS);
    });
  });

  // The demo project's 24h default only reads as a curve at hourly granularity, and granularity
  // follows the selected range — so the range and the interval have to move together.
  describe("granularity follows the default", () => {
    it("should resolve a 24h default to hourly buckets", () => {
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

    it("should keep the 30-day default on daily buckets", () => {
      const { result } = renderHook(() =>
        useMetricDateRangeWithQueryAndStorage(),
      );

      expect(result.current.interval).toBe(INTERVAL_TYPE.DAILY);
    });
  });

  // A stored range is sticky across projects and outranks any defaultValue, so a project needing a
  // different default has to persist into its own slot or it inherits whatever was picked elsewhere.
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
    it("should not let a value stored under the unsuffixed key win", () => {
      const storage: Record<string, string> = {
        "opik-project-insights-daterange": DEFAULT_DATE_PRESET,
      };
      vi.mocked(useQueryParamAndLocalStorageState).mockImplementation(
        ({
          localStorageKey,
          defaultValue,
        }: {
          localStorageKey: string;
          // `unknown`, not the value type: the hook is generic, so a narrower annotation here does
          // not typecheck against its signature.
          defaultValue: unknown;
        }) => [
          storage[localStorageKey] ?? (defaultValue as string),
          mockSetValue,
        ],
      );

      const { result } = renderHook(() =>
        useMetricDateRangeWithQueryAndStorage({
          localStorageKey: "opik-project-insights-daterange",
          defaultValue: DATE_RANGE_PRESET_PAST_24_HOURS,
          storageKeySuffix: "-demo",
        }),
      );

      expect(result.current.dateRangeValue).toBe(
        DATE_RANGE_PRESET_PAST_24_HOURS,
      );
      expect(result.current.interval).toBe(INTERVAL_TYPE.HOURLY);
    });
  });

  // The 24h value is only the *initial* default: a range the user picks must win and never be
  // reverted to it.
  describe("a user's own selection on the demo project", () => {
    const demoOptions = {
      defaultValue: DATE_RANGE_PRESET_PAST_24_HOURS,
      storageKeySuffix: `-${DEMO_PROJECT_NAME}`,
    };

    // Storage is mocked in this file, so this proves the handler reaches the setter — not that the
    // write lands. The real boundary is covered in
    // useMetricDateRangeWithQueryAndStorage.persistence.test.ts.
    it("should forward the selection to the setter", () => {
      const { result } = renderHook(() =>
        useMetricDateRangeWithQueryAndStorage(demoOptions),
      );

      result.current.handleDateRangeChange(
        PRESET_DATE_RANGES[DATE_RANGE_PRESET_PAST_7_DAYS],
      );

      expect(mockSetValue).toHaveBeenCalledWith(DATE_RANGE_PRESET_PAST_7_DAYS);
    });

    it("should keep the selection instead of reverting to the 24h default", () => {
      vi.mocked(useQueryParamAndLocalStorageState).mockReturnValue([
        DATE_RANGE_PRESET_PAST_7_DAYS,
        mockSetValue,
      ]);

      const { result } = renderHook(() =>
        useMetricDateRangeWithQueryAndStorage(demoOptions),
      );

      expect(result.current.dateRangeValue).toBe(DATE_RANGE_PRESET_PAST_7_DAYS);
    });

    it("should not write anything back after a selection is in place", () => {
      vi.mocked(useQueryParamAndLocalStorageState).mockReturnValue([
        DATE_RANGE_PRESET_PAST_7_DAYS,
        mockSetValue,
      ]);

      const { rerender } = renderHook(() =>
        useMetricDateRangeWithQueryAndStorage(demoOptions),
      );
      rerender();

      expect(mockSetValue).not.toHaveBeenCalled();
    });

    it("should let the granularity follow the selection, not the demo default", () => {
      vi.mocked(useQueryParamAndLocalStorageState).mockReturnValue([
        DATE_RANGE_PRESET_PAST_7_DAYS,
        mockSetValue,
      ]);

      const { result } = renderHook(() =>
        useMetricDateRangeWithQueryAndStorage(demoOptions),
      );

      // 7 days buckets daily; if the 24h default were still winning this would be HOURLY.
      expect(result.current.interval).toBe(INTERVAL_TYPE.DAILY);
    });
  });
});
