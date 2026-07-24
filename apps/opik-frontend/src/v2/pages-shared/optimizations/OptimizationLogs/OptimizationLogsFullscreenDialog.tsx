import React, { useCallback, useEffect, useRef } from "react";
import { ArrowDownToLine, Clock } from "lucide-react";
import { Button } from "@/ui/button";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/ui/dialog";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { formatDate } from "@/lib/date";
import { applyVerticalScrollRatio, getVerticalScrollRatio } from "@/lib/scroll";
import { useChatScroll } from "@/v2/pages-shared/traces/TraceDetailsPanel/TraceAIViewer/useChatScroll";

type OptimizationLogsFullscreenDialogProps = {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onClose: (scrollRatio: number) => void;
  logContent: string;
  logHtml: string;
  isInProgress: boolean;
  lastUpdatedAt: string | null;
  hasNewLogs: boolean;
  initialScrollRatio?: number;
};

const OptimizationLogsFullscreenDialog: React.FC<
  OptimizationLogsFullscreenDialogProps
> = ({
  open,
  onOpenChange,
  onClose,
  logContent,
  logHtml,
  isInProgress,
  lastUpdatedAt,
  hasNewLogs,
  initialScrollRatio = 1,
}) => {
  const hasInitializedScrollRef = useRef(false);

  const { scrollContainerRef, handleScroll, scrollToBottom } = useChatScroll({
    contentLength: logContent.length,
    isStreaming: isInProgress,
  });

  // set initial scroll position when dialog opens
  useEffect(() => {
    if (open && logContent && !hasInitializedScrollRef.current) {
      requestAnimationFrame(() => {
        if (scrollContainerRef.current) {
          applyVerticalScrollRatio(
            scrollContainerRef.current,
            initialScrollRatio,
          );
          hasInitializedScrollRef.current = true;
        }
      });
    }

    if (!open) {
      hasInitializedScrollRef.current = false;
    }
  }, [open, logContent, initialScrollRatio, scrollContainerRef]);

  const handleOpenChange = useCallback(
    (isOpen: boolean) => {
      if (!isOpen && scrollContainerRef.current) {
        onClose(getVerticalScrollRatio(scrollContainerRef.current));
      }
      onOpenChange(isOpen);
    },
    [onOpenChange, onClose, scrollContainerRef],
  );

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="flex h-[90vh] w-[90vw] max-w-[90vw] flex-col">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <span>Logs</span>
            {lastUpdatedAt && (
              <TooltipWrapper
                content={`Last updated at ${formatDate(lastUpdatedAt, {
                  includeSeconds: true,
                })}`}
              >
                <Clock className="size-3.5 text-muted-slate" />
              </TooltipWrapper>
            )}
          </DialogTitle>
        </DialogHeader>
        <div className="relative flex flex-1 flex-col overflow-hidden">
          <div
            ref={scrollContainerRef}
            onScroll={handleScroll}
            className="flex-1 overflow-auto rounded-sm border border-border bg-muted/50 p-3"
          >
            <pre
              className="whitespace-pre-wrap break-words font-mono text-xs leading-relaxed"
              dangerouslySetInnerHTML={{ __html: logHtml }}
            />
          </div>
          {hasNewLogs && (
            <Button
              variant="special"
              size="2xs"
              onClick={scrollToBottom}
              className="absolute bottom-4 left-1/2 -translate-x-1/2 gap-1.5 shadow-md"
            >
              <ArrowDownToLine className="size-3.5" />
              New logs available
            </Button>
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
};

export default OptimizationLogsFullscreenDialog;
