import React from "react";
import { Tag } from "@/ui/tag";
import { ExperimentItemStatus } from "@/types/evaluation-suites";

const STATUS_CONFIG: Record<
  ExperimentItemStatus,
  { label: string; variant: "green" | "red" | "gray" }
> = {
  [ExperimentItemStatus.PASSED]: {
    label: "Passed",
    variant: "green",
  },
  [ExperimentItemStatus.FAILED]: {
    label: "Failed",
    variant: "red",
  },
  [ExperimentItemStatus.SKIPPED]: {
    label: "Skipped",
    variant: "gray",
  },
};

type PassFailBadgeProps = {
  status?: ExperimentItemStatus;
};

const PassFailBadge: React.FC<PassFailBadgeProps> = ({ status }) => {
  if (!status) return null;

  const config = STATUS_CONFIG[status];

  return <Tag variant={config.variant}>{config.label}</Tag>;
};

export default PassFailBadge;
