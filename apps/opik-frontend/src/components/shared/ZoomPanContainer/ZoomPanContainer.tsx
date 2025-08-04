import React, {
  useState,
  useRef,
  ReactNode,
  useCallback,
  useMemo,
} from "react";
import { Hand, ZoomIn, ZoomOut, RotateCcw, Expand } from "lucide-react";

import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

const ZOOM_CONFIG = {
  DEFAULT: 1,
  MIN: 0.2,
  MAX: 10,
  STEP: 0.2,
} as const;

const TRANSITION_CONFIG = {
  DURATION: "0.1s",
  TIMING: "ease-out",
} as const;

interface ZoomPanContainerProps {
  children: ReactNode;
  expandButton?: boolean;
  dialogTitle?: string;
  className?: string;
}

interface DragState {
  startX: number;
  startY: number;
  initialOffset: { x: number; y: number };
}

const ZoomPanContainer: React.FC<ZoomPanContainerProps> = ({
  children,
  expandButton = true,
  dialogTitle = "Fullscreen View",
  className,
}) => {
  const [scale, setScale] = useState<number>(ZOOM_CONFIG.DEFAULT);
  const [isPanning, setIsPanning] = useState<boolean>(false);
  const [isDragging, setIsDragging] = useState<boolean>(false);
  const [isFullscreenOpen, setIsFullscreenOpen] = useState<boolean>(false);
  const [panOffset, setPanOffset] = useState<{ x: number; y: number }>({
    x: 0,
    y: 0,
  });

  const dragStateRef = useRef<DragState | null>(null);

  const handleZoomIn = useCallback(() => {
    const newScale = Math.min(scale + ZOOM_CONFIG.STEP, ZOOM_CONFIG.MAX);
    setScale(newScale);
  }, [scale]);

  const handleZoomOut = useCallback(() => {
    const newScale = Math.max(scale - ZOOM_CONFIG.STEP, ZOOM_CONFIG.MIN);
    setScale(newScale);
  }, [scale]);

  const handleReset = useCallback(() => {
    setScale(ZOOM_CONFIG.DEFAULT);
    setPanOffset({ x: 0, y: 0 });
  }, []);

  const handleMouseDown = useCallback(
    (e: React.MouseEvent) => {
      if (!isPanning) return;

      e.preventDefault();
      setIsDragging(true);

      dragStateRef.current = {
        startX: e.clientX,
        startY: e.clientY,
        initialOffset: { ...panOffset },
      };

      const handleMouseMove = (e: MouseEvent) => {
        if (!dragStateRef.current) return;

        const deltaX = e.clientX - dragStateRef.current.startX;
        const deltaY = e.clientY - dragStateRef.current.startY;

        const newOffset = {
          x: dragStateRef.current.initialOffset.x + deltaX,
          y: dragStateRef.current.initialOffset.y + deltaY,
        };

        setPanOffset(newOffset);
      };

      const handleMouseUp = () => {
        setIsDragging(false);
        dragStateRef.current = null;
        document.removeEventListener("mousemove", handleMouseMove);
        document.removeEventListener("mouseup", handleMouseUp);
      };

      document.addEventListener("mousemove", handleMouseMove);
      document.addEventListener("mouseup", handleMouseUp);
    },
    [isPanning, panOffset],
  );

  const togglePanning = useCallback(() => {
    setIsPanning(!isPanning);
  }, [isPanning]);

  const handleExpandClick = useCallback(() => {
    setIsFullscreenOpen(true);
  }, []);

  const canZoomIn = useMemo(() => scale < ZOOM_CONFIG.MAX, [scale]);
  const canZoomOut = useMemo(() => scale > ZOOM_CONFIG.MIN, [scale]);
  const canReset = useMemo(
    () =>
      scale !== ZOOM_CONFIG.DEFAULT || panOffset.x !== 0 || panOffset.y !== 0,
    [scale, panOffset.x, panOffset.y],
  );

  const contentStyle = useMemo(
    () => ({
      transform: `scale(${scale}) translate(${panOffset.x / scale}px, ${
        panOffset.y / scale
      }px)`,
      transformOrigin: "center center",
      transition: isPanning
        ? "none"
        : `transform ${TRANSITION_CONFIG.DURATION} ${TRANSITION_CONFIG.TIMING}`,
      userSelect: isDragging ? "none" : "auto",
    }),
    [scale, panOffset.x, panOffset.y, isPanning, isDragging],
  );

  const containerStyle = useMemo(
    () => ({
      cursor: !isPanning ? "default" : isDragging ? "grabbing" : "grab",
      userSelect: isDragging ? "none" : "auto",
    }),
    [isDragging, isPanning],
  );

  return (
    <>
      <div className={cn("relative size-full overflow-hidden p-6", className)}>
        <div className="absolute right-0 top-0 z-10 flex gap-1.5">
          <TooltipWrapper
            content={isPanning ? "Disable pan mode" : "Enable pan mode"}
          >
            <Button
              variant={isPanning ? "secondary" : "outline"}
              size="icon-2xs"
              onClick={togglePanning}
              aria-label={isPanning ? "Disable pan mode" : "Enable pan mode"}
            >
              <Hand />
            </Button>
          </TooltipWrapper>

          <TooltipWrapper
            content={canZoomIn ? "Zoom in" : "Maximum zoom reached"}
          >
            <Button
              variant="outline"
              size="icon-2xs"
              onClick={handleZoomIn}
              disabled={!canZoomIn}
              aria-label="Zoom in"
            >
              <ZoomIn />
            </Button>
          </TooltipWrapper>

          <TooltipWrapper
            content={canZoomOut ? "Zoom out" : "Minimum zoom reached"}
          >
            <Button
              variant="outline"
              size="icon-2xs"
              onClick={handleZoomOut}
              disabled={!canZoomOut}
              aria-label="Zoom out"
            >
              <ZoomOut />
            </Button>
          </TooltipWrapper>

          <TooltipWrapper
            content={
              canReset
                ? "Reset zoom and position"
                : "Already at default position"
            }
          >
            <Button
              variant="outline"
              size="icon-2xs"
              onClick={handleReset}
              disabled={!canReset}
              aria-label="Reset zoom and position"
            >
              <RotateCcw />
            </Button>
          </TooltipWrapper>

          {expandButton && (
            <TooltipWrapper content="Open in fullscreen">
              <Button
                variant="outline"
                size="icon-2xs"
                onClick={handleExpandClick}
                aria-label="Open in fullscreen"
              >
                <Expand />
              </Button>
            </TooltipWrapper>
          )}
        </div>

        <div
          className="flex size-full items-center justify-center overflow-hidden"
          style={containerStyle as React.CSSProperties}
          onMouseDown={handleMouseDown}
          role="img"
          aria-label="Zoomable and pannable content"
        >
          <div
            className="size-full"
            style={contentStyle as React.CSSProperties}
          >
            {children}
          </div>
        </div>
      </div>

      {expandButton && (
        <Dialog open={isFullscreenOpen} onOpenChange={setIsFullscreenOpen}>
          <DialogContent
            className="min-h-[90vh] w-[90vw]"
            onEscapeKeyDown={(e) => e.stopPropagation()}
            onOpenAutoFocus={(e) => e.preventDefault()}
          >
            <DialogHeader>
              <DialogTitle>{dialogTitle}</DialogTitle>
              <div className="size-full max-h-[80vh] p-4">
                <ZoomPanContainer expandButton={false}>
                  {children}
                </ZoomPanContainer>
              </div>
            </DialogHeader>
          </DialogContent>
        </Dialog>
      )}
    </>
  );
};

export default ZoomPanContainer;
