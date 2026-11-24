import React from "react";
import { describe, expect, it, vi } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import useModelPriceResolve from "./useModelPriceResolve";
import { MODEL_PRICE_RESOLVE_KEY } from "@/api/api";

const { post } = vi.hoisted(() => ({ post: vi.fn() }));

vi.mock("@/api/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/api/api")>();
  return { ...actual, default: { post } };
});

const request = {
  model: "gpt-4o",
  provider: "openai",
  usage: { prompt_tokens: 1000, completion_tokens: 500 },
};

const resolution = (version: number) => ({ list_version: version });

const renderResolve = (queryClient: QueryClient) =>
  renderHook(() => useModelPriceResolve(request), {
    wrapper: ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    ),
  });

describe("useModelPriceResolve", () => {
  it("doesn't answer from a price resolved a minute ago, which may have changed since", async () => {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    queryClient.setQueryData(
      [MODEL_PRICE_RESOLVE_KEY, request],
      resolution(7),
      {
        updatedAt: Date.now() - 60 * 1000,
      },
    );
    post.mockResolvedValueOnce({ data: resolution(8) });

    const { result } = renderResolve(queryClient);

    expect(result.current.isPending).toBe(true);
    await waitFor(() => expect(result.current.isPending).toBe(false));
    expect(result.current.data).toEqual(resolution(8));
  });

  it("answers at once from a price resolved a few seconds ago", () => {
    const queryClient = new QueryClient();
    queryClient.setQueryData(
      [MODEL_PRICE_RESOLVE_KEY, request],
      resolution(7),
      {
        updatedAt: Date.now() - 5 * 1000,
      },
    );

    const { result } = renderResolve(queryClient);

    expect(result.current.isPending).toBe(false);
    expect(result.current.data).toEqual(resolution(7));
    expect(post).not.toHaveBeenCalled();
  });
});
