import { ReactNode } from "react";

export type FeedbackScoreCustomMeta = {
  feedbackKey?: string;
  colorMap?: Record<string, string>;
  prefixIcon?: ReactNode;
  scoreValue?: number | string;
};
