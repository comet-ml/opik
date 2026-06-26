import React from "react";
import { AGENT_INSIGHTS_ISSUE_SEVERITY } from "@/types/signals";
import { cn } from "@/lib/utils";
import {
  SEVERITY_DOT_MAP,
  SEVERITY_LABEL_MAP,
} from "@/v2/pages/SignalsPage/helpers";

type SeverityTagProps = {
  severity: AGENT_INSIGHTS_ISSUE_SEVERITY;
  className?: string;
};

const SeverityTag: React.FC<SeverityTagProps> = ({ severity, className }) => (
  <span
    className={cn(
      "inline-flex shrink-0 items-center gap-1.5 rounded-md border border-border bg-primary-foreground px-1.5 py-0.5 text-xs text-foreground-secondary",
      className,
    )}
  >
    <span className={cn("size-1.5 rounded-full", SEVERITY_DOT_MAP[severity])} />
    {SEVERITY_LABEL_MAP[severity]}
  </span>
);

export default SeverityTag;
