import React from "react";
import { describe, expect, it, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import useModelPriceHistory from "./useModelPriceHistory";

const { get } = vi.hoisted(() => ({ get: vi.fn() }));

vi.mock("@/api/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/api/api")>();
  return { ...actual, default: { get } };
});

const entry = (version: number) => ({
  list_version: version,
  row_id: `row-${version}`,
  action: "created",
  created_at: "2026-11-01T10:00:00Z",
  created_by: "maria",
});

const page = (number: number, versions: number[], total: number) => ({
  data: { page: number, size: 2, total, content: versions.map(entry) },
});

const setup = () => {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const wrapper = ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  return renderHook(() => useModelPriceHistory({ size: 2 }), { wrapper })
    .result;
};

describe("useModelPriceHistory", () => {
  it("loads older pages until every entry is loaded", async () => {
    get
      .mockResolvedValueOnce(page(1, [5, 4], 3))
      .mockResolvedValueOnce(page(2, [3], 3));
    const result = setup();

    await waitFor(() => expect(result.current.hasNextPage).toBe(true));
    expect(get.mock.calls[0][1].params).toEqual({ page: 1, size: 2 });

    await act(() => result.current.fetchNextPage());

    await waitFor(() => expect(result.current.hasNextPage).toBe(false));
    expect(get).toHaveBeenCalledTimes(2);
    expect(get.mock.calls[1][1].params).toEqual({ page: 2, size: 2 });
    expect(
      result.current.data?.pages.flatMap((loaded) =>
        loaded.content.map((item) => item.list_version),
      ),
    ).toEqual([5, 4, 3]);
  });

  it("stops at an empty page even when the total says there is more", async () => {
    get.mockResolvedValueOnce(page(1, [], 4));
    const result = setup();

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.hasNextPage).toBe(false);
  });
});
