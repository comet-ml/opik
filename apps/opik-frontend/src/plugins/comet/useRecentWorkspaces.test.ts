import { describe, it, expect, beforeEach, vi, afterEach } from "vitest";
import { act, renderHook } from "@testing-library/react";

import useRecentWorkspaces from "./useRecentWorkspaces";

const ws = (name: string, org = "org-1", id = `id-${name}`) => ({
  workspaceId: id,
  workspaceName: name,
  organizationId: org,
});

const names = (result: { current: { recentWorkspaces: { workspaceName: string }[] } }) =>
  result.current.recentWorkspaces.map((w) => w.workspaceName);

describe("useRecentWorkspaces", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("starts with no recents", () => {
    const { result } = renderHook(() => useRecentWorkspaces());

    expect(result.current.recentWorkspaces).toEqual([]);
  });

  it("records a visit with identity and timestamp", () => {
    vi.setSystemTime(new Date(1000));
    const { result } = renderHook(() => useRecentWorkspaces());

    act(() => result.current.recordVisit(ws("workspace-a")));

    expect(result.current.recentWorkspaces[0]).toMatchObject({
      workspaceId: "id-workspace-a",
      workspaceName: "workspace-a",
      organizationId: "org-1",
      visitedAt: 1000,
    });
  });

  it("keeps the latest timestamp on repeat visits", () => {
    const { result } = renderHook(() => useRecentWorkspaces());

    vi.setSystemTime(new Date(1000));
    act(() => result.current.recordVisit(ws("workspace-a")));
    vi.setSystemTime(new Date(5000));
    act(() => result.current.recordVisit(ws("workspace-a")));

    expect(result.current.recentWorkspaces).toHaveLength(1);
    expect(result.current.recentWorkspaces[0].visitedAt).toBe(5000);
  });

  it("ignores an entry without a workspace name", () => {
    const { result } = renderHook(() => useRecentWorkspaces());

    act(() => result.current.recordVisit(ws("")));

    expect(result.current.recentWorkspaces).toEqual([]);
  });

  it("orders recent workspaces most-recent first", () => {
    const { result } = renderHook(() => useRecentWorkspaces());

    vi.setSystemTime(new Date(1000));
    act(() => result.current.recordVisit(ws("workspace-a")));
    vi.setSystemTime(new Date(2000));
    act(() => result.current.recordVisit(ws("workspace-b")));

    expect(names(result)).toEqual(["workspace-b", "workspace-a"]);
  });
});
