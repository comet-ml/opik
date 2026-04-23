import { renderHook } from "@testing-library/react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import useTablePageSize from "./useTablePageSize";

const setSize = vi.fn();
let mockRawSize: unknown = undefined;
let mockDefaultPageSize = 100;

vi.mock("use-query-params", () => ({
  NumberParam: {},
  useQueryParam: vi.fn(() => [mockRawSize, setSize]),
}));

vi.mock("@/contexts/feature-toggles-provider", () => ({
  useDefaultPageSize: () => mockDefaultPageSize,
}));

describe("useTablePageSize", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockRawSize = undefined;
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
