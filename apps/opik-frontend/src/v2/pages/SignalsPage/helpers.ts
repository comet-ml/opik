import { AGENT_INSIGHTS_ISSUE_SEVERITY } from "@/types/signals";

// Mirrors the backend sweep's threshold: at this many traces in the window a project gets its
// automatic first diagnostic.
export const AUTO_FIRST_RUN_MIN_TRACES = 100;

// Mirrors the backend sweep's window: the automatic first run reads the traces of the 7 days before it is
// claimed, and the threshold above is counted over the same span.
export const AUTO_FIRST_RUN_WINDOW_MS = 7 * 24 * 60 * 60 * 1000;

// The longest an automatic run is expected to take. Not a backend limit, just a generous bound on runs
// that usually finish in minutes: past it, a run with no result is treated as lost, and a result that
// lands later is not attributed to it.
export const AUTO_RUN_MAX_DURATION_MS = 40 * 60 * 1000;

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
