import { TagProps } from "@/ui/tag";
import { AGENT_INSIGHTS_ISSUE_SEVERITY } from "@/types/signals";

export const SEVERITY_LABEL_MAP: Record<AGENT_INSIGHTS_ISSUE_SEVERITY, string> =
  {
    [AGENT_INSIGHTS_ISSUE_SEVERITY.critical]: "Critical",
    [AGENT_INSIGHTS_ISSUE_SEVERITY.high]: "High",
    [AGENT_INSIGHTS_ISSUE_SEVERITY.medium]: "Medium",
    [AGENT_INSIGHTS_ISSUE_SEVERITY.low]: "Low",
  };

export const SEVERITY_TAG_VARIANT_MAP: Record<
  AGENT_INSIGHTS_ISSUE_SEVERITY,
  TagProps["variant"]
> = {
  [AGENT_INSIGHTS_ISSUE_SEVERITY.critical]: "red",
  [AGENT_INSIGHTS_ISSUE_SEVERITY.high]: "orange",
  [AGENT_INSIGHTS_ISSUE_SEVERITY.medium]: "yellow",
  [AGENT_INSIGHTS_ISSUE_SEVERITY.low]: "gray",
};
