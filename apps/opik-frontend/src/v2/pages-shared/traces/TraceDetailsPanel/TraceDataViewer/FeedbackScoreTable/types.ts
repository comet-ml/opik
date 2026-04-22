import { TraceFeedbackScore } from "@/types/traces";

export type ExpandingFeedbackScoreRow = TraceFeedbackScore & {
  id: string;
  subRows?: ExpandingFeedbackScoreRow[];
  author?: string;
  span_type?: string;
  span_id?: string; // Single span ID for child rows grouped by type
};
