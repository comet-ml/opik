import { TagProps } from "@/ui/tag";
import { AGENT_INSIGHTS_ISSUE_STATUS } from "@/types/signals";

export const STATUS_LABEL_MAP: Record<AGENT_INSIGHTS_ISSUE_STATUS, string> = {
  [AGENT_INSIGHTS_ISSUE_STATUS.open]: "Open",
  [AGENT_INSIGHTS_ISSUE_STATUS.resolved]: "Resolved",
  [AGENT_INSIGHTS_ISSUE_STATUS.closed]: "Closed",
};

export const STATUS_TAG_VARIANT_MAP: Record<
  AGENT_INSIGHTS_ISSUE_STATUS,
  TagProps["variant"]
> = {
  [AGENT_INSIGHTS_ISSUE_STATUS.open]: "red",
  [AGENT_INSIGHTS_ISSUE_STATUS.resolved]: "green",
  [AGENT_INSIGHTS_ISSUE_STATUS.closed]: "gray",
};
