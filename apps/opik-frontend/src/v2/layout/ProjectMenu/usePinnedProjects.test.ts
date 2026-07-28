import { describe, it, expect, beforeEach } from "vitest";
import { act, renderHook } from "@testing-library/react";

import usePinnedProjects from "./usePinnedProjects";

const projectA = { id: "a", name: "Project A" };
const projectB = { id: "b", name: "Project B" };

describe("usePinnedProjects", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("starts with no pinned projects", () => {
    const { result } = renderHook(() => usePinnedProjects("ws"));

    expect(result.current.pinnedProjects).toEqual([]);
    expect(result.current.isPinned("a")).toBe(false);
  });

  it("pins a project and reports it as pinned", () => {
    const { result } = renderHook(() => usePinnedProjects("ws"));

    act(() => result.current.pinProject(projectA));

    expect(result.current.pinnedProjects).toEqual([projectA]);
    expect(result.current.isPinned("a")).toBe(true);
  });

  it("persists only id and name", () => {
    const { result } = renderHook(() => usePinnedProjects("ws"));

    act(() =>
      result.current.pinProject({
        ...projectA,
        description: "ignored",
      } as never),
    );

    expect(result.current.pinnedProjects).toEqual([
      { id: "a", name: "Project A" },
    ]);
  });

  it("does not pin the same project twice", () => {
    const { result } = renderHook(() => usePinnedProjects("ws"));

    act(() => result.current.pinProject(projectA));
    act(() => result.current.pinProject(projectA));

    expect(result.current.pinnedProjects).toEqual([projectA]);
  });

  it("unpins a project", () => {
    const { result } = renderHook(() => usePinnedProjects("ws"));

    act(() => result.current.pinProject(projectA));
    act(() => result.current.pinProject(projectB));
    act(() => result.current.unpinProject("a"));

    expect(result.current.pinnedProjects).toEqual([projectB]);
    expect(result.current.isPinned("a")).toBe(false);
  });

  it("isolates pinned projects per workspace", () => {
    const { result: wsOne } = renderHook(() => usePinnedProjects("ws-1"));
    act(() => wsOne.current.pinProject(projectA));

    const { result: wsTwo } = renderHook(() => usePinnedProjects("ws-2"));

    expect(wsTwo.current.pinnedProjects).toEqual([]);
    expect(wsTwo.current.isPinned("a")).toBe(false);
  });
});
