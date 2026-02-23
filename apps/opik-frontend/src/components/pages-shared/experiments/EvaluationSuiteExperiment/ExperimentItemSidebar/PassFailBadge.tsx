import React from "react";
import { cn } from "@/lib/utils";
import { ExperimentItemStatus } from "@/types/evaluation-suites";

const STATUS_CONFIG: Record<
  ExperimentItemStatus,
  { label: string; className: string }
> = {
  [ExperimentItemStatus.PASSED]: {
    label: "PASSED",
    className: "bg-green-100 text-green-700",
  },
  [ExperimentItemStatus.FAILED]: {
    label: "FAILED",
    className: "bg-red-100 text-red-700",
  },
  [ExperimentItemStatus.SKIPPED]: {
    label: "SKIPPED",
    className: "bg-gray-100 text-gray-600",
  },
};

type PassFailBadgeProps = {
  status?: ExperimentItemStatus;
};

const PassFailBadge: React.FunctionComponent<PassFailBadgeProps> = ({
  status,
}) => {
  if (!status) return null;

  const config = STATUS_CONFIG[status];

  return (
    <span
      className={cn(
        "inline-flex items-center rounded-md px-2 py-0.5 text-xs font-semibold uppercase",
        config.className,
      )}
    >
      {config.label}
    </span>
  );
};

export default PassFailBadge;
