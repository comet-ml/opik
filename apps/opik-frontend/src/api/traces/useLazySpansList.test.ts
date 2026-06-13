import { renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import useLazySpansList from "@/api/traces/useLazySpansList";
import useSpansList, {
  UseSpansListParams,
  UseSpansListResponse,
} from "@/api/traces/useSpansList";

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
});
