import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen } from "@testing-library/react";

import { ArrowScrollArea } from "./arrow-scroll-area";

const VIEWPORT_HEIGHT = 200;
const CONTENT_HEIGHT = 1000;
const MAX_SCROLL_TOP = CONTENT_HEIGHT - VIEWPORT_HEIGHT;

// happy-dom has no layout engine, so the heights the component reads are stubbed.
const stubHeights = (contentHeight: number) => {
  vi.spyOn(Element.prototype, "scrollHeight", "get").mockReturnValue(
    contentHeight,
  );
  vi.spyOn(HTMLElement.prototype, "clientHeight", "get").mockReturnValue(
    VIEWPORT_HEIGHT,
  );
};

const renderArea = () => {
  render(
    <ArrowScrollArea>
      <div>Rows</div>
    </ArrowScrollArea>,
  );
  return screen.getByTestId("arrow-scroll-viewport");
};

const scrollViewportTo = (viewport: HTMLElement, scrollTop: number) => {
  viewport.scrollTop = scrollTop;
  fireEvent.scroll(viewport);
};

// happy-dom's ResizeObserver never calls back, so this one lets a test fire it.
const stubResizeObserver = () => {
  const callbacks: ResizeObserverCallback[] = [];
  vi.stubGlobal(
    "ResizeObserver",
    class {
      constructor(callback: ResizeObserverCallback) {
        callbacks.push(callback);
      }
      observe() {}
      disconnect() {}
    },
  );
  return () =>
    callbacks.forEach((callback) => callback([], {} as ResizeObserver));
};

const upArrow = () => screen.queryByTestId("scroll-up-arrow");
const downArrow = () => screen.queryByTestId("scroll-down-arrow");

describe("ArrowScrollArea", () => {
  let scrollBy: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    // happy-dom runs a smooth scrollBy on a real timer that fake timers can't
    // advance, so it is replaced by a spy and the hold tests see only the hold.
    scrollBy = vi.fn();
    vi.spyOn(Element.prototype, "scrollBy").mockImplementation(scrollBy);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  describe("arrows", () => {
    it("shows no arrows when the content fits", () => {
      stubHeights(VIEWPORT_HEIGHT);
      renderArea();

      expect(upArrow()).not.toBeInTheDocument();
      expect(downArrow()).not.toBeInTheDocument();
    });

    it("shows only the down arrow at the top", () => {
      stubHeights(CONTENT_HEIGHT);
      renderArea();

      expect(upArrow()).not.toBeInTheDocument();
      expect(downArrow()).toBeInTheDocument();
    });

    it("shows both arrows in the middle", () => {
      stubHeights(CONTENT_HEIGHT);
      const viewport = renderArea();

      scrollViewportTo(viewport, MAX_SCROLL_TOP / 2);

      expect(upArrow()).toBeInTheDocument();
      expect(downArrow()).toBeInTheDocument();
    });

    it("shows only the up arrow at the bottom", () => {
      stubHeights(CONTENT_HEIGHT);
      const viewport = renderArea();

      scrollViewportTo(viewport, MAX_SCROLL_TOP);

      expect(upArrow()).toBeInTheDocument();
      expect(downArrow()).not.toBeInTheDocument();
    });

    it("treats a fractional scroll position less than a pixel from the end as the bottom", () => {
      stubHeights(CONTENT_HEIGHT);
      const viewport = renderArea();

      scrollViewportTo(viewport, MAX_SCROLL_TOP - 0.5);

      expect(upArrow()).toBeInTheDocument();
      expect(downArrow()).not.toBeInTheDocument();
    });

    it("keeps the down arrow a full pixel from the end", () => {
      stubHeights(CONTENT_HEIGHT);
      const viewport = renderArea();

      scrollViewportTo(viewport, MAX_SCROLL_TOP - 1);

      expect(downArrow()).toBeInTheDocument();
    });
  });

  describe("click", () => {
    it("scrolls down smoothly", () => {
      stubHeights(CONTENT_HEIGHT);
      renderArea();

      fireEvent.pointerDown(downArrow()!);
      fireEvent.pointerUp(downArrow()!);

      expect(scrollBy).toHaveBeenCalledOnce();
      expect(scrollBy).toHaveBeenCalledWith({
        top: expect.toSatisfy((top: number) => top > 0),
        behavior: "smooth",
      });
    });

    it("scrolls up smoothly", () => {
      stubHeights(CONTENT_HEIGHT);
      const viewport = renderArea();
      scrollViewportTo(viewport, MAX_SCROLL_TOP);

      fireEvent.pointerDown(upArrow()!);
      fireEvent.pointerUp(upArrow()!);

      expect(scrollBy).toHaveBeenCalledOnce();
      expect(scrollBy).toHaveBeenCalledWith({
        top: expect.toSatisfy((top: number) => top < 0),
        behavior: "smooth",
      });
    });

    it("does not keep scrolling after a quick click", () => {
      vi.useFakeTimers();
      stubHeights(CONTENT_HEIGHT);
      const viewport = renderArea();

      fireEvent.pointerDown(downArrow()!);
      fireEvent.pointerUp(downArrow()!);
      act(() => {
        vi.advanceTimersByTime(1000);
      });

      expect(viewport.scrollTop).toBe(0);
    });
  });

  describe("press and hold", () => {
    it("keeps scrolling while held and stops on release", () => {
      vi.useFakeTimers();
      stubHeights(CONTENT_HEIGHT);
      const viewport = renderArea();

      fireEvent.pointerDown(downArrow()!);
      act(() => {
        vi.advanceTimersByTime(500);
      });
      const scrollTopAfterHalfSecond = viewport.scrollTop;
      act(() => {
        vi.advanceTimersByTime(500);
      });
      const scrollTopAfterOneSecond = viewport.scrollTop;
      fireEvent.pointerUp(downArrow()!);
      act(() => {
        vi.advanceTimersByTime(1000);
      });

      expect(scrollTopAfterHalfSecond).toBeGreaterThan(0);
      expect(scrollTopAfterOneSecond).toBeGreaterThan(scrollTopAfterHalfSecond);
      expect(viewport.scrollTop).toBe(scrollTopAfterOneSecond);
    });

    it("scrolls up while the up arrow is held", () => {
      vi.useFakeTimers();
      stubHeights(CONTENT_HEIGHT);
      const viewport = renderArea();
      scrollViewportTo(viewport, MAX_SCROLL_TOP);

      fireEvent.pointerDown(upArrow()!);
      act(() => {
        vi.advanceTimersByTime(1000);
      });

      expect(viewport.scrollTop).toBeLessThan(MAX_SCROLL_TOP);
    });

    it("stops when the pointer leaves the arrow", () => {
      vi.useFakeTimers();
      stubHeights(CONTENT_HEIGHT);
      const viewport = renderArea();

      fireEvent.pointerDown(downArrow()!);
      act(() => {
        vi.advanceTimersByTime(1000);
      });
      fireEvent.pointerLeave(downArrow()!);
      const scrollTopOnLeave = viewport.scrollTop;
      act(() => {
        vi.advanceTimersByTime(1000);
      });

      expect(viewport.scrollTop).toBe(scrollTopOnLeave);
    });

    it("stops scrolling at the end of the list while still held", () => {
      vi.useFakeTimers();
      stubHeights(CONTENT_HEIGHT);
      const viewport = renderArea();

      fireEvent.pointerDown(downArrow()!);
      act(() => {
        vi.advanceTimersByTime(5000);
      });
      const scrollTopAtEnd = viewport.scrollTop;
      act(() => {
        vi.advanceTimersByTime(1000);
      });

      expect(scrollTopAtEnd).toBeGreaterThanOrEqual(MAX_SCROLL_TOP);
      expect(viewport.scrollTop).toBe(scrollTopAtEnd);
    });

    it("stops scrolling at the top of the list while the up arrow is still held", () => {
      vi.useFakeTimers();
      stubHeights(CONTENT_HEIGHT);
      const viewport = renderArea();
      scrollViewportTo(viewport, MAX_SCROLL_TOP / 2);

      fireEvent.pointerDown(upArrow()!);
      act(() => {
        vi.advanceTimersByTime(5000);
      });
      const scrollTopAtTop = viewport.scrollTop;
      act(() => {
        vi.advanceTimersByTime(1000);
      });

      expect(scrollTopAtTop).toBeLessThanOrEqual(0);
      expect(viewport.scrollTop).toBe(scrollTopAtTop);
    });

    it("does not move a held arrow from a fractional bottom", () => {
      vi.useFakeTimers();
      stubHeights(CONTENT_HEIGHT);
      const viewport = renderArea();
      scrollViewportTo(viewport, MAX_SCROLL_TOP - 2);

      fireEvent.pointerDown(downArrow()!);
      scrollViewportTo(viewport, MAX_SCROLL_TOP - 0.5);
      act(() => {
        vi.advanceTimersByTime(1000);
      });

      expect(viewport.scrollTop).toBe(MAX_SCROLL_TOP - 0.5);
    });

    it("resumes when the list grows under an arrow held at the end", () => {
      vi.useFakeTimers();
      const fireResize = stubResizeObserver();
      stubHeights(CONTENT_HEIGHT);
      const viewport = renderArea();

      fireEvent.pointerDown(downArrow()!);
      act(() => {
        vi.advanceTimersByTime(5000);
      });
      // A browser fires this scroll event as the hold reaches the end; happy-dom does not.
      fireEvent.scroll(viewport);
      const scrollTopAtEnd = viewport.scrollTop;
      stubHeights(2 * CONTENT_HEIGHT);
      act(() => fireResize());
      act(() => {
        vi.advanceTimersByTime(1000);
      });

      expect(viewport.scrollTop).toBeGreaterThan(scrollTopAtEnd);
    });

    it("keeps a held arrow at the end of the list until release", () => {
      stubHeights(CONTENT_HEIGHT);
      const viewport = renderArea();

      fireEvent.pointerDown(downArrow()!);
      scrollViewportTo(viewport, MAX_SCROLL_TOP);
      expect(downArrow()).toBeInTheDocument();

      fireEvent.pointerUp(downArrow()!);
      expect(downArrow()).not.toBeInTheDocument();
    });
  });
});
