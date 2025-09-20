import { TraceFeedbackScore } from "@/types/traces";

export type ExpandingFeedbackScoreRow = TraceFeedbackScore & {
  id: string;
  subRows?: ExpandingFeedbackScoreRow[];
  author?: string;
};
