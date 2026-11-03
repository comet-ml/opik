import { CircleCheck, LucideIcon, ThumbsDown } from "lucide-react";
import { buildDocsUrl } from "@/v2/lib/utils";
import {
  AGENT_INSIGHTS_ISSUE_SEVERITY,
  AGENT_INSIGHTS_ISSUE_STATUS,
  AgentInsightsIssue,
  AgentInsightsJob,
} from "@/types/signals";

export const DIAGNOSTICS_DOCS_URL = buildDocsUrl("/tracing/diagnostics");

// How each closed status is shown wherever it appears (close dialog, list, tabs).
export const CLOSED_STATUS_META: Record<
  AGENT_INSIGHTS_ISSUE_STATUS.resolved | AGENT_INSIGHTS_ISSUE_STATUS.closed,
  { icon: LucideIcon; label: string }
> = {
  [AGENT_INSIGHTS_ISSUE_STATUS.resolved]: {
    icon: CircleCheck,
    label: "Resolved",
  },
  [AGENT_INSIGHTS_ISSUE_STATUS.closed]: {
    icon: ThumbsDown,
    label: "Not useful",
  },
};

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

// Figma: 24px outline buttons with 14px text and icons, 4px radius (Settings,
// Close issue, Reopen).
export const ACTION_BUTTON_CLASS = "h-6 gap-1 rounded px-2 text-sm font-medium";

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

// The current results predate the saved guidance: the job has run and the guidance
// version differs from the one the results were produced with (absent = 0).
export const isGuidanceOutdated = (job?: AgentInsightsJob | null): boolean =>
  Boolean(job?.last_scan_at) &&
  (job?.guidance_version ?? 0) !== (job?.results_guidance_version ?? 0);

// Issues closed as not useful aren't problems, so their traces don't count.
export const countAffectedTraces = (issues: AgentInsightsIssue[]): number =>
  issues
    .filter((i) => i.status !== AGENT_INSIGHTS_ISSUE_STATUS.closed)
    .reduce((sum, i) => sum + i.total_occurrences, 0);

// What the guidance-changed callout offers: run, the out-of-credits popover, or nothing
// (no permission to run, or a run that can't start right now).
export const getGuidanceCalloutAction = ({
  canConfigure,
  canRun,
  isOutOfCredits,
}: {
  canConfigure: boolean;
  canRun: boolean;
  isOutOfCredits: boolean;
}): "run" | "outOfCredits" | null => {
  if (!canConfigure) return null;
  if (isOutOfCredits) return "outOfCredits";
  return canRun ? "run" : null;
};

// Settings is there from the start, even before the first run: auto-run for
// configurers, guidance for everyone on the project. Run waits until the job has
// something to show.
export const getHeaderControls = ({
  showClosed,
  canConfigure,
  guidanceEnabled,
  showJobControls,
}: {
  showClosed: boolean;
  canConfigure: boolean;
  guidanceEnabled: boolean;
  showJobControls: boolean;
}) => ({
  settings: !showClosed && (canConfigure || guidanceEnabled),
  run: !showClosed && canConfigure && showJobControls,
});
