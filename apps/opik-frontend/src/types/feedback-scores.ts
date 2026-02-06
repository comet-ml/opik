import { ReactNode } from "react";

export type FeedbackScoreCustomMeta = {
  scoreName?: string;
  colorMap?: Record<string, string>;
  prefixIcon?: ReactNode;
  scoreValue?: number | string;
};
