import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import {
  ArrowDownToLine,
  Clock,
  ListEnd,
  Maximize2,
  RotateCw,
} from "lucide-react";
import { Card, CardContent } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Optimization } from "@/types/optimizations";
import useOptimizationStudioLogs from "@/api/optimizations/useOptimizationStudioLogs";
import Loader from "@/components/shared/Loader/Loader";
import { Spinner } from "@/components/ui/spinner";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { cn } from "@/lib/utils";
import {
  IN_PROGRESS_OPTIMIZATION_STATUSES,
  OPTIMIZATION_ACTIVE_REFETCH_INTERVAL,
} from "@/lib/optimizations";
import { convertTerminalOutputToHtml } from "@/lib/terminalOutput";
import { useChatScroll } from "@/components/pages-shared/traces/TraceDetailsPanel/TraceAIViewer/useChatScroll";
import { formatDate } from "@/lib/date";
import OptimizationLogsFullscreenDialog from "./OptimizationLogsFullscreenDialog";

type OptimizationLogsProps = {
  optimization: Optimization | null;
};

const OptimizationLogs: React.FC<OptimizationLogsProps> = ({
  optimization,
}) => {
  const isInProgress =
    optimization?.status &&
    IN_PROGRESS_OPTIMIZATION_STATUSES.includes(optimization.status);

  const [isFullscreen, setIsFullscreen] = useState(false);
  const [initialScrollRatio, setInitialScrollRatio] = useState(1);

  const { data, isPending, refetch, dataUpdatedAt } = useOptimizationStudioLogs(
    {
      optimizationId: optimization?.id ?? "",
    },
    {
      enabled: Boolean(optimization?.id),
      refetchInterval: OPTIMIZATION_ACTIVE_REFETCH_INTERVAL,
      retry: false,
    },
  );

  const logContent = data?.content ?? "";
  const lastUpdatedAt = dataUpdatedAt
    ? new Date(dataUpdatedAt).toISOString()
    : null;
  const prevLogLengthRef = useRef<number>(0);
  const [hasNewLogs, setHasNewLogs] = useState(false);

  const {
    scrollContainerRef,
    isAutoScrollEnabled,
    handleScroll,
    scrollToBottom,
  } = useChatScroll({
    contentLength: logContent.length,
    isStreaming: Boolean(isInProgress),
  });

  useEffect(() => {
    const currentLength = logContent.length;
    const hasNewContent = currentLength > prevLogLengthRef.current;

    if (hasNewContent && !isAutoScrollEnabled) {
      setHasNewLogs(true);
    }

    if (isAutoScrollEnabled) {
      setHasNewLogs(false);
    }

    prevLogLengthRef.current = currentLength;
  }, [logContent.length, isAutoScrollEnabled]);

  const logHtml = useMemo(
    () => convertTerminalOutputToHtml(logContent),
    [logContent],
  );

  const openFullscreen = useCallback(() => {
    if (scrollContainerRef.current) {
      const { scrollTop, scrollHeight, clientHeight } =
        scrollContainerRef.current;
      const maxScroll = scrollHeight - clientHeight;
      const ratio = maxScroll > 0 ? scrollTop / maxScroll : 1;
      setInitialScrollRatio(ratio);
    }
    setIsFullscreen(true);
  }, [scrollContainerRef]);

  const handleFullscreenClose = useCallback(
    (scrollRatio: number) => {
      requestAnimationFrame(() => {
        if (scrollContainerRef.current) {
          const { scrollHeight, clientHeight } = scrollContainerRef.current;
          const maxScroll = scrollHeight - clientHeight;
          scrollContainerRef.current.scrollTop = maxScroll * scrollRatio;
        }
      });
    },
    [scrollContainerRef],
  );

  if (!optimization) {
    return null;
  }

  const renderContent = () => {
    if (isPending && !logContent) {
      return (
        <Loader
          message={isInProgress ? "Waiting for logs..." : "Loading logs..."}
          className="min-h-32"
        />
      );
    }

    if (!logContent) {
      return (
        <div className="flex flex-1 flex-col items-center justify-center gap-2">
          {isInProgress && <Spinner size="small" />}
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
          <div className="flex items-center gap-2">
            <h3 className="comet-body-s-accented">Logs</h3>
            {lastUpdatedAt && (
              <TooltipWrapper
                content={`Last updated at ${formatDate(lastUpdatedAt, {
                  includeSeconds: true,
                })}`}
              >
                <Clock className="size-3.5 text-muted-slate" />
              </TooltipWrapper>
            )}
            {isInProgress && logContent && <Spinner size="xs" />}
          </div>

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
            {logContent && (
              <TooltipWrapper content="Fullscreen">
                <Button variant="ghost" size="icon-xs" onClick={openFullscreen}>
                  <Maximize2 className="size-3.5" />
                </Button>
              </TooltipWrapper>
            )}
          </div>
        </div>

        {renderContent()}
      </CardContent>

      <OptimizationLogsFullscreenDialog
        open={isFullscreen}
        onOpenChange={setIsFullscreen}
        onClose={handleFullscreenClose}
        logContent={logContent}
        logHtml={logHtml}
        isInProgress={Boolean(isInProgress)}
        lastUpdatedAt={lastUpdatedAt}
        hasNewLogs={hasNewLogs}
        initialScrollRatio={initialScrollRatio}
      />
    </Card>
  );
};

export default OptimizationLogs;
