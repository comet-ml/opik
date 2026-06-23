import { AGENT_INSIGHTS_ISSUE_SEVERITY } from "@/types/signals";

export const SEVERITY_LABEL_MAP: Record<AGENT_INSIGHTS_ISSUE_SEVERITY, string> =
  {
    [AGENT_INSIGHTS_ISSUE_SEVERITY.critical]: "Critical",
    [AGENT_INSIGHTS_ISSUE_SEVERITY.high]: "High",
    [AGENT_INSIGHTS_ISSUE_SEVERITY.medium]: "Medium",
    [AGENT_INSIGHTS_ISSUE_SEVERITY.low]: "Low",
  };
