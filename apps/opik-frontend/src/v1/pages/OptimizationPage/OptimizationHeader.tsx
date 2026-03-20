import React from "react";
import { RotateCw, X } from "lucide-react";
import { Tag } from "@/ui/tag";
import { Button } from "@/ui/button";
import { useNavigate } from "@tanstack/react-router";
import { OPTIMIZATION_STATUS, Optimization } from "@/types/optimizations";
import { STATUS_TO_VARIANT_MAP } from "@/constants/experiments";
import { IN_PROGRESS_OPTIMIZATION_STATUSES } from "@/lib/optimizations";
import useOptimizationStopMutation from "@/api/optimizations/useOptimizationStopMutation";
import useAppStore from "@/store/AppStore";
import NavigationTag from "@/shared/NavigationTag/NavigationTag";
import { RESOURCE_TYPE } from "@/shared/ResourceLink/ResourceLink";
import { formatDate } from "@/lib/date";

type OptimizationHeaderProps = {
  optimization?: Optimization;
  status?: OPTIMIZATION_STATUS;
  optimizationId?: string;
  isStudioOptimization?: boolean;
  canRerun?: boolean;
};

const OptimizationHeader: React.FC<OptimizationHeaderProps> = ({
  optimization,
  status,
  optimizationId,
  isStudioOptimization,
  canRerun,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const navigate = useNavigate();
  const { mutate: stopOptimization, isPending: isStoppingOptimization } =
    useOptimizationStopMutation();

  const isInProgress =
    status && IN_PROGRESS_OPTIMIZATION_STATUSES.includes(status);

  const canStop = isStudioOptimization && optimizationId && isInProgress;

  const handleStop = () => {
    if (!optimizationId) return;
    stopOptimization({ optimizationId });
  };

  const handleRerun = () => {
    if (!optimizationId) return;
    navigate({
      to: "/$workspaceName/optimizations/new",
      params: { workspaceName },
      search: { rerun: optimizationId },
    });
  };

  return (
    <div className="mb-4 flex min-h-8 flex-nowrap items-center justify-between gap-2">
      <div className="flex flex-col gap-1">
        <div className="flex items-center gap-2">
          <h1 className="comet-title-l truncate break-words">
            {optimization?.dataset_name || optimizationId}
          </h1>
          {optimization?.created_at && (
            <span className="comet-body-s text-muted-slate">
              {formatDate(optimization.created_at)}
            </span>
          )}
          {status && (
            <Tag
              variant={STATUS_TO_VARIANT_MAP[status]}
              size="md"
              className="capitalize"
            >
              {status}
            </Tag>
          )}
        </div>
        {optimization?.dataset_id && optimization?.dataset_name && (
          <NavigationTag
            id={optimization.dataset_id}
            name={`Go to ${optimization.dataset_name}`}
            resource={RESOURCE_TYPE.dataset}
            className="w-fit"
          />
        )}
      </div>
      <div className="flex items-center gap-2">
        {canRerun && (
          <Button variant="outline" size="sm" onClick={handleRerun}>
            <RotateCw className="mr-2 size-4" />
            Rerun
          </Button>
        )}
        {canStop && (
          <Button
            variant="destructive"
            size="sm"
            onClick={handleStop}
            disabled={isStoppingOptimization}
          >
            <X className="mr-2 size-4" />
            Stop Execution
          </Button>
        )}
      </div>
    </div>
  );
};

export default OptimizationHeader;
