import { renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import useLazySpansList, {
  shouldLoadFullSpansData,
} from "@/api/traces/useLazySpansList";
import useSpansList, {
  UseSpansListParams,
  UseSpansListResponse,
} from "@/api/traces/useSpansList";
import { COLUMN_CUSTOM_ID, COLUMN_TYPE } from "@/types/shared";

vi.mock("@/api/traces/useSpansList", () => ({
  default: vi.fn(),
}));

const useSpansListMock = vi.mocked(useSpansList);

const params: UseSpansListParams = {
  projectId: "project-1",
  traceId: "trace-1",
  page: 1,
  size: 100,
};

const makeResponse = (total: number): UseSpansListResponse => ({
  content: [],
  sortable_by: [],
  total,
});

const makeQuery = (overrides: Partial<ReturnType<typeof useSpansList>> = {}) =>
  ({
    data: undefined,
    isPending: false,
    isPlaceholderData: false,
    ...overrides,
  }) as unknown as ReturnType<typeof useSpansList>;

const makeFilter = (field: string, key = "") => ({
  id: `${field}-${key}`,
  field,
  key,
  type: COLUMN_TYPE.string,
  operator: "contains" as const,
  value: "value",
});

describe("useLazySpansList", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("keeps the full spans query disabled when total exceeds the guard", () => {
    const lightQuery = makeQuery({ data: makeResponse(501) });
    const fullQuery = makeQuery({ data: makeResponse(501) });
    useSpansListMock.mockImplementation((queryParams) =>
      queryParams.exclude ? lightQuery : fullQuery,
    );

    const { result } = renderHook(() =>
      useLazySpansList(params, { enabled: true }),
    );

    expect(useSpansListMock).toHaveBeenNthCalledWith(
      1,
      { ...params, exclude: ["input", "output"] },
      { enabled: true },
    );
    expect(useSpansListMock).toHaveBeenNthCalledWith(
      2,
      params,
      expect.objectContaining({ enabled: false }),
    );
    expect(result.current.query).toBe(lightQuery);
    expect(result.current.isLazyLoading).toBe(false);
  });

  it("returns light spans while the requested full spans query is pending", () => {
    const lightQuery = makeQuery({ data: makeResponse(100) });
    const fullQuery = makeQuery({ isPending: true });
    useSpansListMock.mockImplementation((queryParams) =>
      queryParams.exclude ? lightQuery : fullQuery,
    );

    const { result } = renderHook(() =>
      useLazySpansList(params, { enabled: true }),
    );

    expect(result.current.query).toBe(lightQuery);
    expect(result.current.isLazyLoading).toBe(true);
  });

  it("loads full spans immediately when payload data is required", () => {
    const lightQuery = makeQuery({ data: makeResponse(501) });
    const fullQuery = makeQuery({ data: makeResponse(501) });
    useSpansListMock.mockImplementation((queryParams) =>
      queryParams.exclude ? lightQuery : fullQuery,
    );

    const { result } = renderHook(() =>
      useLazySpansList(params, { enabled: true }, { loadFullData: true }),
    );

    expect(useSpansListMock).toHaveBeenNthCalledWith(
      1,
      { ...params, exclude: ["input", "output"] },
      expect.objectContaining({ enabled: false }),
    );
    expect(useSpansListMock).toHaveBeenNthCalledWith(
      2,
      params,
      expect.objectContaining({ enabled: true }),
    );
    expect(result.current.query).toBe(fullQuery);
    expect(result.current.isLazyLoading).toBe(false);
  });

  it("detects search and filters that need full span payloads", () => {
    expect(shouldLoadFullSpansData("prompt", [])).toBe(true);
    expect(
      shouldLoadFullSpansData(undefined, [
        makeFilter("input"),
        makeFilter(COLUMN_CUSTOM_ID, "output.answer"),
      ]),
    ).toBe(true);
    expect(shouldLoadFullSpansData(undefined, [makeFilter("name")])).toBe(
      false,
    );
    expect(
      shouldLoadFullSpansData(undefined, [{ field: COLUMN_CUSTOM_ID, key: 1 }]),
    ).toBe(false);
    expect(shouldLoadFullSpansData(undefined, { field: "input" })).toBe(false);
  });
});
