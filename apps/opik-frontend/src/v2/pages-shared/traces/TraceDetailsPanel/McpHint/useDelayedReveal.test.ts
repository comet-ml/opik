import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { renderHook, act } from "@testing-library/react";

import useDelayedReveal from "./useDelayedReveal";
import { MCP_HINT_REVEAL_DELAY_MS } from "./constants";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

const reveal = (active: boolean, subject = "trace-1") =>
  renderHook(
    ({ active, subject }: { active: boolean; subject: string }) =>
      useDelayedReveal({ active, subject }),
    { initialProps: { active, subject } },
  );

const advance = (ms: number) =>
  act(() => {
    vi.advanceTimersByTime(ms);
  });

describe("useDelayedReveal", () => {
  it("reveals nothing while the delay is still running", () => {
    const { result } = reveal(true);

    advance(MCP_HINT_REVEAL_DELAY_MS - 1);

    expect(result.current).toBe(false);
  });

  it("reveals once the delay elapses", () => {
    const { result } = reveal(true);

    advance(MCP_HINT_REVEAL_DELAY_MS);

    expect(result.current).toBe(true);
  });

  it("stays hidden while inactive, however long we wait", () => {
    const { result } = reveal(false);

    advance(MCP_HINT_REVEAL_DELAY_MS * 10);

    expect(result.current).toBe(false);
  });

  // The one-second delay is what makes the control read as a response to the
  // user's action. Going inactive inside that window withdraws the action, so
  // the pending reveal must be dropped rather than merely postponed.
  it("cancels a pending reveal when it goes inactive in time", () => {
    const { result, rerender } = reveal(true);

    advance(MCP_HINT_REVEAL_DELAY_MS - 1);
    rerender({ active: false, subject: "trace-1" });
    advance(MCP_HINT_REVEAL_DELAY_MS * 10);

    expect(result.current).toBe(false);
  });

  // The source ticket contradicts itself here: its interaction notes say going
  // inactive removes the control, its acceptance criteria say an already-shown
  // control stays. The acceptance criteria win — expanding the error is the
  // intent signal, and collapsing it again is usually "I have read this, now
  // let me act", which is exactly when the control is wanted.
  it("does not retract a reveal that already happened", () => {
    const { result, rerender } = reveal(true);

    advance(MCP_HINT_REVEAL_DELAY_MS);
    rerender({ active: false, subject: "trace-1" });
    advance(MCP_HINT_REVEAL_DELAY_MS * 10);

    expect(result.current).toBe(true);
  });

  it("re-arms after going inactive and active again", () => {
    const { result, rerender } = reveal(true);

    advance(MCP_HINT_REVEAL_DELAY_MS - 1);
    rerender({ active: false, subject: "trace-1" });
    rerender({ active: true, subject: "trace-1" });
    advance(MCP_HINT_REVEAL_DELAY_MS - 1);
    expect(result.current).toBe(false);

    advance(1);
    expect(result.current).toBe(true);
  });

  // The control refers to the failure in front of the user. A different span or
  // trace is a different failure, so a reveal earned on the previous one does
  // not carry over.
  it("retracts when the subject changes", () => {
    const { result, rerender } = reveal(true);

    advance(MCP_HINT_REVEAL_DELAY_MS);
    expect(result.current).toBe(true);

    rerender({ active: true, subject: "span-2" });

    expect(result.current).toBe(false);
  });

  // Staying active across a change of subject is not a fresh ask — the user
  // expanded the *previous* failure and simply never closed it. Re-revealing
  // here would also put an impression on the funnel with no expansion in front
  // of it.
  it("does not re-earn the reveal on a new subject it was merely left active for", () => {
    const { result, rerender } = reveal(true);

    advance(MCP_HINT_REVEAL_DELAY_MS);
    rerender({ active: true, subject: "span-2" });

    advance(MCP_HINT_REVEAL_DELAY_MS * 10);

    expect(result.current).toBe(false);
  });

  it("reveals on the new subject once the user asks again there", () => {
    const { result, rerender } = reveal(true);

    advance(MCP_HINT_REVEAL_DELAY_MS);
    rerender({ active: true, subject: "span-2" });
    rerender({ active: false, subject: "span-2" });
    rerender({ active: true, subject: "span-2" });

    advance(MCP_HINT_REVEAL_DELAY_MS);

    expect(result.current).toBe(true);
  });

  it("does not reveal on a new subject that is no longer active", () => {
    const { result, rerender } = reveal(true);

    advance(MCP_HINT_REVEAL_DELAY_MS);
    rerender({ active: false, subject: "span-2" });
    advance(MCP_HINT_REVEAL_DELAY_MS * 10);

    expect(result.current).toBe(false);
  });
});
