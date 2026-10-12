import * as React from "react";
import { ChevronDown, ChevronUp } from "lucide-react";

import { cn } from "@/lib/utils";

const CLICK_SCROLL_PX = 120;
const HOLD_DELAY_MS = 300;
const HOLD_SCROLL_PX_PER_MS = 0.5;

type ScrollDirection = "up" | "down";

const SCROLL_SIGN: Record<ScrollDirection, number> = { up: -1, down: 1 };

interface ScrollArrowProps {
  direction: ScrollDirection;
  onPress: (direction: ScrollDirection) => void;
  onRelease: () => void;
}

const ScrollArrow: React.FC<ScrollArrowProps> = ({
  direction,
  onPress,
  onRelease,
}) => {
  const Icon = direction === "up" ? ChevronUp : ChevronDown;

  return (
    <div
      aria-hidden
      data-testid={`scroll-${direction}-arrow`}
      className={cn(
        "absolute inset-x-0 flex h-6 cursor-default items-center justify-center bg-background",
        direction === "up" ? "top-0" : "bottom-0",
      )}
      onPointerDown={() => onPress(direction)}
      onPointerUp={onRelease}
      onPointerLeave={onRelease}
      onPointerCancel={onRelease}
    >
      <Icon className="size-3.5" />
    </div>
  );
};

interface ArrowScrollAreaProps {
  className?: string;
  children?: React.ReactNode;
}

const ArrowScrollArea: React.FC<ArrowScrollAreaProps> = ({
  className,
  children,
}) => {
  const viewportRef = React.useRef<HTMLDivElement>(null);
  const contentRef = React.useRef<HTMLDivElement>(null);
  const [canScrollUp, setCanScrollUp] = React.useState(false);
  const [canScrollDown, setCanScrollDown] = React.useState(false);
  const [heldDirection, setHeldDirection] =
    React.useState<ScrollDirection | null>(null);

  const updateArrows = React.useCallback(() => {
    const viewport = viewportRef.current;
    if (!viewport) return;

    const maxScrollTop = viewport.scrollHeight - viewport.clientHeight;
    setCanScrollUp(viewport.scrollTop > 0);
    setCanScrollDown(Math.ceil(viewport.scrollTop) < maxScrollTop);
  }, []);

  React.useLayoutEffect(() => {
    const viewport = viewportRef.current;
    const content = contentRef.current;
    if (!viewport || !content) return;

    updateArrows();
    const observer = new ResizeObserver(updateArrows);
    observer.observe(viewport);
    observer.observe(content);
    return () => observer.disconnect();
  }, [updateArrows]);

  React.useEffect(() => {
    const viewport = viewportRef.current;
    if (!heldDirection || !viewport) return;

    let frame = 0;
    let previousTime: number | undefined;
    const scrollWhileHeld = (time: number) => {
      if (previousTime !== undefined) {
        viewport.scrollTop +=
          SCROLL_SIGN[heldDirection] *
          HOLD_SCROLL_PX_PER_MS *
          (time - previousTime);
      }
      previousTime = time;
      frame = requestAnimationFrame(scrollWhileHeld);
    };
    const holdTimer = window.setTimeout(() => {
      frame = requestAnimationFrame(scrollWhileHeld);
    }, HOLD_DELAY_MS);

    return () => {
      window.clearTimeout(holdTimer);
      cancelAnimationFrame(frame);
    };
  }, [heldDirection]);

  const press = (direction: ScrollDirection) => {
    viewportRef.current?.scrollBy({
      top: SCROLL_SIGN[direction] * CLICK_SCROLL_PX,
      behavior: "smooth",
    });
    setHeldDirection(direction);
  };

  const release = () => setHeldDirection(null);

  // A held arrow stays until release even at the end of the list: Radix Select
  // picks whatever item a pointer is released over, so a vanished arrow would
  // turn the release into a selection.
  const renderArrow = (direction: ScrollDirection, canScroll: boolean) =>
    (canScroll || heldDirection === direction) && (
      <ScrollArrow direction={direction} onPress={press} onRelease={release} />
    );

  return (
    <div className={cn("relative flex min-h-0 flex-col", className)}>
      <div
        ref={viewportRef}
        className="min-h-0 flex-1 scroll-py-6 overflow-y-auto"
        onScroll={updateArrows}
      >
        <div ref={contentRef}>{children}</div>
      </div>
      {renderArrow("up", canScrollUp)}
      {renderArrow("down", canScrollDown)}
    </div>
  );
};

export { ArrowScrollArea };
