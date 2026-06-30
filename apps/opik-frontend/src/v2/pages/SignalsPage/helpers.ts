import { AGENT_INSIGHTS_ISSUE_SEVERITY } from "@/types/signals";

export const SEVERITY_LABEL_MAP: Record<AGENT_INSIGHTS_ISSUE_SEVERITY, string> =
  {
    [AGENT_INSIGHTS_ISSUE_SEVERITY.critical]: "Critical",
    [AGENT_INSIGHTS_ISSUE_SEVERITY.high]: "High",
    [AGENT_INSIGHTS_ISSUE_SEVERITY.medium]: "Medium",
    [AGENT_INSIGHTS_ISSUE_SEVERITY.low]: "Low",
  };

export const SEVERITY_DOT_MAP: Record<AGENT_INSIGHTS_ISSUE_SEVERITY, string> = {
  [AGENT_INSIGHTS_ISSUE_SEVERITY.critical]: "bg-[#DC2626]",
  [AGENT_INSIGHTS_ISSUE_SEVERITY.high]: "bg-[#F43F5E]",
  [AGENT_INSIGHTS_ISSUE_SEVERITY.medium]: "bg-[#F59E0B]",
  [AGENT_INSIGHTS_ISSUE_SEVERITY.low]: "bg-[#94A3B8]",
};

// Multi-day issues show the latest day's count (matches the prose) plus the
// cross-day total; single-day collapses to just the total.
export const formatOccurrences = (
  total: number,
  latest: number,
  daysReported: number,
): string =>
  daysReported > 1
    ? `${total.toLocaleString()} total · ${latest.toLocaleString()} latest`
    : total.toLocaleString();
