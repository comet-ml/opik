import React from "react";
import { Link } from "@tanstack/react-router";
import { Card, CardContent } from "@/components/ui/card";
import useAppStore from "@/store/AppStore";
import { Optimization } from "@/types/optimizations";

type OptimizationLogsProps = {
  optimization: Optimization | null;
};

const OptimizationLogs: React.FC<OptimizationLogsProps> = ({
  optimization,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const logs = optimization
    ? [
        "Evaluating prompt candidates",
        "Best prompt score: 0.39",
        "Generating new samples",
        "...",
        "...",
        "Optimization finished.",
      ]
    : [];

  return (
    <Card className="h-full">
      <CardContent className="flex h-full flex-col p-4">
        <div className="space-y-2">
          {logs.length === 0 ? (
            <div className="comet-body-s text-muted-slate">
              No logs available
            </div>
          ) : (
            <>
              {logs.map((log, index) => (
                <div key={index} className="comet-body-s text-primary">
                  {log}
                </div>
              ))}
              {optimization && (
                <Link
                  to="/$workspaceName/optimizations/$datasetId/$optimizationId/compare"
                  params={{
                    workspaceName,
                    datasetId: optimization.dataset_id,
                    optimizationId: optimization.id,
                  }}
                  search={{ optimizations: [optimization.id] }}
                  target="_blank"
                  className="comet-body-s inline-block text-primary underline"
                >
                  View all trial
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
