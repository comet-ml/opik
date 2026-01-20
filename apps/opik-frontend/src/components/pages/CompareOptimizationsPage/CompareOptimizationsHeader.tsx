import React from "react";
import { RotateCw, X } from "lucide-react";
import { Tag } from "@/components/ui/tag";
import { Button } from "@/components/ui/button";
import { useNavigate } from "@tanstack/react-router";
import { OPTIMIZATION_STATUS } from "@/types/optimizations";
import { STATUS_TO_VARIANT_MAP } from "@/constants/experiments";
import { IN_PROGRESS_OPTIMIZATION_STATUSES } from "@/lib/optimizations";
import useOptimizationStopMutation from "@/api/optimizations/useOptimizationStopMutation";
import useAppStore from "@/store/AppStore";

type CompareOptimizationsHeaderProps = {
  title: string;
  status?: OPTIMIZATION_STATUS;
  optimizationId?: string;
  isStudioOptimization?: boolean;
  canRerun?: boolean;
};

const CompareOptimizationsHeader: React.FC<CompareOptimizationsHeaderProps> = ({
  title,
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
      <div className="flex items-center gap-2">
        <h1 className="comet-title-l truncate break-words">{title}</h1>
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
      {(canStop || canRerun) && (
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
      )}
    </div>
  );
};

export default CompareOptimizationsHeader;
