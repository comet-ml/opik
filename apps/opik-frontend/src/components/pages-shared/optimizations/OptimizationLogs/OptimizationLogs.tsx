import React from "react";
import { RotateCw } from "lucide-react";
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

type OptimizationLogsProps = {
  optimization: Optimization | null;
};

const OptimizationLogs: React.FC<OptimizationLogsProps> = ({
  optimization,
}) => {
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

  if (!optimization) {
    return null;
  }

  const renderContent = () => {
    if (isPending && !logContent) {
      return <Loader />;
    }

    if (!logContent) {
      return (
        <div className="flex flex-1 items-center justify-center">
          <div className="comet-body-s text-muted-slate">No logs available</div>
        </div>
      );
    }

    return (
      <div className="flex flex-1 flex-col overflow-hidden">
        <div className="flex-1 overflow-auto rounded-sm border border-border bg-primary-foreground p-3">
          <pre className="whitespace-pre-wrap break-words font-mono text-xs leading-relaxed text-foreground">
            {logContent}
          </pre>
        </div>
      </div>
    );
  };

  return (
    <Card className="size-full">
      <CardContent className="flex h-full flex-col p-4">
        <div className="mb-3 flex items-center justify-between">
          <h3 className="comet-body-s-accented">Logs</h3>
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

        {renderContent()}
      </CardContent>
    </Card>
  );
};

export default OptimizationLogs;
