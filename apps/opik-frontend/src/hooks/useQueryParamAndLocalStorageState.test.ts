import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook } from "@testing-library/react";
import { StringParam } from "use-query-params";

const mockSetQueryValue = vi.fn();
const mockSetLocalStorageValue = vi.fn();

// `undefined` here means "no query param at all"; `null` means the param is present but null, which
// the hook treats differently from absent — see the "null vs undefined" tests below.
let queryValue: string | null | undefined;

// Distinguishes "key absent from localStorage" from "key holds null". use-local-storage-state falls
// back to defaultValue only in the first case (`string === null ? defaultValue : parse(string)`), so
// collapsing the two with `??` would hide that a stored null reaches the hook unchanged.
const NOT_SET = Symbol("not-set");
let localStorageValue: string | null | undefined | typeof NOT_SET = NOT_SET;

vi.mock("use-query-params", () => ({
  StringParam: {},
  useQueryParam: () => [queryValue, mockSetQueryValue],
}));

vi.mock("use-local-storage-state", () => ({
  default: (_key: string, options: { defaultValue: string }) => [
    localStorageValue === NOT_SET ? options.defaultValue : localStorageValue,
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
    localStorageValue = NOT_SET;
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

  // The hook tests absence two different ways: `??` for value resolution (line 63) and
  // `isUndefined(queryValue)` for the init sync (line 55). So a *null* query value is "absent" for
  // one and "present" for the other. That asymmetry is undocumented in the hook, so it is pinned
  // here rather than left to be rediscovered.
  describe("null vs undefined", () => {
    it("should keep a stored null rather than substituting the default", () => {
      localStorageValue = null;

      const { result } = renderSubject();

      expect(result.current[0]).toBeNull();
    });

    it("should fall back to storage when the query value is null, not just undefined", () => {
      queryValue = null;
      localStorageValue = "from-storage";

      const { result } = renderSubject();

      expect(result.current[0]).toBe("from-storage");
    });

    it("should not seed the URL when the query value is null, unlike undefined", () => {
      // `isUndefined(null)` is false, so the init sync treats a null param as already present.
      queryValue = null;
      localStorageValue = "from-storage";

      renderSubject();

      expect(mockSetQueryValue).not.toHaveBeenCalled();
    });

    it("should treat an empty-string query value as present, not absent", () => {
      // "" is not nullish, so `??` keeps it and storage is never consulted.
      queryValue = "";
      localStorageValue = "from-storage";

      const { result } = renderSubject();

      expect(result.current[0]).toBe("");
      expect(mockSetQueryValue).not.toHaveBeenCalled();
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
