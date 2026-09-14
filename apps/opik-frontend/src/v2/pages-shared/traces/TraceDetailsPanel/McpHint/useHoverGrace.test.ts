import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { renderHook, act } from "@testing-library/react";

import useHoverGrace from "./useHoverGrace";
import { MCP_HINT_HOVER_GRACE_MS } from "./constants";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

const advance = (ms: number) =>
  act(() => {
    vi.advanceTimersByTime(ms);
  });

describe("useHoverGrace", () => {
  it("starts closed", () => {
    const { result } = renderHook(() => useHoverGrace());

    expect(result.current.isOpen).toBe(false);
  });

  it("opens immediately", () => {
    const { result } = renderHook(() => useHoverGrace());

    act(() => result.current.open());

    expect(result.current.isOpen).toBe(true);
  });

  // The grace period exists because the pointer has to cross a gap to reach the
  // popover, and a cursor that clips a corner on the way must not dismiss it.
  it("stays open while the grace period is still running", () => {
    const { result } = renderHook(() => useHoverGrace());

    act(() => result.current.open());
    act(() => result.current.closeAfterGrace());
    advance(MCP_HINT_HOVER_GRACE_MS - 1);

    expect(result.current.isOpen).toBe(true);
  });

  it("closes once the grace period elapses", () => {
    const { result } = renderHook(() => useHoverGrace());

    act(() => result.current.open());
    act(() => result.current.closeAfterGrace());
    advance(MCP_HINT_HOVER_GRACE_MS);

    expect(result.current.isOpen).toBe(false);
  });

  it("cancels the pending close when the pointer comes back", () => {
    const { result } = renderHook(() => useHoverGrace());

    act(() => result.current.open());
    act(() => result.current.closeAfterGrace());
    advance(MCP_HINT_HOVER_GRACE_MS - 1);
    act(() => result.current.open());
    advance(MCP_HINT_HOVER_GRACE_MS * 10);

    expect(result.current.isOpen).toBe(true);
  });

  // Crossing the gap fires leave on the button and enter on the popover, so the
  // two arrive back to back. Only the latest one may decide the outcome.
  it("lets the newest intent win when leaves and re-entries interleave", () => {
    const { result } = renderHook(() => useHoverGrace());

    act(() => result.current.open());
    act(() => {
      result.current.closeAfterGrace();
      result.current.open();
      result.current.closeAfterGrace();
    });
    advance(MCP_HINT_HOVER_GRACE_MS - 1);
    expect(result.current.isOpen).toBe(true);

    advance(1);
    expect(result.current.isOpen).toBe(false);
  });

  it("closes immediately when asked to, without waiting out the grace", () => {
    const { result } = renderHook(() => useHoverGrace());

    act(() => result.current.open());
    act(() => result.current.closeNow());

    expect(result.current.isOpen).toBe(false);
  });

  it("drops a pending close when asked to close immediately", () => {
    const { result } = renderHook(() => useHoverGrace());

    act(() => result.current.open());
    act(() => result.current.closeAfterGrace());
    act(() => result.current.closeNow());
    act(() => result.current.open());
    advance(MCP_HINT_HOVER_GRACE_MS * 10);

    expect(result.current.isOpen).toBe(true);
  });

  it("abandons a pending close on unmount", () => {
    const { result, unmount } = renderHook(() => useHoverGrace());

    act(() => result.current.open());
    act(() => result.current.closeAfterGrace());
    unmount();

    expect(() => advance(MCP_HINT_HOVER_GRACE_MS * 10)).not.toThrow();
  });
});
