import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook } from "@testing-library/react";
import { StringParam } from "use-query-params";

const mockSetQueryValue = vi.fn();
const mockSetLocalStorageValue = vi.fn();

let queryValue: string | null | undefined;
let localStorageValue: string | null | undefined;

vi.mock("use-query-params", () => ({
  StringParam: {},
  useQueryParam: () => [queryValue, mockSetQueryValue],
}));

vi.mock("use-local-storage-state", () => ({
  default: (_key: string, options: { defaultValue: string }) => [
    localStorageValue ?? options.defaultValue,
    mockSetLocalStorageValue,
  ],
}));

import useQueryParamAndLocalStorageState from "./useQueryParamAndLocalStorageState";

const renderSubject = (overrides: Record<string, unknown> = {}) =>
  renderHook(() =>
    useQueryParamAndLocalStorageState<string | null | undefined>({
      localStorageKey: "test-key",
      queryKey: "test_param",
      defaultValue: "fallback",
      queryParamConfig: StringParam,
      syncQueryWithLocalStorageOnInit: true,
      ...overrides,
    }),
  );

describe("useQueryParamAndLocalStorageState", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    queryValue = undefined;
    localStorageValue = undefined;
  });

  describe("init sync", () => {
    it("should write the current value into the URL on mount when the URL is empty", () => {
      renderSubject();

      expect(mockSetQueryValue).toHaveBeenCalledWith("fallback");
    });

    it("should not overwrite a value already in the URL", () => {
      queryValue = "from-url";

      renderSubject();

      expect(mockSetQueryValue).not.toHaveBeenCalled();
    });

    it("should not sync when syncQueryWithLocalStorageOnInit is off", () => {
      renderSubject({ syncQueryWithLocalStorageOnInit: false });

      expect(mockSetQueryValue).not.toHaveBeenCalled();
    });
  });

  // A caller whose defaultValue depends on async data (the demo project's 24h chart range is
  // resolved from a fetched project name) would otherwise get the placeholder default pinned into
  // the URL on mount, and the URL then wins over the real default forever.
  describe("initSyncReady", () => {
    it("should defer the URL write while the caller is not ready", () => {
      renderSubject({ initSyncReady: false });

      expect(mockSetQueryValue).not.toHaveBeenCalled();
    });

    it("should write once the caller becomes ready, using the settled default", () => {
      const { rerender } = renderHook(
        ({ ready, fallback }: { ready: boolean; fallback: string }) =>
          useQueryParamAndLocalStorageState<string | null | undefined>({
            localStorageKey: "test-key",
            queryKey: "test_param",
            defaultValue: fallback,
            queryParamConfig: StringParam,
            syncQueryWithLocalStorageOnInit: true,
            initSyncReady: ready,
          }),
        { initialProps: { ready: false, fallback: "placeholder" } },
      );

      expect(mockSetQueryValue).not.toHaveBeenCalled();

      rerender({ ready: true, fallback: "settled" });

      expect(mockSetQueryValue).toHaveBeenCalledTimes(1);
      expect(mockSetQueryValue).toHaveBeenCalledWith("settled");
    });

    it("should still skip the write when the URL filled in while waiting", () => {
      const { rerender } = renderHook(
        ({ ready }: { ready: boolean }) =>
          useQueryParamAndLocalStorageState<string | null | undefined>({
            localStorageKey: "test-key",
            queryKey: "test_param",
            defaultValue: "fallback",
            queryParamConfig: StringParam,
            syncQueryWithLocalStorageOnInit: true,
            initSyncReady: ready,
          }),
        { initialProps: { ready: false } },
      );

      queryValue = "user-picked";
      rerender({ ready: true });

      expect(mockSetQueryValue).not.toHaveBeenCalled();
    });

    it("should default to syncing immediately when initSyncReady is omitted", () => {
      renderSubject();

      expect(mockSetQueryValue).toHaveBeenCalledWith("fallback");
    });
  });

  describe("value resolution", () => {
    it("should prefer the URL value over the stored one", () => {
      queryValue = "from-url";
      localStorageValue = "from-storage";

      const { result } = renderSubject();

      expect(result.current[0]).toBe("from-url");
    });

    it("should fall back to the stored value when the URL is empty", () => {
      localStorageValue = "from-storage";

      const { result } = renderSubject();

      expect(result.current[0]).toBe("from-storage");
    });

    it("should write to both the URL and storage on set", () => {
      const { result } = renderSubject();

      result.current[1]("chosen");

      expect(mockSetLocalStorageValue).toHaveBeenCalledWith("chosen");
      expect(mockSetQueryValue).toHaveBeenCalledWith("chosen");
    });
  });
});
