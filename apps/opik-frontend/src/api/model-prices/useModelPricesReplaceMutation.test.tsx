import React from "react";
import { describe, expect, it, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import useModelPricesReplaceMutation from "./useModelPricesReplaceMutation";
import { MODEL_PRICE_HISTORY_KEY, MODEL_PRICES_KEY } from "@/api/api";

const { put } = vi.hoisted(() => ({ put: vi.fn() }));

vi.mock("@/api/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/api/api")>();
  return { ...actual, default: { put } };
});

const request = { rows: [], expected_version: 2 };

const setup = () => {
  const queryClient = new QueryClient({
    defaultOptions: { mutations: { retry: false } },
  });
  const invalidate = vi.spyOn(queryClient, "invalidateQueries");
  const wrapper = ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  const { result } = renderHook(() => useModelPricesReplaceMutation(), {
    wrapper,
  });
  return { result, invalidate };
};

const invalidatedKeys = (invalidate: { mock: { calls: unknown[][] } }) =>
  invalidate.mock.calls.map(
    (call) => (call[0] as { queryKey: unknown[] }).queryKey[0],
  );

describe("useModelPricesReplaceMutation", () => {
  it("refreshes the list and the history after a save", async () => {
    put.mockResolvedValueOnce({ data: { saved: true, version: 3 } });
    const { result, invalidate } = setup();

    await act(() => result.current.mutateAsync({ request, dryRun: false }));

    expect(invalidatedKeys(invalidate)).toEqual([
      MODEL_PRICES_KEY,
      MODEL_PRICE_HISTORY_KEY,
    ]);
  });

  it("refreshes the history when someone else saved first", async () => {
    put.mockRejectedValueOnce({ response: { status: 409 } });
    const { result, invalidate } = setup();

    await act(() =>
      result.current
        .mutateAsync({ request, dryRun: false })
        .catch(() => undefined),
    );

    expect(invalidatedKeys(invalidate)).toEqual([MODEL_PRICE_HISTORY_KEY]);
  });

  it("leaves the cache alone on other errors and on a dry run", async () => {
    put.mockRejectedValueOnce({ response: { status: 422 } });
    put.mockResolvedValueOnce({ data: { saved: false, version: 2 } });
    const { result, invalidate } = setup();

    await act(() =>
      result.current
        .mutateAsync({ request, dryRun: false })
        .catch(() => undefined),
    );
    await act(() => result.current.mutateAsync({ request, dryRun: true }));

    expect(invalidate).not.toHaveBeenCalled();
  });
});
