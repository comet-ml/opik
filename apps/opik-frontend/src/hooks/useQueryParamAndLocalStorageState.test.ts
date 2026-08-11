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

    it("should fall back to the default when neither is set", () => {
      const { result } = renderSubject();

      expect(result.current[0]).toBe("fallback");
    });
  });

  describe("setValue", () => {
    it("should write to both the URL and storage, so the choice survives navigation", () => {
      const { result } = renderSubject();

      result.current[1]("chosen");

      expect(mockSetLocalStorageValue).toHaveBeenCalledWith("chosen");
      expect(mockSetQueryValue).toHaveBeenCalledWith("chosen");
    });

    it("should resolve an updater function against the current value", () => {
      queryValue = "from-url";

      const { result } = renderSubject();

      result.current[1]((current) => `${current}-updated`);

      expect(mockSetLocalStorageValue).toHaveBeenCalledWith("from-url-updated");
      expect(mockSetQueryValue).toHaveBeenCalledWith("from-url-updated");
    });
  });

  describe("init sync", () => {
    it("should seed the URL from the current value on mount when the URL is empty", () => {
      renderSubject();

      expect(mockSetQueryValue).toHaveBeenCalledWith("fallback");
    });

    it("should seed the URL from the stored value when one exists", () => {
      localStorageValue = "from-storage";

      renderSubject();

      expect(mockSetQueryValue).toHaveBeenCalledWith("from-storage");
    });

    it("should not overwrite a value already present in the URL", () => {
      queryValue = "from-url";

      renderSubject();

      expect(mockSetQueryValue).not.toHaveBeenCalled();
    });

    it("should not sync when syncQueryWithLocalStorageOnInit is off", () => {
      renderSubject({ syncQueryWithLocalStorageOnInit: false });

      expect(mockSetQueryValue).not.toHaveBeenCalled();
    });

    it("should sync only once, not on every render", () => {
      const { rerender } = renderSubject();
      rerender();
      rerender();

      expect(mockSetQueryValue).toHaveBeenCalledTimes(1);
    });
  });
});
