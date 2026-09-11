import { describe, it, expect, beforeEach, vi, afterEach } from "vitest";
import { act, renderHook } from "@testing-library/react";

import useRecentWorkspaces from "./useRecentWorkspaces";

describe("useRecentWorkspaces", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("starts with no visits", () => {
    const { result } = renderHook(() => useRecentWorkspaces());

    expect(result.current.visits).toEqual({});
    expect(result.current.getVisitedAt("workspace-a")).toBe(0);
  });

  it("records a visit timestamp", () => {
    vi.setSystemTime(new Date(1000));
    const { result } = renderHook(() => useRecentWorkspaces());

    act(() => result.current.recordVisit("workspace-a"));

    expect(result.current.getVisitedAt("workspace-a")).toBe(1000);
  });

  it("keeps the latest timestamp on repeat visits", () => {
    const { result } = renderHook(() => useRecentWorkspaces());

    vi.setSystemTime(new Date(1000));
    act(() => result.current.recordVisit("workspace-a"));
    vi.setSystemTime(new Date(5000));
    act(() => result.current.recordVisit("workspace-a"));

    expect(result.current.getVisitedAt("workspace-a")).toBe(5000);
  });

  it("ignores an empty workspace name", () => {
    const { result } = renderHook(() => useRecentWorkspaces());

    act(() => result.current.recordVisit(""));

    expect(result.current.visits).toEqual({});
  });

  it("tracks multiple workspaces independently", () => {
    const { result } = renderHook(() => useRecentWorkspaces());

    vi.setSystemTime(new Date(1000));
    act(() => result.current.recordVisit("workspace-a"));
    vi.setSystemTime(new Date(2000));
    act(() => result.current.recordVisit("workspace-b"));

    expect(result.current.getVisitedAt("workspace-a")).toBe(1000);
    expect(result.current.getVisitedAt("workspace-b")).toBe(2000);
  });
});
