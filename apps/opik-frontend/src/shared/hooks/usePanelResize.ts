import { RefObject, useCallback, useEffect, useRef, useState } from "react";

const MIN_LEFT_POSITION = 0.1;
const MAX_LEFT_POSITION = 0.8;
const DEFAULT_INITIAL_WIDTH = 0.75;

const clampLeft = (value: number) =>
  Math.max(MIN_LEFT_POSITION, Math.min(MAX_LEFT_POSITION, value));

const leftToWidth = (
  leftFraction: number,
  hostWidth: number,
  minWidth?: number,
) => {
  const width = hostWidth * (1 - clampLeft(leftFraction));
  return minWidth ? Math.max(width, Math.min(minWidth, hostWidth)) : width;
};

type UsePanelResizeParams = {
  panelId: string;
  hostWidth: number | null;
  elementRef: RefObject<HTMLElement | null>;
  enabled: boolean;
  initialWidth?: number;
  minWidth?: number;
};

const usePanelResize = ({
  panelId,
  hostWidth,
  elementRef,
  enabled,
  initialWidth = DEFAULT_INITIAL_WIDTH,
  minWidth,
}: UsePanelResizeParams) => {
  const storageKey = `${panelId}-side-panel-width`;

  const readStoredLeft = useCallback(() => {
    const stored = localStorage.getItem(storageKey);
    const parsed = parseFloat(stored ?? `${1 - initialWidth}`);
    return Number.isFinite(parsed) ? clampLeft(parsed) : 1 - initialWidth;
  }, [storageKey, initialWidth]);

  const [leftFraction, setLeftFraction] = useState(readStoredLeft);
  const leftRef = useRef(leftFraction);
  const draggingRef = useRef(false);
  const restoreTransition = useRef<string | null>(null);

  useEffect(() => {
    leftRef.current = leftFraction;
  }, [leftFraction]);

  const panelWidth =
    hostWidth === null
      ? null
      : Math.round(leftToWidth(leftFraction, hostWidth, minWidth));

  const startResize = useCallback(() => {
    if (!enabled) return;
    const el = elementRef.current;
    if (!el) return;
    draggingRef.current = true;
    restoreTransition.current = el.style.transition;
    el.style.transition = "none";
    document.body.style.userSelect = "none";
  }, [enabled, elementRef]);

  const releaseDrag = useCallback(() => {
    if (!draggingRef.current) return false;
    draggingRef.current = false;
    document.body.style.userSelect = "";
    const el = elementRef.current;
    if (el) el.style.transition = restoreTransition.current ?? "";
    return true;
  }, [elementRef]);

  useEffect(
    () => () => {
      releaseDrag();
    },
    [enabled, releaseDrag],
  );

  useEffect(() => {
    if (!enabled) return;

    const onMove = (event: MouseEvent) => {
      const el = elementRef.current;
      if (!draggingRef.current || hostWidth === null || !el) return;
      const hostLeft = el.offsetParent
        ? (el.offsetParent as HTMLElement).getBoundingClientRect().left
        : 0;
      const next = clampLeft((event.clientX - hostLeft) / hostWidth);
      leftRef.current = next;
      el.style.width = `${Math.round(
        leftToWidth(next, hostWidth, minWidth),
      )}px`;
    };

    const onUp = () => {
      if (!releaseDrag()) return;
      localStorage.setItem(storageKey, String(leftRef.current));
      setLeftFraction(leftRef.current);
    };

    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
    return () => {
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
    };
  }, [enabled, hostWidth, elementRef, storageKey, minWidth, releaseDrag]);

  return { panelWidth, startResize, isResizing: draggingRef };
};

export default usePanelResize;
