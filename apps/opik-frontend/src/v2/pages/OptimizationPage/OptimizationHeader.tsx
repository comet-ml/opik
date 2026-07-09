import React from "react";
import { Database, History, RotateCw, Route, X } from "lucide-react";
import { Button } from "@/ui/button";
import { useNavigate } from "@tanstack/react-router";
import { OPTIMIZATION_STATUS, Optimization } from "@/types/optimizations";
import { IN_PROGRESS_OPTIMIZATION_STATUSES } from "@/lib/optimizations";
import useOptimizationStopMutation from "@/api/optimizations/useOptimizationStopMutation";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";
import NavigationTag from "@/shared/NavigationTag/NavigationTag";
import { RESOURCE_TYPE } from "@/shared/ResourceLink/ResourceLink";
import { formatDate } from "@/lib/date";
import BackButton from "@/shared/BackButton/BackButton";
import {
  getOptimizationConfigItems,
  STATUS_DOT_COLOR,
} from "./optimizationHeaderConfig";
import OptimizationConfigPill, {
  CONFIG_PILL_ICON_CLASS,
} from "@/v2/pages-shared/optimizations/OptimizationConfigPill";
import OptimizationModelPill from "./OptimizationModelPill";
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
    <div className="flex min-h-8 flex-nowrap items-start justify-between gap-2">
      <div className="flex min-w-0 flex-col gap-2">
        <div className="flex items-center">
          <BackButton
            to="/$workspaceName/projects/$projectId/optimizations"
            tooltip="Back to optimization runs"
            iconClassName="size-3.5"
            className="-ml-1"
          />
          <h1 className="comet-body-accented truncate break-words">
            {optimization?.name || optimization?.dataset_name || optimizationId}
          </h1>
          {status && (
            <OptimizationConfigPill
              className="ml-1.5 bg-primary-foreground pl-1 pr-1.5 capitalize"
              icon={
                <span className="flex size-3 shrink-0 items-center justify-center">
                  <span
                    className="size-1.5 rounded-full"
                    style={{ backgroundColor: STATUS_DOT_COLOR[status] }}
                  />
                </span>
              }
            >
              {status}
            </OptimizationConfigPill>
          )}
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {optimization?.created_at && (
            <OptimizationConfigPill
              icon={<History className={CONFIG_PILL_ICON_CLASS} />}
            >
              {formatDate(optimization.created_at)}
            </OptimizationConfigPill>
          )}
          {optimization?.dataset_id && optimization?.dataset_name && (
            <NavigationTag
              id={optimization.dataset_id}
              name={optimization.dataset_name}
              resource={RESOURCE_TYPE.dataset}
              icon={Database}
              textSize="xs"
              className="w-fit rounded-md"
            />
          )}
          {model && <OptimizationModelPill model={model} />}
          {algorithmLabel && (
            <OptimizationConfigPill
              icon={<Route className={CONFIG_PILL_ICON_CLASS} />}
            >
              {algorithmLabel}
            </OptimizationConfigPill>
          )}
          {metric && <OptimizationMetricPill metric={metric} />}
        </div>
      </div>
      <div className="flex shrink-0 items-center gap-2">
        {canRerun && (
          <Button variant="outline" size="2xs" onClick={handleRerun}>
            <RotateCw className="size-3.5 shrink-0" />
            <span className="ml-1.5">Rerun</span>
          </Button>
        )}
        {canStop && (
          <Button
            variant="destructive"
            size="2xs"
            onClick={handleStop}
            disabled={isStoppingOptimization}
          >
            <X className="size-3.5 shrink-0" />
            <span className="ml-1.5">Stop Execution</span>
          </Button>
        )}
      </div>
    </div>
  );
};

export default OptimizationHeader;
