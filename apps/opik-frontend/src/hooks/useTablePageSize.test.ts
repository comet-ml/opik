import { renderHook } from "@testing-library/react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import useTablePageSize, {
  useTablePageSizeWithStorage,
} from "./useTablePageSize";

const setSize = vi.fn();
const setStoredSize = vi.fn();
let mockRawSize: unknown = undefined;
let mockStoredSize: unknown = undefined;
let mockDefaultPageSize = 100;

vi.mock("use-query-params", () => ({
  NumberParam: {},
  useQueryParam: vi.fn(() => [mockRawSize, setSize]),
}));

vi.mock("use-local-storage-state", () => ({
  default: vi.fn(() => [mockStoredSize, setStoredSize]),
}));

vi.mock("@/contexts/feature-toggles-provider", () => ({
  useDefaultPageSize: () => mockDefaultPageSize,
}));

describe("useTablePageSize", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockRawSize = undefined;
    mockStoredSize = undefined;
    mockDefaultPageSize = 100;
  });

  it("returns deployment default when URL param is absent", () => {
    mockRawSize = undefined;
    mockDefaultPageSize = 25;

    const { result } = renderHook(() => useTablePageSize());

    expect(result.current[0]).toBe(25);
  });

  it("returns the URL value when it is a valid positive integer", () => {
    mockRawSize = 50;
    mockDefaultPageSize = 25;

    const { result } = renderHook(() => useTablePageSize());

    expect(result.current[0]).toBe(50);
  });

  it.each([
    ["NaN", NaN],
    ["zero", 0],
    ["negative", -10],
    ["fractional", 12.5],
    ["null", null],
    ["string", "abc"],
  ])("falls back to deployment default when URL value is %s", (_, value) => {
    mockRawSize = value;
    mockDefaultPageSize = 25;

    const { result } = renderHook(() => useTablePageSize());

    expect(result.current[0]).toBe(25);
  });

  it("exposes the query-param setter as the second tuple element", () => {
    const { result } = renderHook(() => useTablePageSize());

    result.current[1](200);
    expect(setSize).toHaveBeenCalledWith(200);
  });
});

describe("useTablePageSizeWithStorage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockRawSize = undefined;
    mockStoredSize = undefined;
    mockDefaultPageSize = 100;
  });

  it("URL value wins over both localStorage and deployment default", () => {
    mockRawSize = 50;
    mockStoredSize = 200;
    mockDefaultPageSize = 25;

    const { result } = renderHook(() => useTablePageSizeWithStorage("k"));

    expect(result.current[0]).toBe(50);
  });

  it("localStorage value wins over deployment default when URL is absent", () => {
    mockRawSize = undefined;
    mockStoredSize = 75;
    mockDefaultPageSize = 25;

    const { result } = renderHook(() => useTablePageSizeWithStorage("k"));

    expect(result.current[0]).toBe(75);
  });

  it("falls back to deployment default when neither URL nor localStorage has a value", () => {
    mockRawSize = undefined;
    mockStoredSize = undefined;
    mockDefaultPageSize = 25;

    const { result } = renderHook(() => useTablePageSizeWithStorage("k"));

    expect(result.current[0]).toBe(25);
  });

  it.each([
    ["NaN", NaN],
    ["zero", 0],
    ["negative", -10],
    ["fractional", 12.5],
  ])(
    "ignores malformed localStorage value (%s) and falls through to deployment default",
    (_, value) => {
      mockStoredSize = value;
      mockDefaultPageSize = 25;

      const { result } = renderHook(() => useTablePageSizeWithStorage("k"));

      expect(result.current[0]).toBe(25);
    },
  );

  it("setSize writes to both URL and localStorage for valid sizes", () => {
    const { result } = renderHook(() => useTablePageSizeWithStorage("k"));

    result.current[1](150);

    expect(setSize).toHaveBeenCalledWith(150);
    expect(setStoredSize).toHaveBeenCalledWith(150);
  });

  it("setSize does NOT write invalid sizes (null) to localStorage", () => {
    const { result } = renderHook(() => useTablePageSizeWithStorage("k"));

    result.current[1](null);

    expect(setSize).toHaveBeenCalledWith(null);
    expect(setStoredSize).not.toHaveBeenCalled();
  });
});
