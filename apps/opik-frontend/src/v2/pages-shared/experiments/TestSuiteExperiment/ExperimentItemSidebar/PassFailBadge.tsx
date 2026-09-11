import React from "react";
import { Tag } from "@/ui/tag";
import { RunStatus } from "@/types/test-suites";

const STATUS_CONFIG: Record<
  RunStatus,
  { label: string; variant: "green" | "red" | "gray" }
> = {
  [RunStatus.PASSED]: {
    label: "Passed",
    variant: "green",
  },
  [RunStatus.FAILED]: {
    label: "Failed",
    variant: "red",
  },
  [RunStatus.SKIPPED]: {
    label: "Skipped",
    variant: "gray",
  },
};

type PassFailBadgeProps = {
  status?: RunStatus;
};

const PassFailBadge: React.FC<PassFailBadgeProps> = ({ status }) => {
  if (!status) return null;

  const config = STATUS_CONFIG[status];

  return <Tag variant={config.variant}>{config.label}</Tag>;
};

export default PassFailBadge;
