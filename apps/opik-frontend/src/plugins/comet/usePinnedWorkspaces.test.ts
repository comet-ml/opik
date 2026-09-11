import { describe, it, expect, beforeEach } from "vitest";
import { act, renderHook } from "@testing-library/react";

import usePinnedWorkspaces from "./usePinnedWorkspaces";

const workspaceA = { workspaceId: "a", workspaceName: "workspace-a" };
const workspaceB = { workspaceId: "b", workspaceName: "workspace-b" };

describe("usePinnedWorkspaces", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("starts with no pinned workspaces", () => {
    const { result } = renderHook(() => usePinnedWorkspaces("org"));

    expect(result.current.pinnedWorkspaces).toEqual([]);
    expect(result.current.isPinned("a")).toBe(false);
  });

  it("pins a workspace and reports it as pinned", () => {
    const { result } = renderHook(() => usePinnedWorkspaces("org"));

    act(() => result.current.pinWorkspace(workspaceA));

    expect(result.current.pinnedWorkspaces).toEqual([workspaceA]);
    expect(result.current.isPinned("a")).toBe(true);
  });

  it("persists only workspaceId and workspaceName", () => {
    const { result } = renderHook(() => usePinnedWorkspaces("org"));

    act(() =>
      result.current.pinWorkspace({
        ...workspaceA,
        organizationId: "ignored",
      } as never),
    );

    expect(result.current.pinnedWorkspaces).toEqual([
      { workspaceId: "a", workspaceName: "workspace-a" },
    ]);
  });

  it("does not pin the same workspace twice", () => {
    const { result } = renderHook(() => usePinnedWorkspaces("org"));

    act(() => result.current.pinWorkspace(workspaceA));
    act(() => result.current.pinWorkspace(workspaceA));

    expect(result.current.pinnedWorkspaces).toEqual([workspaceA]);
  });

  it("unpins a workspace", () => {
    const { result } = renderHook(() => usePinnedWorkspaces("org"));

    act(() => result.current.pinWorkspace(workspaceA));
    act(() => result.current.pinWorkspace(workspaceB));
    act(() => result.current.unpinWorkspace("a"));

    expect(result.current.pinnedWorkspaces).toEqual([workspaceB]);
    expect(result.current.isPinned("a")).toBe(false);
  });

  it("isolates pinned workspaces per organization", () => {
    const { result: orgOne } = renderHook(() => usePinnedWorkspaces("org-1"));
    act(() => orgOne.current.pinWorkspace(workspaceA));

    const { result: orgTwo } = renderHook(() => usePinnedWorkspaces("org-2"));

    expect(orgTwo.current.pinnedWorkspaces).toEqual([]);
    expect(orgTwo.current.isPinned("a")).toBe(false);
  });
});
