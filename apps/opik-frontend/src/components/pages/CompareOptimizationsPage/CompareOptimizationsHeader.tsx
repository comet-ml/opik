import React from "react";
import { X } from "lucide-react";
import { Tag } from "@/components/ui/tag";
import { Button } from "@/components/ui/button";
import { OPTIMIZATION_STATUS } from "@/types/optimizations";
import { STATUS_TO_VARIANT_MAP } from "@/constants/experiments";
import { IN_PROGRESS_OPTIMIZATION_STATUSES } from "@/lib/optimizations";
import useOptimizationStopMutation from "@/api/optimizations/useOptimizationStopMutation";

type CompareOptimizationsHeaderProps = {
  title: string;
  status?: OPTIMIZATION_STATUS;
  optimizationId?: string;
  isStudioOptimization?: boolean;
};

const CompareOptimizationsHeader: React.FC<CompareOptimizationsHeaderProps> = ({
  title,
  status,
  optimizationId,
  isStudioOptimization,
}) => {
  const { mutate: stopOptimization, isPending: isStoppingOptimization } =
    useOptimizationStopMutation();

  const canStop =
    isStudioOptimization &&
    optimizationId &&
    status &&
    IN_PROGRESS_OPTIMIZATION_STATUSES.includes(status);

  const handleStop = () => {
    if (!optimizationId) return;
    stopOptimization({ optimizationId });
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
      {canStop && (
        <Button
          variant="destructive"
          size="sm"
          onClick={handleStop}
          disabled={isStoppingOptimization}
        >
          <X className="size-4" />
          Stop Execution
        </Button>
      )}
    </div>
  );
};

export default CompareOptimizationsHeader;
