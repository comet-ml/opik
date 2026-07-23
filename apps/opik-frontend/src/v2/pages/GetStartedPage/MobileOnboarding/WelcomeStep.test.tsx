import React from "react";
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, act } from "@testing-library/react";
import WelcomeStep from "./WelcomeStep";

vi.mock("./illustrations", () => ({
  WelcomeIllustration: () => <div data-testid="illustration" />,
}));

const TYPING_START_DELAY = 500;
const TYPE_SPEED = 80;

let documentHidden = false;

const setDocumentHidden = (hidden: boolean) => {
  documentHidden = hidden;
  act(() => {
    document.dispatchEvent(new Event("visibilitychange"));
  });
};

const typedText = (container: HTMLElement) =>
  container.querySelector('[translate="no"]')?.textContent ?? "";

describe("WelcomeStep TypingText timer lifecycle", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    documentHidden = false;
    Object.defineProperty(document, "hidden", {
      configurable: true,
      get: () => documentHidden,
    });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("types one character per tick after the start delay", () => {
    const { container } = render(<WelcomeStep />);

    act(() => {
      vi.advanceTimersByTime(TYPING_START_DELAY);
    });
    expect(typedText(container)).toBe("W");

    act(() => {
      vi.advanceTimersByTime(TYPE_SPEED * 2);
    });
    expect(typedText(container)).toBe("Wha");
  });

  it("does not run the typing loop while the step is inactive", () => {
    const { container } = render(<WelcomeStep active={false} />);

    act(() => {
      vi.advanceTimersByTime(TYPING_START_DELAY + TYPE_SPEED * 10);
    });
    expect(typedText(container)).toBe("");
  });

  it("stops typing when the step becomes inactive", () => {
    const { container, rerender } = render(<WelcomeStep active />);

    act(() => {
      vi.advanceTimersByTime(TYPING_START_DELAY + TYPE_SPEED * 2);
    });
    expect(typedText(container)).toBe("Wha");

    rerender(<WelcomeStep active={false} />);
    act(() => {
      vi.advanceTimersByTime(TYPE_SPEED * 10);
    });
    // Paused — no further progress while offscreen.
    expect(typedText(container)).toBe("Wha");
  });

  it("keeps a single tick chain when visibility toggles during the start delay", () => {
    const { container } = render(<WelcomeStep />);

    // Hide and re-show before the initial start timer (500ms) has fired. The
    // visibility handler starts the loop immediately; the pending start timer
    // must be cancelled or a second concurrent chain doubles the speed.
    act(() => {
      vi.advanceTimersByTime(300);
    });
    setDocumentHidden(true);
    setDocumentHidden(false);

    expect(typedText(container)).toBe("W");

    // 400ms spans the moment the stale start timer would have fired (t=500ms).
    // A single healthy chain types exactly 5 more characters (80ms each).
    act(() => {
      vi.advanceTimersByTime(TYPE_SPEED * 5);
    });
    expect(typedText(container)).toBe("What i");
  });

  it("pauses while the document is hidden and resumes cleanly", () => {
    const { container } = render(<WelcomeStep />);

    act(() => {
      vi.advanceTimersByTime(TYPING_START_DELAY + TYPE_SPEED * 2);
    });
    expect(typedText(container)).toBe("Wha");

    setDocumentHidden(true);
    act(() => {
      vi.advanceTimersByTime(TYPE_SPEED * 10);
    });
    // No progress while hidden.
    expect(typedText(container)).toBe("Wha");

    setDocumentHidden(false);
    // Resume types the next character immediately, then one per tick.
    expect(typedText(container)).toBe("What");
    act(() => {
      vi.advanceTimersByTime(TYPE_SPEED);
    });
    expect(typedText(container)).toBe("What ");
  });
});
