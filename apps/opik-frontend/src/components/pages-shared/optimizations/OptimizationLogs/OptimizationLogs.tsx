import React from "react";
import { Link } from "@tanstack/react-router";
import { Card, CardContent } from "@/components/ui/card";
import useAppStore from "@/store/AppStore";
import { Optimization } from "@/types/optimizations";

type OptimizationLogsProps = {
  optimization: Optimization | null;
  showViewAllTrialsLink?: boolean;
};

const OptimizationLogs: React.FC<OptimizationLogsProps> = ({
  optimization,
  showViewAllTrialsLink = false,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const logs = optimization
    ? ["Logs", "are", "not", "available", "...", "..."]
    : [];

  return (
    <Card className="size-full">
      <CardContent className="flex h-full flex-col p-4">
        <div className="space-y-2">
          {logs.length === 0 ? (
            <div className="comet-body-s text-muted-slate">
              No logs available
            </div>
          ) : (
            <>
              {logs.map((log) => (
                <div key={log} className="comet-body-s text-muted-slate">
                  {log}
                </div>
              ))}
              {showViewAllTrialsLink && optimization && (
                <Link
                  to="/$workspaceName/optimizations/$datasetId/compare"
                  params={{
                    workspaceName,
                    datasetId: optimization.dataset_id,
                  }}
                  search={{ optimizations: [optimization.id] }}
                  target="_blank"
                  className="comet-body-s inline-block text-primary underline"
                >
                  View all trials
                </Link>
              )}
            </>
          )}
        </div>
      </CardContent>
    </Card>
  );
};

export default OptimizationLogs;
