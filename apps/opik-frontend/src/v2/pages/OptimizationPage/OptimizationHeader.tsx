import React from "react";
import { Calendar, RotateCw, Sparkles, Workflow, X } from "lucide-react";
import { Tag } from "@/ui/tag";
import { Button } from "@/ui/button";
import { useNavigate } from "@tanstack/react-router";
import { OPTIMIZATION_STATUS, Optimization } from "@/types/optimizations";
import { STATUS_TO_VARIANT_MAP } from "@/constants/experiments";
import { IN_PROGRESS_OPTIMIZATION_STATUSES } from "@/lib/optimizations";
import useOptimizationStopMutation from "@/api/optimizations/useOptimizationStopMutation";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";
import NavigationTag from "@/shared/NavigationTag/NavigationTag";
import { RESOURCE_TYPE } from "@/shared/ResourceLink/ResourceLink";
import { formatDate } from "@/lib/date";
import { getOptimizationConfigItems } from "./optimizationHeaderConfig";
import OptimizationMetricPill from "./OptimizationMetricPill";

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
  const activeProjectId = useActiveProjectId();
  const navigate = useNavigate();
  const { mutate: stopOptimization, isPending: isStoppingOptimization } =
    useOptimizationStopMutation();

  const isInProgress =
    status && IN_PROGRESS_OPTIMIZATION_STATUSES.includes(status);

  const canStop = isStudioOptimization && optimizationId && isInProgress;

  const { model, algorithmLabel, metric } =
    getOptimizationConfigItems(optimization);

  const handleStop = () => {
    if (!optimizationId) return;
    stopOptimization({ optimizationId });
  };

  const handleRerun = () => {
    if (!optimizationId) return;
    navigate({
      to: "/$workspaceName/projects/$projectId/optimizations/new",
      params: { workspaceName, projectId: activeProjectId! },
      search: { rerun: optimizationId },
    });
  };

  return (
    <div className="mb-4 flex min-h-8 flex-nowrap items-start justify-between gap-2">
      <div className="flex min-w-0 flex-col gap-2">
        <div className="flex items-center gap-2">
          <h1 className="comet-title-xs truncate break-words">
            {optimization?.name || optimization?.dataset_name || optimizationId}
          </h1>
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
        <div className="flex flex-wrap items-center gap-2">
          {optimization?.created_at && (
            <span className="comet-body-s flex items-center gap-1 text-muted-slate">
              <Calendar className="size-3.5 shrink-0" />
              {formatDate(optimization.created_at)}
            </span>
          )}
          {optimization?.dataset_id && optimization?.dataset_name && (
            <NavigationTag
              id={optimization.dataset_id}
              name={optimization.dataset_name}
              resource={RESOURCE_TYPE.dataset}
              prefix="Dataset"
              className="w-fit"
            />
          )}
          {model && (
            <Tag variant="default" size="md">
              <span className="flex items-center gap-1.5">
                <Sparkles className="size-3.5 shrink-0 text-muted-slate" />
                <span className="truncate">{model}</span>
              </span>
            </Tag>
          )}
          {algorithmLabel && (
            <Tag variant="default" size="md">
              <span className="flex items-center gap-1.5">
                <Workflow className="size-3.5 shrink-0 text-muted-slate" />
                <span className="truncate">{algorithmLabel}</span>
              </span>
            </Tag>
          )}
          {metric && <OptimizationMetricPill metric={metric} />}
        </div>
      </div>
      <div className="flex shrink-0 items-center gap-2">
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
