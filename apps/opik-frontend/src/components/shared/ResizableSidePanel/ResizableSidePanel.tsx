import React, { useCallback, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { ArrowDown, ArrowUp, X } from "lucide-react";
import isFunction from "lodash/isFunction";
import { useHotkeys } from "react-hotkeys-hook";

import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

const INITIAL_WIDTH = 0.75;
const MIN_LEFT_POSITION = 0.1;
const MAX_LEFT_POSITION = 0.8;

type ResizableSidePanelProps = {
  panelId: string;
  children: React.ReactNode;
  entity?: string;
  headerContent?: React.ReactNode;
  open?: boolean;
  hasPreviousRow?: boolean;
  hasNextRow?: boolean;
  onClose: () => void;
  onRowChange?: (shift: number) => void;
  initialWidth?: number;
  ignoreHotkeys?: boolean;
  closeOnClickOutside?: boolean;
};

const UP_HOTKEYS = ["↑"];
const DOWN_HOTKEYS = ["↓"];
const ESC_HOTKEYS = ["Esc"];

const ResizableSidePanel: React.FunctionComponent<ResizableSidePanelProps> = ({
  panelId,
  children,
  entity = "",
  headerContent,
  open = false,
  hasPreviousRow,
  hasNextRow,
  onClose,
  onRowChange,
  initialWidth = INITIAL_WIDTH,
  ignoreHotkeys = false,
  closeOnClickOutside = true,
}) => {
  const localStorageKey = `${panelId}-side-panel-width`;

  const width = parseFloat(
    localStorage.getItem(localStorageKey) ?? `${1 - initialWidth}`,
  );
  const resizeHandleRef = useRef<null | HTMLDivElement>(null);
  const leftRef = useRef<number>(width);
  const [left, setLeft] = useState<number>(leftRef.current * window.innerWidth);

  const startResizing = useCallback((event: MouseEvent) => {
    resizeHandleRef.current = event.target as HTMLDivElement;
    resizeHandleRef.current.setAttribute("data-resize-handle-active", "true");
  }, []);

  useHotkeys(
    "ArrowUp,ArrowDown,Escape",
    (keyboardEvent: KeyboardEvent) => {
      if (!open) return;
      keyboardEvent.stopPropagation();
      switch (keyboardEvent.code) {
        case "ArrowUp":
          isFunction(onRowChange) && hasPreviousRow && onRowChange(-1);
          break;
        case "ArrowDown":
          isFunction(onRowChange) && hasNextRow && onRowChange(1);
          break;
        case "Escape":
          onClose();
          break;
      }
    },
    { ignoreEventWhen: () => ignoreHotkeys },
    [onRowChange, onClose, open, ignoreHotkeys],
  );

  useEffect(() => {
    const handleMouseMove = (event: MouseEvent) => {
      if (resizeHandleRef.current) {
        leftRef.current = event.pageX / window.innerWidth;
        const left = Math.max(
          MIN_LEFT_POSITION,
          Math.min(MAX_LEFT_POSITION, leftRef.current),
        );
        setLeft(window.innerWidth * left);
      }
    };

    const handleMouseUp = () => {
      if (resizeHandleRef.current) {
        resizeHandleRef.current.removeAttribute("data-resize-handle-active");
        resizeHandleRef.current = null;
        localStorage.setItem(localStorageKey, leftRef.current.toString());
      }
    };

    const handleResize = () => {
      const left = Math.max(
        MIN_LEFT_POSITION,
        Math.min(MAX_LEFT_POSITION, leftRef.current),
      );
      setLeft(window.innerWidth * left);
    };

    window.addEventListener("mousemove", handleMouseMove);
    window.addEventListener("mouseup", handleMouseUp);
    window.addEventListener("resize", handleResize);
    return () => {
      window.removeEventListener("mousemove", handleMouseMove);
      window.removeEventListener("mouseup", handleMouseUp);
      window.removeEventListener("resize", handleResize);
    };
  }, [localStorageKey]);

  const renderNavigation = () => {
    if (!isFunction(onRowChange)) return null;

    return (
      <>
        <Separator orientation="vertical" className="mx-4 h-8" />
        <TooltipWrapper content={`Previous ${entity}`} hotkeys={UP_HOTKEYS}>
          <Button
            variant="outline"
            size="icon-sm"
            disabled={!hasPreviousRow}
            onClick={() => onRowChange(-1)}
            data-testid="side-panel-previous"
          >
            <ArrowUp />
          </Button>
        </TooltipWrapper>
        <TooltipWrapper content={`Next ${entity}`} hotkeys={DOWN_HOTKEYS}>
          <Button
            variant="outline"
            size="icon-sm"
            disabled={!hasNextRow}
            onClick={() => onRowChange(1)}
            data-testid="side-panel-next"
          >
            <ArrowDown />
          </Button>
        </TooltipWrapper>
      </>
    );
  };

  return createPortal(
    <div className="relative z-10">
      {open && closeOnClickOutside && (
        <div className="fixed inset-0 bg-black/10" onClick={onClose} />
      )}
      {open && (
        <div
          className="fixed inset-0 translate-x-0 bg-background shadow-xl"
          style={{ left: left + "px" }}
          data-testid={panelId}
        >
          <div
            className="absolute inset-y-0 left-0 z-20 flex w-4 cursor-col-resize flex-col justify-center border-l transition-all hover:border-l-2 data-[resize-handle-active]:border-l-blue-600"
            onMouseDown={startResizing as never}
          ></div>
          <div className="relative flex size-full">
            <div className="absolute inset-x-0 top-0 flex h-[60px] items-center justify-between gap-6 pl-6 pr-5">
              <div className="flex gap-2">
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
              {headerContent && <div>{headerContent}</div>}
            </div>
            <div className="absolute inset-x-0 bottom-0 top-[60px] border-t">
              {children}
            </div>
          </div>
        </div>
      )}
    </div>,
    document.body,
    "resizable-side-panel",
  );
};

export default ResizableSidePanel;
