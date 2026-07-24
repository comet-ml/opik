import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, act } from "@testing-library/react";

import { useTrialSidebarState } from "./useTrialSidebarState";

const mockSetQuery = vi.fn();
let mockQuery: Record<string, unknown> = {};

vi.mock("use-query-params", async (importOriginal) => {
  const actual = await importOriginal<typeof import("use-query-params")>();
  return {
    ...actual,
    useQueryParams: () => [mockQuery, mockSetQuery],
  };
});

describe("useTrialSidebarState", () => {
  beforeEach(() => {
    mockSetQuery.mockClear();
    mockQuery = {};
  });

  it("is closed when the trials param is absent or empty", () => {
    const { result } = renderHook(() => useTrialSidebarState());
    expect(result.current.open).toBe(false);
    expect(result.current.experimentIds).toEqual([]);

    mockQuery = { trials: [] };
    const { result: result2 } = renderHook(() => useTrialSidebarState());
    expect(result2.current.open).toBe(false);
  });

  it("opens on a non-empty trials param and keeps only string ids", () => {
    mockQuery = { trials: ["exp-1", 42, "exp-2"], trialNumber: 3 };
    const { result } = renderHook(() => useTrialSidebarState());
    expect(result.current.open).toBe(true);
    expect(result.current.experimentIds).toEqual(["exp-1", "exp-2"]);
    expect(result.current.trialNumber).toBe(3);
  });

  it("defaults the tab to results and honours trialTab=prompt", () => {
    mockQuery = { trials: ["exp-1"], trialTab: "bogus" };
    expect(renderHook(() => useTrialSidebarState()).result.current.tab).toBe(
      "results",
    );

    mockQuery = { trials: ["exp-1"], trialTab: "prompt" };
    expect(renderHook(() => useTrialSidebarState()).result.current.tab).toBe(
      "prompt",
    );
  });

  it("maps trialTab=diff to the Prompt tab in diff view", () => {
    mockQuery = { trials: ["exp-1"], trialTab: "diff" };
    const { result } = renderHook(() => useTrialSidebarState());
    expect(result.current.tab).toBe("prompt");
    expect(result.current.promptView).toBe("diff");

    mockQuery = { trials: ["exp-1"], trialTab: "prompt" };
    expect(
      renderHook(() => useTrialSidebarState()).result.current.promptView,
    ).toBe("config");
  });

  it("openTrial batches the params and resets per-trial state", () => {
    const { result } = renderHook(() => useTrialSidebarState());
    act(() => {
      result.current.openTrial(
        { experimentIds: ["exp-9"], trialNumber: 9 },
        "diff",
      );
    });
    expect(mockSetQuery).toHaveBeenCalledWith(
      {
        trials: ["exp-9"],
        trialNumber: 9,
        trialTab: "diff",
        trace: undefined,
        span: undefined,
        itemsPage: undefined,
        filters: undefined,
      },
      "replaceIn",
    );
  });

  it("close clears the sidebar params and the embedded table's params", () => {
    const { result } = renderHook(() => useTrialSidebarState());
    act(() => {
      result.current.close();
    });
    expect(mockSetQuery).toHaveBeenCalledWith(
      {
        trials: undefined,
        trialNumber: undefined,
        trialTab: undefined,
        trace: undefined,
        span: undefined,
        itemsPage: undefined,
        filters: undefined,
        size: undefined,
        height: undefined,
      },
      "replaceIn",
    );
  });

  it("setTab writes prompt and clears the param for results", () => {
    const { result } = renderHook(() => useTrialSidebarState());
    act(() => result.current.setTab("prompt"));
    expect(mockSetQuery).toHaveBeenLastCalledWith(
      { trialTab: "prompt" },
      "replaceIn",
    );
    act(() => result.current.setTab("results"));
    expect(mockSetQuery).toHaveBeenLastCalledWith(
      { trialTab: undefined },
      "replaceIn",
    );
  });
});
