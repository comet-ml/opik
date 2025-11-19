import React, { useCallback, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { ArrowDown, ArrowLeft, ArrowRight, ArrowUp, X } from "lucide-react";
import isFunction from "lodash/isFunction";
import { useHotkeys } from "react-hotkeys-hook";

import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

const INITIAL_WIDTH = 0.75;
const MIN_LEFT_POSITION = 0.1;
const MAX_LEFT_POSITION = 0.8;

type ArrowNavigationConfig = {
  hasPrevious: boolean;
  hasNext: boolean;
  onChange: (shift: 1 | -1) => void;
  previousTooltip?: string;
  nextTooltip?: string;
};

type ResizableSidePanelProps = {
  panelId: string;
  children: React.ReactNode;
  entity?: string;
  headerContent?: React.ReactNode;
  open?: boolean;
  onClose: () => void;
  initialWidth?: number;
  minWidth?: number;
  ignoreHotkeys?: boolean;
  closeOnClickOutside?: boolean;
  horizontalNavigation?: ArrowNavigationConfig;
  verticalNavigation?: ArrowNavigationConfig;
};

const UP_HOTKEYS = ["↑"];
const DOWN_HOTKEYS = ["↓"];
const LEFT_HOTKEYS = ["←"];
const RIGHT_HOTKEYS = ["→"];
const ESC_HOTKEYS = ["Esc"];

const calculateLeftPosition = (percentage: number, minWidth?: number) => {
  if (minWidth) {
    return Math.min(
      window.innerWidth * percentage,
      window.innerWidth - minWidth,
    );
  } else {
    return window.innerWidth * percentage;
  }
};

const ResizableSidePanel: React.FunctionComponent<ResizableSidePanelProps> = ({
  panelId,
  children,
  entity = "",
  headerContent,
  open = false,
  onClose,
  initialWidth = INITIAL_WIDTH,
  minWidth,
  ignoreHotkeys = false,
  closeOnClickOutside = true,
  horizontalNavigation,
  verticalNavigation,
}) => {
  const localStorageKey = `${panelId}-side-panel-width`;

  const width = parseFloat(
    localStorage.getItem(localStorageKey) ?? `${1 - initialWidth}`,
  );
  const resizeHandleRef = useRef<null | HTMLDivElement>(null);
  const leftRef = useRef<number>(width);
  const [left, setLeft] = useState<number>(
    calculateLeftPosition(leftRef.current, minWidth),
  );

  const startResizing = useCallback((event: MouseEvent) => {
    resizeHandleRef.current = event.target as HTMLDivElement;
    resizeHandleRef.current.setAttribute("data-resize-handle-active", "true");
    resizeHandleRef.current.parentElement!.style.setProperty(
      "transition",
      `unset`,
    );
  }, []);

  useHotkeys(
    "ArrowUp,ArrowDown,ArrowLeft,ArrowRight,Escape",
    (keyboardEvent: KeyboardEvent) => {
      if (!open) return;
      keyboardEvent.stopPropagation();
      keyboardEvent.preventDefault();
      switch (keyboardEvent.code) {
        case "ArrowLeft":
          isFunction(horizontalNavigation?.onChange) &&
            horizontalNavigation?.hasPrevious &&
            horizontalNavigation.onChange(-1);
          break;
        case "ArrowRight":
          isFunction(horizontalNavigation?.onChange) &&
            horizontalNavigation?.hasNext &&
            horizontalNavigation?.onChange(1);
          break;
        case "ArrowUp":
          isFunction(verticalNavigation?.onChange) &&
            verticalNavigation?.hasPrevious &&
            verticalNavigation.onChange(-1);
          break;
        case "ArrowDown":
          isFunction(verticalNavigation?.onChange) &&
            verticalNavigation?.hasNext &&
            verticalNavigation.onChange(1);
          break;
        case "Escape":
          onClose();
          break;
      }
    },
    { ignoreEventWhen: () => ignoreHotkeys },
    [verticalNavigation, horizontalNavigation, onClose, open, ignoreHotkeys],
  );

  useEffect(() => {
    const handleMouseMove = (event: MouseEvent) => {
      if (resizeHandleRef.current) {
        leftRef.current = event.pageX / window.innerWidth;
        const left = Math.max(
          MIN_LEFT_POSITION,
          Math.min(MAX_LEFT_POSITION, leftRef.current),
        );
        setLeft(calculateLeftPosition(left, minWidth));
      }
    };

    const handleMouseUp = () => {
      if (resizeHandleRef.current) {
        resizeHandleRef.current.removeAttribute("data-resize-handle-active");
        resizeHandleRef.current.parentElement!.style.removeProperty(
          "transition",
        );
        resizeHandleRef.current = null;
        localStorage.setItem(localStorageKey, leftRef.current.toString());
      }
    };

    const handleResize = () => {
      const left = Math.max(
        MIN_LEFT_POSITION,
        Math.min(MAX_LEFT_POSITION, leftRef.current),
      );
      setLeft(calculateLeftPosition(left, minWidth));
    };

    window.addEventListener("mousemove", handleMouseMove);
    window.addEventListener("mouseup", handleMouseUp);
    window.addEventListener("resize", handleResize);
    return () => {
      window.removeEventListener("mousemove", handleMouseMove);
      window.removeEventListener("mouseup", handleMouseUp);
      window.removeEventListener("resize", handleResize);
    };
  }, [localStorageKey, minWidth]);

  const renderNavigation = () => {
    if (!horizontalNavigation && !verticalNavigation) return null;

    return (
      <>
        <Separator orientation="vertical" className="mx-1 h-4" />
        {horizontalNavigation && (
          <div className="flex shrink-0 items-center">
            <TooltipWrapper
              content={
                horizontalNavigation.previousTooltip ?? `Previous ${entity}`
              }
              hotkeys={LEFT_HOTKEYS}
            >
              <Button
                variant="outline"
                size="icon-sm"
                disabled={!horizontalNavigation.hasPrevious}
                onClick={() => horizontalNavigation.onChange(-1)}
                data-testid="side-panel-previous"
                className="rounded-r-none"
              >
                <ArrowLeft />
              </Button>
            </TooltipWrapper>
            <TooltipWrapper
              content={horizontalNavigation.nextTooltip ?? `Next ${entity}`}
              hotkeys={RIGHT_HOTKEYS}
            >
              <Button
                variant="outline"
                size="icon-sm"
                disabled={!horizontalNavigation.hasNext}
                onClick={() => horizontalNavigation.onChange(1)}
                data-testid="side-panel-next"
                className="-ml-px rounded-l-none"
              >
                <ArrowRight />
              </Button>
            </TooltipWrapper>
          </div>
        )}
        {verticalNavigation && (
          <div className="flex shrink-0 items-center">
            <TooltipWrapper
              content={verticalNavigation.previousTooltip ?? `Up ${entity}`}
              hotkeys={UP_HOTKEYS}
            >
              <Button
                variant="outline"
                size="icon-sm"
                disabled={!verticalNavigation.hasPrevious}
                onClick={() => verticalNavigation.onChange(-1)}
                data-testid="side-panel-up"
                className="rounded-r-none"
              >
                <ArrowUp />
              </Button>
            </TooltipWrapper>
            <TooltipWrapper
              content={verticalNavigation.nextTooltip ?? `Down ${entity}`}
              hotkeys={DOWN_HOTKEYS}
            >
              <Button
                variant="outline"
                size="icon-sm"
                disabled={!verticalNavigation.hasNext}
                onClick={() => verticalNavigation.onChange(1)}
                data-testid="side-panel-down"
                className="-ml-px rounded-l-none"
              >
                <ArrowDown />
              </Button>
            </TooltipWrapper>
          </div>
        )}
      </>
    );
  };

  return createPortal(
    <div className="relative z-10">
      {open && closeOnClickOutside && (
        <div className="fixed inset-0 bg-black/10" onClick={onClose} />
      )}
      <div
        className="fixed inset-0 bg-background shadow-xl transition-transform duration-150 will-change-transform"
        style={{
          left: left + "px",
          transform: open ? "translateX(0)" : "translateX(100%)",
        }}
        data-testid={panelId}
      >
        {open && (
          <>
            <div
              className="absolute inset-y-0 left-0 z-20 flex w-4 cursor-col-resize flex-col justify-center border-l hover:border-l-2 data-[resize-handle-active]:border-l-blue-600"
              onMouseDown={startResizing as never}
            ></div>
            <div className="relative flex size-full">
              <div className="absolute inset-x-0 top-0 flex h-[60px] items-center pl-6 pr-5">
                <div className="flex items-center gap-2">
                  <TooltipWrapper
                    content={`Close ${entity}`}
                    hotkeys={ESC_HOTKEYS}
                  >
                    <Button
                      data-testid="side-panel-close"
                      variant="outline"
                      size="icon-sm"
                      onClick={onClose}
                    >
                      <X />
                    </Button>
                  </TooltipWrapper>
                  {renderNavigation()}
                </div>
                {headerContent && headerContent}
              </div>
              <div className="absolute inset-x-0 bottom-0 top-[60px] border-t">
                {children}
              </div>
            </div>
          </>
        )}
      </div>
    </div>,
    document.body,
    "resizable-side-panel",
  );
};

export default ResizableSidePanel;
