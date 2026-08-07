import React from "react";
import { describe, it, expect, beforeEach, vi } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

import useWorkspaceByName from "./useWorkspaceByName";
import api from "./api";

vi.mock("./api", () => ({
  default: { post: vi.fn() },
}));

const post = vi.mocked(api.post);

const wrapper = ({ children }: { children: React.ReactNode }) => {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
};

const axiosError = (status: number, data: unknown = undefined) =>
  Object.assign(new Error(`Request failed with status code ${status}`), {
    isAxiosError: true,
    response: { status, data },
  });

// What the backend actually sends for "no such workspace / not visible to you".
const notFoundBody = { msg: "No such workspace!", code: 404, data: null };

const workspace = {
  workspaceId: "ws-id",
  workspaceName: "my-team",
  organizationId: "org-id",
  default: false,
  member: true,
};

describe("useWorkspaceByName", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("resolves a workspace by name through the point read", async () => {
    post.mockResolvedValue({ data: workspace });

    const { result } = renderHook(
      () => useWorkspaceByName({ workspaceName: "my-team" }),
      { wrapper },
    );

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual(workspace);
    expect(post).toHaveBeenCalledWith(
      "/workspaces/retrieve",
      { workspaceName: "my-team" },
      expect.anything(),
    );
  });

  // The endpoint answers 404 both for "no such name" and "you may not see it", exactly as the list it
  // replaces simply did not contain the row. Surfacing that as an error would turn a normal answer
  // into a broken render.
  it("treats the backend's 404 as an absent workspace rather than an error", async () => {
    post.mockRejectedValue(axiosError(404, notFoundBody));

    const { result } = renderHook(
      () => useWorkspaceByName({ workspaceName: "not-mine" }),
      { wrapper },
    );

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeNull();
    expect(result.current.isError).toBe(false);
  });

  // A frontend deployed ahead of its backend gets a router 404 with no error body. Reading that as
  // "this workspace does not exist" would send every user to the private-project page.
  it("does not mistake a missing endpoint for a missing workspace", async () => {
    post.mockRejectedValue(axiosError(404, ""));

    const { result } = renderHook(
      () => useWorkspaceByName({ workspaceName: "my-team" }),
      { wrapper },
    );

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.data).toBeUndefined();
  });

  it("still reports a real failure", async () => {
    post.mockRejectedValue(axiosError(500, { msg: "boom", code: 500 }));

    const { result } = renderHook(
      () => useWorkspaceByName({ workspaceName: "my-team" }),
      { wrapper },
    );

    await waitFor(() => expect(result.current.isError).toBe(true));
  });

  it("does not fire without a workspace name", () => {
    renderHook(() => useWorkspaceByName({ workspaceName: "" }), { wrapper });

    expect(post).not.toHaveBeenCalled();
  });

  it("honours an explicit enabled: false", () => {
    renderHook(
      () =>
        useWorkspaceByName({ workspaceName: "my-team" }, { enabled: false }),
      { wrapper },
    );

    expect(post).not.toHaveBeenCalled();
  });

  it("keys the query by name so two names do not share a cache entry", async () => {
    post.mockResolvedValue({ data: workspace });

    const { result, rerender } = renderHook(
      ({ name }: { name: string }) =>
        useWorkspaceByName({ workspaceName: name }),
      { wrapper, initialProps: { name: "my-team" } },
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    post.mockResolvedValue({
      data: { ...workspace, workspaceName: "other-team" },
    });
    rerender({ name: "other-team" });

    await waitFor(() =>
      expect(result.current.data?.workspaceName).toBe("other-team"),
    );
    expect(post).toHaveBeenCalledTimes(2);
  });
});
