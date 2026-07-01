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
import { getOptimizationConfigItems } from "./optimizationHeaderConfig";
import OptimizationConfigPill, {
  CONFIG_PILL_ICON_CLASS,
} from "./OptimizationConfigPill";
import OptimizationModelPill from "./OptimizationModelPill";
import OptimizationMetricPill from "./OptimizationMetricPill";

/**
 * Per-status dot colours. The status tag chrome stays neutral (Figma), so the
 * dot alone carries the status colour. Keyed to the same palette as
 * STATUS_TO_VARIANT_MAP.
 */
const STATUS_DOT_COLOR: Record<OPTIMIZATION_STATUS, string> = {
  [OPTIMIZATION_STATUS.RUNNING]: "var(--color-green)",
  [OPTIMIZATION_STATUS.COMPLETED]: "var(--color-gray)",
  [OPTIMIZATION_STATUS.CANCELLED]: "var(--color-red)",
  [OPTIMIZATION_STATUS.INITIALIZED]: "var(--color-blue)",
  [OPTIMIZATION_STATUS.ERROR]: "var(--color-red)",
};

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
        <div className="flex items-center gap-1.5">
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
              className="bg-primary-foreground pl-1 pr-1.5 capitalize"
              icon={
                <span
                  className="size-1.5 shrink-0 rounded-full"
                  style={{ backgroundColor: STATUS_DOT_COLOR[status] }}
                />
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
