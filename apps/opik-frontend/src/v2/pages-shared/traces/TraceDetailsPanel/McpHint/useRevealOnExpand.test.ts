import { describe, it, expect } from "vitest";
import { renderHook } from "@testing-library/react";

import useRevealOnExpand from "./useRevealOnExpand";

const reveal = (active: boolean, subject = "trace-1") =>
  renderHook(
    ({ active, subject }: { active: boolean; subject: string }) =>
      useRevealOnExpand({ active, subject }),
    { initialProps: { active, subject } },
  );

describe("useRevealOnExpand", () => {
  it("shows nothing while closed", () => {
    const { result } = reveal(false);

    expect(result.current).toBe(false);
  });

  it("shows as soon as it is opened", () => {
    const { result } = reveal(true);

    expect(result.current).toBe(true);
  });

  it("hides again when it is closed", () => {
    const { result, rerender } = reveal(true);

    rerender({ active: false, subject: "trace-1" });

    expect(result.current).toBe(false);
  });

  it("comes back on reopen", () => {
    const { result, rerender } = reveal(true);

    rerender({ active: false, subject: "trace-1" });
    rerender({ active: true, subject: "trace-1" });

    expect(result.current).toBe(true);
  });

  // The shared collapsible keeps its open state across a change of span, so
  // being open here is not evidence the user opened *this* failure. Showing
  // anyway would also put an impression on the funnel with no expansion in
  // front of it.
  it("does not show on a subject it was merely left open for", () => {
    const { result, rerender } = reveal(true);

    rerender({ active: true, subject: "span-2" });

    expect(result.current).toBe(false);
  });

  it("shows on the new subject once the user opens it there", () => {
    const { result, rerender } = reveal(true);

    rerender({ active: true, subject: "span-2" });
    rerender({ active: false, subject: "span-2" });
    rerender({ active: true, subject: "span-2" });

    expect(result.current).toBe(true);
  });

  it("stays hidden on a new subject that is not open", () => {
    const { result, rerender } = reveal(true);

    rerender({ active: false, subject: "span-2" });

    expect(result.current).toBe(false);
  });
});
