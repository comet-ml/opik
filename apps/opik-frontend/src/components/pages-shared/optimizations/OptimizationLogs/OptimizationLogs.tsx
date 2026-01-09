import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { ArrowDownToLine, ListEnd, RotateCw } from "lucide-react";
import { Card, CardContent } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Optimization } from "@/types/optimizations";
import useOptimizationStudioLogs from "@/api/optimizations/useOptimizationStudioLogs";
import Loader from "@/components/shared/Loader/Loader";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { cn } from "@/lib/utils";
import {
  IN_PROGRESS_OPTIMIZATION_STATUSES,
  OPTIMIZATION_ACTIVE_REFETCH_INTERVAL,
} from "@/lib/optimizations";
import { convertTerminalOutputToHtml } from "@/lib/terminalOutput";

type OptimizationLogsProps = {
  optimization: Optimization | null;
};

const SCROLL_THRESHOLD = 50; // pixels from bottom to consider "at bottom"

const OptimizationLogs: React.FC<OptimizationLogsProps> = ({
  optimization,
}) => {
  const scrollContainerRef = useRef<HTMLDivElement>(null);
  const [isAutoScrollEnabled, setIsAutoScrollEnabled] = useState(true);
  const [hasNewLogs, setHasNewLogs] = useState(false);
  const prevLogLengthRef = useRef<number>(0);

  const { data, isPending, refetch } = useOptimizationStudioLogs(
    {
      optimizationId: optimization?.id ?? "",
    },
    {
      enabled: Boolean(optimization?.id),
      refetchInterval:
        optimization?.status &&
        IN_PROGRESS_OPTIMIZATION_STATUSES.includes(optimization?.status)
          ? OPTIMIZATION_ACTIVE_REFETCH_INTERVAL
          : undefined,
      retry: false,
    },
  );

  const logContent = data?.content ?? "";

  // Convert terminal output to HTML for Rich-formatted logs
  const logHtml = useMemo(
    () => convertTerminalOutputToHtml(logContent),
    [logContent],
  );

  useEffect(() => {
    const currentLength = logContent.length;
    const hasNewContent = currentLength > prevLogLengthRef.current;

    if (hasNewContent && !isAutoScrollEnabled) {
      setHasNewLogs(true);
    }

    prevLogLengthRef.current = currentLength;
  }, [logContent, isAutoScrollEnabled]);

  useEffect(() => {
    if (isAutoScrollEnabled && scrollContainerRef.current && logContent) {
      scrollContainerRef.current.scrollTop =
        scrollContainerRef.current.scrollHeight;
    }
  }, [logContent, isAutoScrollEnabled]);

  const handleScroll = useCallback(() => {
    if (!scrollContainerRef.current) return;

    const { scrollTop, scrollHeight, clientHeight } =
      scrollContainerRef.current;
    const isAtBottom =
      scrollHeight - scrollTop - clientHeight < SCROLL_THRESHOLD;

    // if user scrolls away from bottom, disable auto-scroll
    // if user scrolls back to bottom, re-enable auto-scroll
    setIsAutoScrollEnabled(isAtBottom);

    if (isAtBottom) {
      setHasNewLogs(false);
    }
  }, []);

  const scrollToBottom = useCallback(() => {
    if (scrollContainerRef.current) {
      scrollContainerRef.current.scrollTop =
        scrollContainerRef.current.scrollHeight;
      setIsAutoScrollEnabled(true);
      setHasNewLogs(false);
    }
  }, []);

  if (!optimization) {
    return null;
  }

  const renderContent = () => {
    if (isPending && !logContent) {
      return <Loader />;
    }

    if (!logContent) {
      const isInProgress =
        optimization?.status &&
        IN_PROGRESS_OPTIMIZATION_STATUSES.includes(optimization.status);

      return (
        <div className="flex flex-1 items-center justify-center">
          <div className="comet-body-s text-muted-slate">
            {isInProgress ? "Logs will appear shortly" : "No logs available"}
          </div>
        </div>
      );
    }

    return (
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
    );
  };

  return (
    <Card className="size-full">
      <CardContent className="flex h-full flex-col p-4">
        <div className="mb-3 flex items-center justify-between">
          <h3 className="comet-body-s-accented">Logs</h3>
          <div className="flex items-center gap-1">
            {logContent && (
              <TooltipWrapper
                content={
                  isAutoScrollEnabled
                    ? "Auto-scroll enabled"
                    : "Click to scroll to bottom"
                }
              >
                <Button
                  variant="ghost"
                  size="icon-xs"
                  onClick={scrollToBottom}
                  className={cn(isAutoScrollEnabled && "text-primary")}
                >
                  <ListEnd className="size-3.5" />
                </Button>
              </TooltipWrapper>
            )}
            <TooltipWrapper content="Refresh logs">
              <Button
                variant="ghost"
                size="icon-xs"
                onClick={() => refetch()}
                disabled={isPending}
              >
                <RotateCw
                  className={cn("size-3.5", isPending && "animate-spin")}
                />
              </Button>
            </TooltipWrapper>
          </div>
        </div>

        {renderContent()}
      </CardContent>
    </Card>
  );
};

export default OptimizationLogs;
