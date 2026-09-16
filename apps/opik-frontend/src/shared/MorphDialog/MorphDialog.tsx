import React, {
  ReactNode,
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
} from "react";

import * as DialogPrimitive from "@radix-ui/react-dialog";
import { X } from "lucide-react";
import {
  Dialog,
  DialogClose,
  DialogCloseButton,
  DialogFooter,
  DialogHeader,
  DialogOverlay,
  DialogPortal,
  DialogTitle,
} from "@/ui/dialog";
import { Button } from "@/ui/button";
import { usePortalContainer } from "@/lib/portal-container";
import usePanelResize from "@/shared/hooks/usePanelResize";
import { cn } from "@/lib/utils";

export type MorphMode = "modal" | "panel";

const MORPH_MS = 440;
const RADIUS_MS = 340;
const MORPH_EASING = "cubic-bezier(.22,.61,.36,1)";
const FADE_MS = 130;
const MODAL_MARGIN = 32;

type MorphDialogProps = {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  mode: MorphMode;
  panelId: string;
  title: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
  modalWidth?: number;
  panelInitialWidth?: number;
  panelMinWidth?: number;
  className?: string;
};

type Frame = {
  mode: MorphMode;
  title: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
};

const usePrefersReducedMotion = () => {
  const [reduced, setReduced] = useState(
    () =>
      window.matchMedia?.("(prefers-reduced-motion: reduce)")?.matches ?? false,
  );

  useEffect(() => {
    const mq = window.matchMedia?.("(prefers-reduced-motion: reduce)");
    if (!mq) return;
    const onChange = () => setReduced(mq.matches);
    mq.addEventListener("change", onChange);
    return () => mq.removeEventListener("change", onChange);
  }, []);

  return reduced;
};

const MorphDialog: React.FunctionComponent<MorphDialogProps> = ({
  open,
  onOpenChange,
  mode,
  panelId,
  title,
  children,
  footer,
  modalWidth = 640,
  panelInitialWidth = 0.56,
  panelMinWidth,
  className,
}) => {
  const host = usePortalContainer();
  const reducedMotion = usePrefersReducedMotion();
  const contentRef = useRef<HTMLDivElement | null>(null);
  const bodyRef = useRef<HTMLDivElement | null>(null);
  const animationRef = useRef<Animation | null>(null);
  const fadeRef = useRef<Animation | null>(null);
  const swapTimer = useRef<number>();
  const lastFrame = useRef<Frame>({ mode, title, children, footer });
  const [frozenFrame, setFrozenFrame] = useState<Frame | null>(null);
  const prevMode = useRef(mode);

  const [hostWidth, setHostWidth] = useState<number | null>(null);

  useEffect(() => {
    const update = () =>
      setHostWidth(host?.getBoundingClientRect().width || window.innerWidth);
    update();
    if (!host) {
      window.addEventListener("resize", update);
      return () => window.removeEventListener("resize", update);
    }
    const observer = new ResizeObserver(update);
    observer.observe(host);
    return () => observer.disconnect();
  }, [host]);

  const { panelWidth, startResize } = usePanelResize({
    panelId,
    hostWidth,
    elementRef: contentRef,
    enabled: mode === "panel",
    initialWidth: panelInitialWidth,
    minWidth: panelMinWidth,
  });

  const morph = useCallback((from: DOMRect, fromRadius: string) => {
    const el = contentRef.current;
    if (!el) return;

    animationRef.current?.cancel();

    const to = el.getBoundingClientRect();
    const toRadius = getComputedStyle(el).borderRadius;

    const origin = (
      el.offsetParent as HTMLElement | null
    )?.getBoundingClientRect();
    const originLeft = origin?.left ?? 0;
    const originTop = origin?.top ?? 0;

    const box = (rect: DOMRect, radius: string) => ({
      left: `${rect.left - originLeft}px`,
      top: `${rect.top - originTop}px`,
      right: "auto",
      bottom: "auto",
      margin: "0px",
      width: `${rect.width}px`,
      height: `${rect.height}px`,
      borderRadius: radius,
    });

    animationRef.current = el.animate(
      [
        box(from, fromRadius),
        { ...box(to, toRadius), offset: RADIUS_MS / MORPH_MS },
        box(to, toRadius),
      ],
      { duration: MORPH_MS, easing: MORPH_EASING },
    );

    animationRef.current.finished
      .then(() => {
        animationRef.current = null;
      })
      .catch(() => undefined);
  }, []);

  const crossFade = useCallback(() => {
    const body = bodyRef.current;
    if (!body) return;

    fadeRef.current?.cancel();
    window.clearTimeout(swapTimer.current);

    setFrozenFrame(lastFrame.current);
    fadeRef.current = body.animate(
      [{ opacity: 1 }, { opacity: 0, offset: 0.5 }, { opacity: 1 }],
      { duration: FADE_MS * 2, easing: "linear" },
    );
    swapTimer.current = window.setTimeout(() => setFrozenFrame(null), FADE_MS);
  }, []);

  const lastBox = useRef<{ rect: DOMRect; radius: string } | null>(null);

  useLayoutEffect(() => {
    const el = contentRef.current;
    if (!el) {
      lastBox.current = null;
      return;
    }

    const changed = mode !== prevMode.current;
    prevMode.current = mode;
    const previous = lastBox.current;

    lastBox.current = {
      rect: el.getBoundingClientRect(),
      radius: getComputedStyle(el).borderRadius,
    };

    if (changed && previous && !reducedMotion) {
      morph(previous.rect, previous.radius);
      crossFade();
    }
  });

  useEffect(() => {
    if (frozenFrame === null) {
      lastFrame.current = { mode, title, children, footer };
    }
  });

  useEffect(() => {
    if (open) return;
    animationRef.current?.cancel();
    fadeRef.current?.cancel();
    window.clearTimeout(swapTimer.current);
    setFrozenFrame(null);
  }, [open]);

  useEffect(() => () => window.clearTimeout(swapTimer.current), []);

  const frame = frozenFrame ?? { mode, title, children, footer };
  const isPanel = mode === "panel";
  const framedAsPanel = frame.mode === "panel";

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogPortal container={host}>
        <DialogOverlay />
        <DialogPrimitive.Content
          ref={contentRef}
          data-morph-mode={mode}
          className={cn(
            "z-50 flex flex-col overflow-hidden bg-background shadow-lg outline-none",
            isPanel ? "border-l border-border" : "border border-border",
            className,
          )}
          style={
            isPanel
              ? {
                  position: "fixed",
                  inset: "0 0 0 auto",
                  width: panelWidth ?? modalWidth,
                  borderRadius: 0,
                }
              : {
                  position: "fixed",
                  inset: 0,
                  margin: "auto",
                  width: modalWidth,
                  height: "fit-content",
                  maxHeight: `calc(100% - ${MODAL_MARGIN * 2}px)`,
                  borderRadius: 6,
                }
          }
        >
          {isPanel && (
            <div
              data-testid="morph-resize-handle"
              onMouseDown={startResize}
              className="absolute inset-y-0 left-0 z-10 w-4 cursor-col-resize border-l hover:border-l-2 hover:border-l-primary"
            />
          )}
          <div ref={bodyRef} className="flex min-h-0 flex-1 flex-col">
            {framedAsPanel ? (
              <DialogHeader className="h-12 shrink-0 flex-row items-center gap-2 space-y-0 border-b border-border px-6 pb-0 text-left">
                <DialogClose asChild>
                  <Button variant="ghost" size="icon-2xs">
                    <X />
                    <span className="sr-only">Close</span>
                  </Button>
                </DialogClose>
                <DialogTitle className="comet-title-xs truncate pr-0">
                  {frame.title}
                </DialogTitle>
              </DialogHeader>
            ) : (
              <DialogHeader className="shrink-0 flex-row items-start justify-between gap-4 space-y-0 px-8 pt-6">
                <DialogTitle className="comet-title-s">
                  {frame.title}
                </DialogTitle>
                <DialogCloseButton />
              </DialogHeader>
            )}
            <div
              className={cn(
                "min-h-0 flex-1 overflow-y-auto",
                framedAsPanel ? "px-6 pt-4" : "px-8",
              )}
            >
              {frame.children}
            </div>
            {frame.footer && (
              <DialogFooter
                className={cn(
                  "shrink-0",
                  framedAsPanel
                    ? "border-t border-border px-6 pb-4 pt-4"
                    : "px-8 pb-8 pt-4",
                )}
              >
                {frame.footer}
              </DialogFooter>
            )}
          </div>
        </DialogPrimitive.Content>
      </DialogPortal>
    </Dialog>
  );
};

export default MorphDialog;
