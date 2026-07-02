import React from "react";
import { BugPlay } from "lucide-react";
import { AgentInsightsIssue } from "@/types/signals";
import { cn } from "@/lib/utils";
import { SEVERITY_DOT_MAP } from "@/v2/pages/SignalsPage/helpers";

const IssueSeverityBadge: React.FC<{
  severity?: AgentInsightsIssue["severity"];
  className?: string;
}> = ({ severity, className }) => (
  <span
    className={cn(
      "flex size-4 shrink-0 items-center justify-center rounded",
      severity ? SEVERITY_DOT_MAP[severity] : "bg-light-slate",
      className,
    )}
  >
    <BugPlay className="size-2 text-white" />
  </span>
);

export default IssueSeverityBadge;
