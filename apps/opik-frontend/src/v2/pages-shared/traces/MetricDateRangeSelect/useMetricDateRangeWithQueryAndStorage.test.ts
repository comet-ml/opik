import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook } from "@testing-library/react";
import {
  DATE_RANGE_PRESET_ALLTIME,
  DATE_RANGE_PRESET_PAST_7_DAYS,
  DEFAULT_DATE_PRESET,
} from "./constants";

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
});
